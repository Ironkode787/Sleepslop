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
    private const val FADE_OUT_MS = 45_000L // sleep-timer fade duration

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

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private var thread: Thread? = null
    @Volatile private var running = false

    private var focusRequest: android.media.AudioFocusRequest? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("sleepslop", Context.MODE_PRIVATE)
        _masterVolume.value = prefs.getFloat("master", 0.8f)
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

        val generators = HashMap<Sound, SoundGenerator>()
        val gains = HashMap<Sound, Float>()
        var masterGain = 0f

        while (running) {
            val snapshot = _mix.value

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
                timerFade = min(1f, remaining / FADE_OUT_MS.toFloat())
            }

            generators.keys.retainAll(snapshot.keys)
            gains.keys.retainAll(snapshot.keys)

            java.util.Arrays.fill(mixL, 0f)
            java.util.Arrays.fill(mixR, 0f)

            for ((sound, volume) in snapshot) {
                val gen = generators.getOrPut(sound) { sound.create() }
                gen.render(genL, genR, BUFFER_FRAMES)
                // Perceptual (squared) volume curve, ramped across the buffer.
                val target = volume * volume
                val start = gains[sound] ?: 0f
                val step = (target - start) / BUFFER_FRAMES
                var g = start
                for (i in 0 until BUFFER_FRAMES) {
                    g += step
                    mixL[i] += genL[i] * g
                    mixR[i] += genR[i] * g
                }
                gains[sound] = target
            }

            val masterTarget = _masterVolume.value.let { it * it } * timerFade
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
