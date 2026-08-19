package com.sleepslop.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.sleepslop.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.tanh

/**
 * Singleton synth engine. Owns the render thread that mixes all active
 * generators into a streaming [AudioTrack]. UI state is exposed as flows.
 */
@SuppressLint("StaticFieldLeak")
object AudioEngine {

    private const val BUFFER_FRAMES = 1024
    private const val FADE_OUT_MS = 60_000L // sleep-timer fade duration
    private const val FADE_IN_SECONDS = 3f  // gentle ramp when playback starts

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    /** Active sounds and their individual volumes (0..1). */
    private val _mix = MutableStateFlow<Map<Sound, Float>>(emptyMap())
    val mix = _mix.asStateFlow()

    private val _masterVolume = MutableStateFlow(0.8f)
    val masterVolume = _masterVolume.asStateFlow()

    /** Epoch millis when the sleep timer stops playback, or null. */
    private val _timerEndAt = MutableStateFlow<Long?>(null)
    val timerEndAt = _timerEndAt.asStateFlow()

    /** Room-EQ correction (dB per SpeakerTuner band), or null if never calibrated. */
    private val _eqGains = MutableStateFlow<FloatArray?>(null)
    val eqGains = _eqGains.asStateFlow()

    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled = _eqEnabled.asStateFlow()

    @Volatile private var eqChainL: EqChain? = null
    @Volatile private var eqChainR: EqChain? = null

    /** Per-sound parameter overrides (values not at their defaults). */
    private val _paramValues = MutableStateFlow<Map<Sound, Map<String, Float>>>(emptyMap())
    val paramValues = _paramValues.asStateFlow()

    /** Generator instances currently owned by the render thread. */
    private val liveGenerators = java.util.concurrent.ConcurrentHashMap<Sound, SoundGenerator>()

    /** Per-sound brightness tilt, -1 (darker) .. +1 (brighter). */
    private val _tilts = MutableStateFlow<Map<Sound, Float>>(emptyMap())
    val tilts = _tilts.asStateFlow()

    private val _driftEnabled = MutableStateFlow(false)
    val driftEnabled = _driftEnabled.asStateFlow()
    private val _driftIntensity = MutableStateFlow(0.6f)
    val driftIntensity = _driftIntensity.asStateFlow()

    private val _spaceWidth = MutableStateFlow(0.5f)
    val spaceWidth = _spaceWidth.asStateFlow()
    private val _spaceRoom = MutableStateFlow(0f)
    val spaceRoom = _spaceRoom.asStateFlow()

    private val drift = DriftEngine()
    private val space = SpaceDiffuser()

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private var thread: Thread? = null
    @Volatile private var running = false

    private var focusRequest: android.media.AudioFocusRequest? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("sleepslop", Context.MODE_PRIVATE)
        _masterVolume.value = prefs.getFloat("master", 0.8f)
        _driftEnabled.value = prefs.getBoolean("drift_on", false)
        _driftIntensity.value = prefs.getFloat("drift_i", 0.6f)
        _spaceWidth.value = prefs.getFloat("space_w", 0.5f)
        _spaceRoom.value = prefs.getFloat("space_r", 0f)
        prefs.getString("tilts", null)?.let { encoded ->
            val restored = mutableMapOf<Sound, Float>()
            for (entry in encoded.split(',')) {
                val eq = entry.indexOf('=')
                if (eq < 0) continue
                val sound = Sound.entries.firstOrNull { it.name == entry.substring(0, eq) }
                    ?: continue
                restored[sound] = entry.substring(eq + 1).toFloatOrNull() ?: continue
            }
            _tilts.value = restored
        }
        prefs.getString("params", null)?.let { encoded ->
            val restored = mutableMapOf<Sound, MutableMap<String, Float>>()
            for (entry in encoded.split(',')) {
                val eq = entry.indexOf('=')
                val slash = entry.indexOf('/')
                if (eq < 0 || slash < 0 || slash > eq) continue
                val sound = Sound.entries.firstOrNull { it.name == entry.substring(0, slash) }
                    ?: continue
                val value = entry.substring(eq + 1).toFloatOrNull() ?: continue
                restored.getOrPut(sound) { mutableMapOf() }[entry.substring(slash + 1, eq)] = value
            }
            _paramValues.value = restored
        }
        prefs.getString("eq", null)?.let { encoded ->
            val gains = encoded.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
            if (gains.size == SpeakerTuner.BAND_CENTERS.size) {
                _eqGains.value = gains
                _eqEnabled.value = prefs.getBoolean("eq_on", true)
                rebuildEq()
            }
        }
        val saved = prefs.getString("mix", null) ?: return
        val restored = mutableMapOf<Sound, Float>()
        for (entry in saved.split(',')) {
            val parts = entry.split(':')
            if (parts.size != 2) continue
            val sound = Sound.entries.firstOrNull { it.name == parts[0] } ?: continue
            restored[sound] = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: continue
        }
        _mix.value = restored
    }

    // ------------------------------------------------------------------ API

    fun toggle(sound: Sound) {
        val current = _mix.value.toMutableMap()
        if (current.containsKey(sound)) {
            current.remove(sound)
        } else {
            current[sound] = 0.75f
        }
        _mix.value = current
        persist()
        if (current.isEmpty()) {
            pause()
        } else if (!_isPlaying.value) {
            play()
        }
    }

    fun setVolume(sound: Sound, volume: Float) {
        val current = _mix.value.toMutableMap()
        if (!current.containsKey(sound)) return
        current[sound] = volume.coerceIn(0f, 1f)
        _mix.value = current
        persist()
    }

    /** Updates one generator parameter live and persists it. */
    fun setParam(sound: Sound, paramId: String, value: Float) {
        val all = _paramValues.value.toMutableMap()
        val per = (all[sound] ?: emptyMap()).toMutableMap()
        per[paramId] = value
        all[sound] = per
        _paramValues.value = all
        val encoded = all.entries.joinToString(",") { (s, params) ->
            params.entries.joinToString(",") { "${s.name}/${it.key}=${it.value}" }
        }
        prefs.edit().putString("params", encoded).apply()
        liveGenerators[sound]?.setParam(paramId, value)
    }

    fun paramValue(sound: Sound, param: Param): Float =
        _paramValues.value[sound]?.get(param.id) ?: param.default

    fun setTilt(sound: Sound, tilt: Float) {
        val all = _tilts.value.toMutableMap()
        if (tilt > -0.02f && tilt < 0.02f) all.remove(sound) else all[sound] = tilt.coerceIn(-1f, 1f)
        _tilts.value = all
        prefs.edit()
            .putString("tilts", all.entries.joinToString(",") { "${it.key.name}=${it.value}" })
            .apply()
    }

    fun setDriftEnabled(enabled: Boolean) {
        _driftEnabled.value = enabled
        prefs.edit().putBoolean("drift_on", enabled).apply()
    }

    fun setDriftIntensity(intensity: Float) {
        _driftIntensity.value = intensity.coerceIn(0f, 1f)
        prefs.edit().putFloat("drift_i", _driftIntensity.value).apply()
    }

    fun setSpaceWidth(width: Float) {
        _spaceWidth.value = width.coerceIn(0f, 1f)
        prefs.edit().putFloat("space_w", _spaceWidth.value).apply()
    }

    fun setSpaceRoom(room: Float) {
        _spaceRoom.value = room.coerceIn(0f, 1f)
        prefs.edit().putFloat("space_r", _spaceRoom.value).apply()
    }

    /** Replaces the whole mix + parameters in one step (used by presets). */
    fun applyPreset(mix: Map<Sound, Float>, params: Map<Sound, Map<String, Float>>, master: Float) {
        _mix.value = mix.filterValues { it > 0f }.mapValues { it.value.coerceIn(0f, 1f) }
        persist()
        if (params.isNotEmpty()) {
            val all = _paramValues.value.toMutableMap()
            for ((sound, perSound) in params) all[sound] = perSound
            _paramValues.value = all
            val encoded = all.entries.joinToString(",") { (s, p) ->
                p.entries.joinToString(",") { "${s.name}/${it.key}=${it.value}" }
            }
            prefs.edit().putString("params", encoded).apply()
            for ((sound, perSound) in params) {
                val gen = liveGenerators[sound] ?: continue
                perSound.forEach { (id, v) -> gen.setParam(id, v) }
            }
        }
        setMasterVolume(master)
        if (_mix.value.isEmpty()) pause()
    }

    fun setMasterVolume(volume: Float) {
        _masterVolume.value = volume.coerceIn(0f, 1f)
        prefs.edit().putFloat("master", _masterVolume.value).apply()
    }

    /** Starts playback; picks a default sound if nothing is selected. */
    fun play() {
        if (_isPlaying.value) return
        if (_mix.value.isEmpty()) {
            _mix.value = mapOf(Sound.BROWN to 0.75f)
            persist()
        }
        if (!requestFocus()) return
        _isPlaying.value = true
        startRenderThread()
        PlaybackService.start(appContext)
    }

    fun pause() {
        if (!_isPlaying.value) return
        _isPlaying.value = false
        running = false
        abandonFocus()
    }

    fun togglePlayback() = if (_isPlaying.value) pause() else play()

    /** Stores a new calibration curve (null clears it) and enables it. */
    fun setEq(gainsDb: FloatArray?) {
        _eqGains.value = gainsDb
        _eqEnabled.value = gainsDb != null
        if (gainsDb == null) {
            prefs.edit().remove("eq").remove("eq_on").apply()
        } else {
            prefs.edit()
                .putString("eq", gainsDb.joinToString(","))
                .putBoolean("eq_on", true)
                .apply()
        }
        rebuildEq()
    }

    fun setEqEnabled(enabled: Boolean) {
        if (_eqGains.value == null) return
        _eqEnabled.value = enabled
        prefs.edit().putBoolean("eq_on", enabled).apply()
        rebuildEq()
    }

    private fun rebuildEq() {
        val gains = _eqGains.value
        if (gains != null && _eqEnabled.value) {
            eqChainL = EqChain(gains)
            eqChainR = EqChain(gains)
        } else {
            eqChainL = null
            eqChainR = null
        }
    }

    /** Schedules a stop [minutes] from now, or cancels when null/0. */
    fun setTimer(minutes: Int?) {
        _timerEndAt.value = if (minutes == null || minutes <= 0) {
            null
        } else {
            System.currentTimeMillis() + minutes * 60_000L
        }
    }

    private fun persist() {
        val encoded = _mix.value.entries.joinToString(",") { "${it.key.name}:${it.value}" }
        prefs.edit().putString("mix", encoded).apply()
    }

    // ---------------------------------------------------------- audio focus

    private fun requestFocus(): Boolean {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    pause()
                }
            }
            .build()
        focusRequest = request
        return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ---------------------------------------------------------- render loop

    private fun startRenderThread() {
        if (thread?.isAlive == true) return
        running = true
        thread = Thread(::renderLoop, "sleepslop-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun renderLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, BUFFER_FRAMES * 2 * 4 * 2))
            .build()
        track.play()

        val genL = FloatArray(BUFFER_FRAMES)
        val genR = FloatArray(BUFFER_FRAMES)
        val mixL = FloatArray(BUFFER_FRAMES)
        val mixR = FloatArray(BUFFER_FRAMES)
        val out = FloatArray(BUFFER_FRAMES * 2)

        val gains = HashMap<Sound, Float>()
        val tiltFilters = HashMap<Sound, Pair<TiltFilter, TiltFilter>>()
        var masterGain = 0f
        var fadeIn = 0f

        while (running) {
            val snapshot = _mix.value

            drift.setIntensity(if (_driftEnabled.value) _driftIntensity.value else 0f)
            drift.advance(BUFFER_FRAMES)
            space.setWidth(_spaceWidth.value)
            space.setRoom(_spaceRoom.value)
            fadeIn = min(1f, fadeIn + BUFFER_FRAMES / (SAMPLE_RATE * FADE_IN_SECONDS))

            // Sleep timer: fade the last FADE_OUT_MS, then stop everything.
            var timerFade = 1f
            val endAt = _timerEndAt.value
            if (endAt != null) {
                val remaining = endAt - System.currentTimeMillis()
                if (remaining <= 0) {
                    _timerEndAt.value = null
                    pause()
                    break
                }
                // Quadratic fade tracks loudness perception better than linear.
                val linear = min(1f, remaining / FADE_OUT_MS.toFloat())
                timerFade = linear * linear
            }

            liveGenerators.keys.retainAll(snapshot.keys)
            gains.keys.retainAll(snapshot.keys)
            tiltFilters.keys.retainAll(snapshot.keys)

            java.util.Arrays.fill(mixL, 0f)
            java.util.Arrays.fill(mixR, 0f)

            for ((sound, volume) in snapshot) {
                val gen = liveGenerators.getOrPut(sound) {
                    sound.create().also { fresh ->
                        _paramValues.value[sound]?.forEach { (id, v) -> fresh.setParam(id, v) }
                    }
                }
                gen.render(genL, genR, BUFFER_FRAMES)
                // Perceptual (squared) volume curve × drift, ramped across the buffer.
                val target = volume * volume * drift.gain(sound)
                val start = gains[sound] ?: 0f
                val step = (target - start) / BUFFER_FRAMES
                var g = start
                val tilt = _tilts.value[sound] ?: 0f
                if (tilt != 0f) {
                    val filters = tiltFilters.getOrPut(sound) { TiltFilter() to TiltFilter() }
                    filters.first.setTilt(tilt)
                    filters.second.setTilt(tilt)
                    for (i in 0 until BUFFER_FRAMES) {
                        g += step
                        mixL[i] += filters.first.process(genL[i]) * g
                        mixR[i] += filters.second.process(genR[i]) * g
                    }
                } else {
                    for (i in 0 until BUFFER_FRAMES) {
                        g += step
                        mixL[i] += genL[i] * g
                        mixR[i] += genR[i] * g
                    }
                }
                gains[sound] = target
            }

            space.process(mixL, mixR, BUFFER_FRAMES)

            val masterTarget = _masterVolume.value.let { it * it } * timerFade * fadeIn
            val masterStep = (masterTarget - masterGain) / BUFFER_FRAMES
            val eqL = eqChainL
            val eqR = eqChainR
            var idx = 0
            for (i in 0 until BUFFER_FRAMES) {
                masterGain += masterStep
                var l = mixL[i]
                var r = mixR[i]
                if (eqL != null && eqR != null) {
                    l = eqL.process(l)
                    r = eqR.process(r)
                }
                // Soft clip so stacked sounds saturate gracefully.
                out[idx++] = tanh(l * masterGain)
                out[idx++] = tanh(r * masterGain)
            }

            track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
        }

        track.stop()
        track.release()
    }
}
