package com.sleepslop.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Speaker/room calibration.
 *
 * Plays synthesized pink noise through the current audio route (phone
 * speaker or Bluetooth) while recording with the microphone, averages the
 * received spectrum over many FFT windows, and compares octave-band
 * energies against the ideal pink slope (equal energy per octave). The
 * inverted deviation becomes a peaking-EQ correction curve.
 *
 * Because the measurement is a steady-state spectrum — not a timed sweep —
 * Bluetooth latency is irrelevant; a settle period simply skips the first
 * seconds so codec startup and the room's onset are excluded.
 */
object SpeakerTuner {

    /** Octave-spaced correction bands (Hz). */
    val BAND_CENTERS = floatArrayOf(63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f)

    const val MAX_BOOST_DB = 6f
    const val MAX_CUT_DB = 8f

    private const val FFT_SIZE = 4096
    private const val SETTLE_SECONDS = 2.5f
    private const val WINDOWS = 80 // ~7.4 s of analysis
    private const val PLAY_GAIN = 1.1f

    sealed interface State {
        data object Idle : State
        data class Measuring(val progress: Float) : State
        data class Done(val gainsDb: FloatArray) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    @Volatile private var cancelled = false

    fun start(context: Context) {
        if (_state.value is State.Measuring) return
        cancelled = false
        _state.value = State.Measuring(0f)
        val appContext = context.applicationContext
        thread(name = "sleepslop-tuner") { runCalibration(appContext) }
    }

    fun cancel() {
        cancelled = true
        _state.value = State.Idle
    }

    fun reset() {
        _state.value = State.Idle
    }

    private fun fail(message: String) {
        if (!cancelled) _state.value = State.Error(message)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the UI before start()
    private fun runCalibration(context: Context) {
        AudioEngine.pause()

        var track: AudioTrack? = null
        var record: AudioRecord? = null
        var player: Thread? = null
        val stopPlayback = java.util.concurrent.atomic.AtomicBoolean(false)

        try {
            // --- Recorder: prefer the unprocessed source so AGC/noise
            // suppression don't distort the measurement.
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val unprocessed =
                am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val source = if (unprocessed) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            val minRec = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            record = AudioRecord(
                source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minRec, FFT_SIZE * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                fail("The microphone is unavailable.")
                return
            }

            // --- Player: pink noise through the normal media route.
            val minPlay = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
            )
            track = AudioTrack.Builder()
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
                .setBufferSizeInBytes(maxOf(minPlay, 8192 * 2 * 4))
                .build()

            record.startRecording()
            track.play()

            val playTrack = track
            player = thread(name = "sleepslop-tuner-play") {
                val pink = PinkFilter()
                val rnd = Random(99)
                val buf = FloatArray(2048 * 2)
                while (!stopPlayback.get()) {
                    var i = 0
                    for (f in 0 until 2048) {
                        val s = pink.next(rnd.nextFloat() * 2f - 1f) * PLAY_GAIN
                        buf[i++] = s
                        buf[i++] = s
                    }
                    if (playTrack.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING) < 0) break
                }
            }

            val chunk = ShortArray(FFT_SIZE)

            // --- Settle: skip codec/route startup and let the room fill.
            val settleSamples = (SETTLE_SECONDS * SAMPLE_RATE).toInt()
            var skipped = 0
            while (skipped < settleSamples) {
                if (cancelled) return
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    fail("Recording failed — try again.")
                    return
                }
                skipped += n
                _state.value = State.Measuring(0.2f * skipped / settleSamples)
            }

            // --- Welch-style averaged power spectrum.
            val window = FloatArray(FFT_SIZE) {
                (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1))).toFloat()
            }
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)
            val power = DoubleArray(FFT_SIZE / 2)
            var sumSquares = 0.0
            var sampleCount = 0L
            var peak = 0f

            for (w in 0 until WINDOWS) {
                if (cancelled) return
                var filled = 0
                while (filled < FFT_SIZE) {
                    val n = record.read(chunk, filled, FFT_SIZE - filled)
                    if (n <= 0) {
                        fail("Recording failed — try again.")
                        return
                    }
                    filled += n
                }
                for (i in 0 until FFT_SIZE) {
                    val s = chunk[i] / 32768f
                    if (s > peak) peak = s
                    if (-s > peak) peak = -s
                    sumSquares += (s * s).toDouble()
                    re[i] = s * window[i]
                    im[i] = 0f
                }
                sampleCount += FFT_SIZE
                Fft.transform(re, im)
                for (i in 0 until FFT_SIZE / 2) {
                    power[i] += (re[i] * re[i] + im[i] * im[i]).toDouble()
                }
                _state.value = State.Measuring(0.2f + 0.8f * (w + 1) / WINDOWS)
            }

            // --- Sanity checks on the captured level.
            val rms = sqrt(sumSquares / sampleCount)
            if (rms < 0.004) {
                fail("Couldn't hear the speaker. Raise the volume, move the phone closer, and try again.")
                return
            }
            if (peak > 0.98f) {
                fail("The microphone clipped. Lower the volume a little and try again.")
                return
            }

            // --- Octave-band energies vs. the ideal pink slope.
            // Pink noise has equal energy per octave, so through a flat
            // system every octave band sums to the same power.
            val sqrt2 = sqrt(2f)
            val bandDb = FloatArray(BAND_CENTERS.size)
            for (b in BAND_CENTERS.indices) {
                val lo = BAND_CENTERS[b] / sqrt2
                val hi = BAND_CENTERS[b] * sqrt2
                val loBin = ceil(lo * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtLeast(1)
                val hiBin = floor(hi * FFT_SIZE / SAMPLE_RATE).toInt()
                    .coerceAtMost(FFT_SIZE / 2 - 1)
                var sum = 0.0
                for (i in loBin..hiBin) sum += power[i]
                bandDb[b] = (10.0 * log10(sum + 1e-12)).toFloat()
            }
            val mean = bandDb.average().toFloat()
            val gains = FloatArray(BAND_CENTERS.size) { b ->
                (mean - bandDb[b]).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)
            }

            if (!cancelled) _state.value = State.Done(gains)
        } catch (e: Exception) {
            fail("Calibration failed: ${e.message ?: "unknown error"}")
        } finally {
            stopPlayback.set(true)
            player?.join(1000)
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }
}

/** A chain of peaking filters realizing one calibration curve (one channel). */
class EqChain(gainsDb: FloatArray) {
    private val filters: Array<Biquad>

    init {
        val active = ArrayList<Biquad>()
        for (i in SpeakerTuner.BAND_CENTERS.indices) {
            val gain = gainsDb.getOrElse(i) { 0f }
            if (gain > 0.5f || gain < -0.5f) {
                // Q≈1.1 gives octave-wide bells with gentle overlap.
                active.add(Biquad().peaking(SpeakerTuner.BAND_CENTERS[i], 1.1f, gain))
            }
        }
        filters = active.toTypedArray()
    }

    fun process(x: Float): Float {
        var y = x
        for (f in filters) y = f.process(y)
        return y
    }
}
