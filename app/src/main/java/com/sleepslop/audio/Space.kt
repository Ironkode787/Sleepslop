package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// --------------------------------------------------------------- file tuning

/** Tiny offset injected into every recursive path so denormals never appear. */
private const val DENORM = 1e-20f

/** Hard ceiling on ANY recursive coefficient in [SpaceDiffuser]. */
private const val MAX_FEEDBACK = 0.65f

/** Nominal loop gain of the cross-coupled delay pair (before the cap). */
private const val LOOP_FEEDBACK = 0.5f

/** Schroeder allpass coefficient (before the cap). */
private const val ALLPASS_G = 0.5f

/** Damping cutoff inside each feedback path — keeps the tail dark. */
private const val DAMP_HZ = 2200f

/** Bass below this stays where it was: only the side signal above it widens. */
private const val SIDE_HP_HZ = 200f

/** Output trim on the reverb tap so wet never out-runs dry. */
private const val WET_TRIM = 0.6f

/** Per-block one-pole coefficient for width/room smoothing (~4 blocks). */
private const val BLOCK_SMOOTH = 0.25f

/** Below this the tail is inaudible (-120 dB) and the room can be bypassed. */
private const val TAIL_FLOOR = 1e-6f

private val HALF_PI = (PI / 2.0).toFloat()

/** Orthonormal 2x2 mixing coefficient — energy preserving, so loop gain == feedback. */
private val ROT = (1.0 / kotlin.math.sqrt(2.0)).toFloat()

private fun msToSamples(ms: Float): Int = (SAMPLE_RATE * ms / 1000f).toInt()

private fun isPrime(n: Int): Boolean {
    if (n < 2) return false
    if (n % 2 == 0) return n == 2
    var d = 3
    while (d * d <= n) {
        if (n % d == 0) return false
        d += 2
    }
    return true
}

/** Largest prime <= [n] (never below 2). Mutually prime delays kill flutter. */
private fun primeAtOrBelow(n: Int): Int {
    var c = if (n < 2) 2 else n
    while (c > 2 && !isPrime(c)) c--
    return c
}

/**
 * Master stereo "space" effect: a mid/side width control plus a small, dark,
 * sleep-friendly diffusion tail.
 *
 * Runs in place on the summed stereo mix once per buffer, before EQ and the
 * soft clip. Everything is preallocated in the constructor; [process] performs
 * zero allocation and takes no locks. Parameters may be pushed from the UI
 * thread at any time — they are smoothed toward their targets at block rate,
 * so slider moves never click.
 *
 * Topology (44.1 kHz):
 *  - width: mid/side rebalance, the side signal split at ~200 Hz so widening
 *    only lifts the part above the split and bass stays centred / mono safe.
 *  - room: 2 series Schroeder allpasses per channel (223 / 449 samples left,
 *    293 / 523 right, g = 0.5) feeding a cross-coupled delay pair
 *    (2069 samples ~ 47 ms left, 2999 ~ 68 ms right) mixed through an
 *    orthonormal rotation, each feedback leg damped by a one-pole at 2.2 kHz.
 *    All delay lengths are mutually prime, so the tail never rings.
 *
 * Stability: every recursive coefficient is clamped to [MAX_FEEDBACK] no matter
 * what the parameters say, the feedback matrix is energy preserving (it cannot
 * amplify), and a denormal offset is added on every recursive write.
 */
class SpaceDiffuser {

    // Targets are written from the UI thread; float writes are atomic on the JVM.
    @Volatile private var targetWidth = 0.5f
    @Volatile private var targetRoom = 0f

    // Smoothed values, owned by the render thread.
    private var width = 0.5f
    private var room = 0f

    /** Peak level seen in the delay pair last block — used to drain the tail. */
    private var tailLevel = 0f

    /** Capped once, here, so no parameter path can ever raise it. */
    private val fb = LOOP_FEEDBACK.coerceIn(-MAX_FEEDBACK, MAX_FEEDBACK)

    private val apL1 = Allpass(primeAtOrBelow(msToSamples(5.1f)), ALLPASS_G)
    private val apL2 = Allpass(primeAtOrBelow(msToSamples(10.3f)), ALLPASS_G)
    private val apR1 = Allpass(primeAtOrBelow(msToSamples(6.7f)), ALLPASS_G)
    private val apR2 = Allpass(primeAtOrBelow(msToSamples(12.1f)), ALLPASS_G)

    private val delayL = Delay(primeAtOrBelow(msToSamples(47f)))
    private val delayR = Delay(primeAtOrBelow(msToSamples(68f)))

    private val dampL = OnePoleLp().setCutoff(DAMP_HZ)
    private val dampR = OnePoleLp().setCutoff(DAMP_HZ)

    private val sideLp = OnePoleLp().setCutoff(SIDE_HP_HZ)

    /** 0..1, 0.5 = untouched, 0 = mono, 1 = side boosted ~6 dB above 200 Hz. */
    fun setWidth(w: Float) {
        targetWidth = w.coerceIn(0f, 1f)
    }

    /** 0..1 wet level of the diffusion (equal power against dry); 0 = dry. */
    fun setRoom(r: Float) {
        targetRoom = r.coerceIn(0f, 1f)
    }

    /** Processes [frames] stereo frames in place. */
    fun process(left: FloatArray, right: FloatArray, frames: Int) {
        if (frames <= 0) return

        var wNext = width + (targetWidth - width) * BLOCK_SMOOTH
        var rNext = room + (targetRoom - room) * BLOCK_SMOOTH
        if (abs(wNext - 0.5f) < 1e-4f) wNext = 0.5f
        if (rNext < 1e-4f) rNext = 0f

        val widthActive = width != 0.5f || wNext != 0.5f
        // Keep running while the tail still has energy, so room-off drains
        // smoothly instead of chopping the reverb.
        val roomActive = room > 0f || rNext > 0f || tailLevel > TAIL_FLOOR

        if (!widthActive && !roomActive) {
            width = wNext
            room = rNext
            return
        }

        // Equal-power dry/wet, ramped across the block.
        val dryA = cos(room * HALF_PI)
        val wetA = sin(room * HALF_PI)
        val dryB = cos(rNext * HALF_PI)
        val wetB = sin(rNext * HALF_PI)
        val dryStep = (dryB - dryA) / frames
        val wetStep = (wetB - wetA) / frames
        val wStep = (wNext - width) / frames

        // Purely draining: stop injecting new signal so the tail can die out.
        val feed = if (room == 0f && rNext == 0f) 0f else 1f

        var w = width
        var dry = dryA
        var wet = wetA
        var peak = 0f

        for (i in 0 until frames) {
            w += wStep
            dry += dryStep
            wet += wetStep

            var l = left[i]
            var r = right[i]

            if (roomActive) {
                val aL = feed * apL2.process(apL1.process(l))
                val aR = feed * apR2.process(apR1.process(r))

                val dL = delayL.read()
                val dR = delayR.read()

                // Energy-preserving rotation: cross-feeds the pair without gain.
                val xL = (dL + dR) * ROT
                val xR = (dL - dR) * ROT

                delayL.write(aL + fb * dampL.process(xL) + DENORM)
                delayR.write(aR + fb * dampR.process(xR) + DENORM)

                val a = max(abs(dL), abs(dR))
                if (a > peak) peak = a

                l = l * dry + dL * WET_TRIM * wet
                r = r * dry + dR * WET_TRIM * wet
            }

            if (widthActive) {
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f
                val sLow = sideLp.process(side)
                val sHigh = side - sLow
                val gSide = w + w                 // 0..2 : 1.0 at w == 0.5
                val gLow = min(gSide, 1f)         // never widen the bass
                val s = sLow * gLow + sHigh * gSide
                l = mid + s
                r = mid - s
            }

            left[i] = l
            right[i] = r
        }

        tailLevel = if (roomActive) peak else 0f
        width = wNext
        room = rNext
    }

    // ------------------------------------------------------------- internals

    /** Schroeder allpass: flat magnitude, smears transients into diffusion. */
    private class Allpass(size: Int, g: Float) {
        private val buf = FloatArray(size)
        private val g = g.coerceIn(-MAX_FEEDBACK, MAX_FEEDBACK)
        private var idx = 0

        fun process(x: Float): Float {
            val d = buf[idx]
            val v = x + g * d + DENORM
            buf[idx] = v
            idx++
            if (idx >= buf.size) idx = 0
            return d - g * v
        }
    }

    /** Fixed-length circular delay; read() then write() gives `size` samples. */
    private class Delay(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0

        fun read(): Float = buf[idx]

        fun write(x: Float) {
            buf[idx] = x
            idx++
            if (idx >= buf.size) idx = 0
        }
    }
}

/**
 * Per-sound spectral tilt: about +/-6 dB of shelf-like brightness pivoting near
 * 1 kHz. Mono — use one instance per channel.
 *
 * The signal is split by a one-pole at the pivot into low = lp(x) and
 * high = x - low, then the two halves are scaled by gains that move in
 * opposite directions, so the pivot frequency stays at unity gain and t = 0 is
 * a bit-exact passthrough. [setTilt] is safe to call from the UI thread; it
 * only precomputes two floats (writes of which are atomic on the JVM) so
 * [process] stays branch-free: one multiply-add for the filter and two more
 * for the mix.
 */
class TiltFilter {

    private val lp = OnePoleLp().setCutoff(PIVOT_HZ)

    @Volatile private var lowGain = 1f
    @Volatile private var highGain = 1f

    /** -1 = darkest, 0 = flat, +1 = brightest. */
    fun setTilt(t: Float) {
        val c = t.coerceIn(-1f, 1f)
        lowGain = 10f.pow(-c * TILT_DB / 20f)
        highGain = 10f.pow(c * TILT_DB / 20f)
    }

    fun process(x: Float): Float {
        val low = lp.process(x)
        return low * lowGain + (x - low) * highGain
    }

    private companion object {
        const val PIVOT_HZ = 1000f
        const val TILT_DB = 6f
    }
}
