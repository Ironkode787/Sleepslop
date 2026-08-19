package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Water.kt — a ground-up rework of the three water scenes.
 *
 * Everything here is synthesized from noise, envelopes and filters; nothing is
 * sampled. The shared machinery at the top of the file (grain pools, a
 * band-limited random walk, a small tonal voice pool) is what lets each scene
 * be built as *layers* rather than as one filtered hiss — real water is always
 * a distant bed plus a dense middle plus a few individual close events, all
 * co-modulated by something slower.
 *
 * Threading follows the house convention: `setParam` runs on the UI thread and
 * only writes `@Volatile` floats; the render thread reads them once per control
 * block and glides its own smoothed copies toward them, so sliders never
 * zipper. Nothing allocates once a generator is constructed.
 */

/** Control-rate period in samples: filters are retuned this often, not per sample. */
private const val CTRL_BLOCK = 128

private val TWO_PI_D = 2.0 * PI
private val HALF_PI_F = (PI / 2.0).toFloat()
private val PI_F = PI.toFloat()

/**
 * Raised-cosine (Hann) window sampled at 257 points. Grain envelopes index
 * this instead of calling cos() per sample — with up to ~50 live grains that
 * difference is the whole CPU budget.
 */
private val HANN = FloatArray(257) { 0.5f - 0.5f * cos(2.0 * PI * it / 256.0).toFloat() }

private fun Random.bip(): Float = nextFloat() * 2f - 1f

/**
 * A band-limited random walk: every [minSec]..[maxSec] it picks a new target
 * inside [lo]..[hi] and glides toward it with time constant [tauSec]. Because
 * it never quite arrives before re-targeting, the output is smooth and never
 * periodic — this is what drives gusts, swell wander and tone drift.
 */
private class Wander(
    seed: Int,
    private val lo: Float,
    private val hi: Float,
    private val minSec: Float,
    private val maxSec: Float,
    private val tauSec: Float,
) {
    private val r = Random(seed)
    private var target = lo + r.nextFloat() * (hi - lo)

    var value: Float = target
        private set

    private var countdown = 0

    /** Call once per control block. */
    fun step(blockSamples: Int) {
        countdown -= blockSamples
        if (countdown <= 0) {
            countdown = ((minSec + r.nextFloat() * (maxSec - minSec)) * SAMPLE_RATE).toInt()
            target = lo + r.nextFloat() * (hi - lo)
        }
        val k = (blockSamples / (tauSec * SAMPLE_RATE)).coerceIn(0.0002f, 0.5f)
        value += (target - value) * k
    }
}

/**
 * Pool of windowed noise grains that share one downstream filter. Only the
 * *envelope* sum is produced here; the caller multiplies it into its own
 * filtered noise. That keeps a dense patter of hundreds of grains per second
 * down to a handful of adds per sample.
 */
private class GrainPool(private val capacity: Int) {
    private val live = BooleanArray(capacity)
    private val phase = FloatArray(capacity)
    private val inc = FloatArray(capacity)
    private val amp = FloatArray(capacity)
    private val gL = FloatArray(capacity)
    private val gR = FloatArray(capacity)
    private var cursor = 0

    fun spawn(amplitude: Float, lengthSamples: Float, gainL: Float, gainR: Float) {
        var slot = -1
        for (k in 0 until capacity) {
            val j = cursor + k
            val idx = if (j >= capacity) j - capacity else j
            if (!live[idx]) {
                slot = idx
                break
            }
        }
        if (slot < 0) return // pool saturated: drop the grain rather than steal
        cursor = if (slot + 1 >= capacity) 0 else slot + 1
        live[slot] = true
        phase[slot] = 0f
        inc[slot] = 1f / lengthSamples.coerceAtLeast(4f)
        amp[slot] = amplitude
        gL[slot] = gainL
        gR[slot] = gainR
    }

    /** Writes the summed L/R grain envelope into [out] (overwrites). */
    fun envelope(out: FloatArray) {
        var l = 0f
        var r = 0f
        for (i in 0 until capacity) {
            if (!live[i]) continue
            val p = phase[i]
            if (p >= 1f) {
                live[i] = false
                continue
            }
            val w = HANN[(p * 256f).toInt()] * amp[i]
            l += w * gL[i]
            r += w * gR[i]
            phase[i] = p + inc[i]
        }
        out[0] = l
        out[1] = r
    }
}

/**
 * Pool of windowed noise grains that each own a bandpass, so every grain can
 * have its own colour and stereo position. Used for the sparse "close" events
 * (rain splats) where individuality matters more than density.
 */
private class SplatPool(private val capacity: Int) {
    private val live = BooleanArray(capacity)
    private val phase = FloatArray(capacity)
    private val inc = FloatArray(capacity)
    private val amp = FloatArray(capacity)
    private val gL = FloatArray(capacity)
    private val gR = FloatArray(capacity)
    private val filt = Array(capacity) { Biquad() }
    private var cursor = 0

    fun spawn(
        amplitude: Float,
        lengthSamples: Float,
        centerHz: Float,
        q: Float,
        gainL: Float,
        gainR: Float,
    ) {
        var slot = -1
        for (k in 0 until capacity) {
            val j = cursor + k
            val idx = if (j >= capacity) j - capacity else j
            if (!live[idx]) {
                slot = idx
                break
            }
        }
        if (slot < 0) return
        cursor = if (slot + 1 >= capacity) 0 else slot + 1
        live[slot] = true
        phase[slot] = 0f
        inc[slot] = 1f / lengthSamples.coerceAtLeast(8f)
        amp[slot] = amplitude
        gL[slot] = gainL
        gR[slot] = gainR
        filt[slot].bandpass(centerHz, q)
    }

    /** Adds every live splat into [out] — does not clear it first. */
    fun add(noise: Float, out: FloatArray) {
        for (i in 0 until capacity) {
            if (!live[i]) continue
            val p = phase[i]
            if (p >= 1f) {
                live[i] = false
                continue
            }
            val s = filt[i].process(noise) * HANN[(p * 256f).toInt()] * amp[i]
            out[0] += s * gL[i]
            out[1] += s * gR[i]
            phase[i] = p + inc[i]
        }
    }
}

/**
 * Polyphonic pool of pitched water transients: an optional noise click plus up
 * to two exponentially decaying partials whose frequencies glide. A stone
 * plink is a click plus two partials gliding slightly *up*; a Minnaert bubble
 * is one partial gliding up steeply with no click. Both live here.
 */
private class TonalPool(private val capacity: Int) {
    private val live = BooleanArray(capacity)
    private val ph1 = DoubleArray(capacity)
    private val ph2 = DoubleArray(capacity)
    private val inc1 = DoubleArray(capacity)
    private val inc2 = DoubleArray(capacity)
    private val gl1 = DoubleArray(capacity)
    private val gl2 = DoubleArray(capacity)
    private val am1 = FloatArray(capacity)
    private val am2 = FloatArray(capacity)
    private val env = FloatArray(capacity)
    private val dec = FloatArray(capacity)
    private val clk = FloatArray(capacity)
    private val clkDec = FloatArray(capacity)
    private val atkPos = IntArray(capacity)
    private val atkLen = IntArray(capacity)
    private val gL = FloatArray(capacity)
    private val gR = FloatArray(capacity)
    private var cursor = 0

    /**
     * [glide1]/[glide2] are the *total* frequency multipliers reached over one
     * decay time; they are converted to a per-sample ratio here so the render
     * loop only multiplies.
     */
    fun spawn(
        f1: Float,
        f2: Float,
        amp1: Float,
        amp2: Float,
        decaySamples: Float,
        glide1: Float,
        glide2: Float,
        clickAmp: Float,
        clickDecaySamples: Float,
        attackSamples: Int,
        gainL: Float,
        gainR: Float,
    ) {
        var slot = -1
        for (k in 0 until capacity) {
            val j = cursor + k
            val idx = if (j >= capacity) j - capacity else j
            if (!live[idx]) {
                slot = idx
                break
            }
        }
        if (slot < 0) return
        cursor = if (slot + 1 >= capacity) 0 else slot + 1

        val nd = decaySamples.coerceAtLeast(32f)
        live[slot] = true
        ph1[slot] = 0.0
        ph2[slot] = 0.0
        inc1[slot] = TWO_PI_D * f1 / SAMPLE_RATE
        inc2[slot] = TWO_PI_D * f2 / SAMPLE_RATE
        // per-sample ratio r such that r^nd == glide
        gl1[slot] = exp(ln(glide1.coerceAtLeast(0.05f).toDouble()) / nd)
        gl2[slot] = exp(ln(glide2.coerceAtLeast(0.05f).toDouble()) / nd)
        am1[slot] = amp1
        am2[slot] = amp2
        env[slot] = 1f
        dec[slot] = exp(-6.9f / nd) // -60 dB after nd samples
        clk[slot] = clickAmp
        clkDec[slot] = exp(-6.9f / clickDecaySamples.coerceAtLeast(8f))
        atkPos[slot] = 0
        atkLen[slot] = attackSamples.coerceAtLeast(1)
        gL[slot] = gainL
        gR[slot] = gainR
    }

    /** Adds every live voice into [out] — does not clear it first. */
    fun add(clickNoise: Float, out: FloatArray) {
        for (i in 0 until capacity) {
            if (!live[i]) continue
            val e = env[i]
            if (e < 0.0002f) {
                live[i] = false
                continue
            }
            var a = e
            val al = atkLen[i]
            if (atkPos[i] < al) {
                // First half of the Hann table is a click-free raised-cosine attack.
                a *= HANN[128 * atkPos[i] / al]
                atkPos[i]++
            }
            ph1[i] += inc1[i]
            ph2[i] += inc2[i]
            inc1[i] *= gl1[i]
            inc2[i] *= gl2[i]
            if (ph1[i] > TWO_PI_D) ph1[i] -= TWO_PI_D
            if (ph2[i] > TWO_PI_D) ph2[i] -= TWO_PI_D
            var s = (sin(ph1[i]) * am1[i] + sin(ph2[i]) * am2[i]).toFloat() * a
            s += clickNoise * clk[i]
            clk[i] *= clkDec[i]
            env[i] = e * dec[i]
            out[0] += s * gL[i]
            out[1] += s * gR[i]
        }
    }
}

// ---------------------------------------------------------------------------
// Rain
// ---------------------------------------------------------------------------

/**
 * **RainV2** — rain modelled as five simultaneous distances rather than one
 * band of hiss. What sells it is that they all breathe together but not
 * equally, which is exactly what a gust front does to a rainfield:
 *
 *  - **Distant wash**: pink noise lowpassed around 1.5–2.5 kHz, its level and
 *    cutoff wandering over 10–25 s. This is the rain two streets away, and it
 *    is the layer a gust moves *least*.
 *  - **Mid patter**: a Poisson stream of 100–400 tiny grains per second, 1–4 ms
 *    each, windowed with a raised cosine and pushed through one shared gentle
 *    bandpass. Deliberately no resonance and no pitch — individual raindrops
 *    landing on a surface are broadband ticks, and any ring at all reads as
 *    "synthetic plink". This is the body of the sound.
 *  - **Close splats**: 0.5–4 per second of larger 5–15 ms grains, each with its
 *    own low-Q 1–3 kHz bandpass and its own stereo position. `surface` lifts
 *    their centre frequency and level: soft foliage at 0, hard roof/window at 1.
 *  - **Hiss canopy**: highpassed 4–6 kHz noise whose level *tracks the patter
 *    density* through an envelope follower, so the top end swells and thins
 *    with the rain instead of sitting there as a constant tape hiss.
 *  - **Gust**: one 8–25 s random walk modulating everything, weighted so the
 *    distant wash barely moves (±14 %) while the close splat rate swings almost
 *    7:1. That unequal, coherent co-modulation is what the ear reads as weather.
 *  - **Gutter drips**: quasi-periodic soft low plops (0.7–2 s ± 30 % jitter),
 *    lowpassed so they are plops and not pings.
 *
 * Params: `intensity` 0..1 (0.5), `surface` 0..1 (0.35), `drips` 0..1 (0.25).
 * At defaults this measures RMS 0.098, peak 0.62, -25.8 dBA, with the gust
 * moving the 300 ms window level over about 3.9 dB.
 */
class RainV2 : SoundGenerator {

    @Volatile private var intensity = 0.5f
    @Volatile private var surface = 0.35f
    @Volatile private var drips = 0.25f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "intensity" -> intensity = value
            "surface" -> surface = value
            "drips" -> drips = value
        }
    }

    // Smoothed copies — render thread only.
    private var intS = 0.5f
    private var surfS = 0.35f
    private var dripS = 0.25f

    private val rnd = Random(9173)

    // Distant wash.
    private val washPinkL = PinkFilter()
    private val washPinkR = PinkFilter()
    private val washLpL = Biquad().lowpass(1800f, 0.7f)
    private val washLpR = Biquad().lowpass(1860f, 0.7f)
    private val washLevel = Wander(21, 0.62f, 1.00f, 10f, 25f, 7f)
    private val washTone = Wander(37, 1500f, 2500f, 12f, 26f, 9f)

    // The one gust that moves everything.
    private val gust = Wander(55, 0f, 1f, 8f, 25f, 5.5f)

    // Mid patter.
    private val patter = GrainPool(56)
    private val patterBpL = Biquad().bandpass(1400f, 0.55f)
    private val patterBpR = Biquad().bandpass(1480f, 0.55f)
    private val patterEnv = FloatArray(2)

    // Close splats.
    private val splats = SplatPool(10)
    private val splatAcc = FloatArray(2)

    // Hiss canopy.
    private val hissHpL = Biquad().highpass(4600f, 0.7f)
    private val hissHpR = Biquad().highpass(4800f, 0.7f)

    // Gutter drips.
    private val plops = TonalPool(4)
    private val plopAcc = FloatArray(2)
    private val plopLpL = Biquad().lowpass(1100f, 0.7f)
    private val plopLpR = Biquad().lowpass(1100f, 0.7f)
    private var plopCountdown = SAMPLE_RATE

    // Block-rate derived values.
    private var blockCountdown = 0
    private var washGain = 0f
    private var patterProb = 0f
    private var patterLenLo = 44f
    private var patterLenSpan = 132f
    private var patterGain = 0f
    private var splatProb = 0f
    private var splatCenter = 1800f
    private var splatGain = 0f
    private var hissGain = 0f
    private var plopAmp = 0f
    private var plopPeriod = 1.4f

    // Patter-density follower driving the hiss canopy (~70 ms).
    private var density = 0f
    private val densityK = 1f - exp(-1f / (0.070f * SAMPLE_RATE))

    private fun updateBlock() {
        intS += (intensity - intS) * 0.06f
        surfS += (surface - surfS) * 0.06f
        dripS += (drips - dripS) * 0.06f

        washLevel.step(CTRL_BLOCK)
        washTone.step(CTRL_BLOCK)
        gust.step(CTRL_BLOCK)
        val g = gust.value

        // Distant wash — least gust-modulated of all the layers.
        washLpL.lowpass(washTone.value, 0.7f)
        washLpR.lowpass(washTone.value * 1.06f, 0.7f)
        washGain = WASH_GAIN * washLevel.value * (0.86f + 0.28f * g) * (0.55f + 0.50f * intS)

        // Mid patter — the body. Density swings with the gust.
        val rate = (95f + 330f * intS) * (0.55f + 0.90f * g)
        patterProb = rate / SAMPLE_RATE
        val pc = 950f + 1500f * surfS
        patterBpL.bandpass(pc, 0.55f)
        patterBpR.bandpass(pc * 1.07f, 0.55f)
        patterLenLo = 0.0010f * SAMPLE_RATE
        patterLenSpan = (0.0030f - 0.0015f * surfS) * SAMPLE_RATE
        // Normalise for density so "more rain" gets denser, not just louder.
        patterGain = PATTER_GAIN / (0.55f + 0.45f * (rate / 300f))

        // Close splats — most gust-modulated of all the layers.
        splatProb = (0.5f + 3.5f * intS) * (0.30f + 1.75f * g) / SAMPLE_RATE
        splatCenter = 1100f + 1900f * surfS
        splatGain = SPLAT_GAIN * (0.70f + 0.70f * surfS)

        // Hiss canopy.
        hissHpL.highpass(4000f + 2000f * surfS, 0.7f)
        hissHpR.highpass(4200f + 2000f * surfS, 0.7f)
        hissGain = HISS_GAIN * (0.5f + 0.7f * intS) * (0.80f + 0.40f * g)

        // Gutter drips.
        plopAmp = dripS
        plopPeriod = 2.0f - 1.3f * dripS
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = CTRL_BLOCK
                updateBlock()
            }

            // --- distant wash -------------------------------------------------
            val wl = washLpL.process(washPinkL.next(rnd.bip())) * washGain
            val wr = washLpR.process(washPinkR.next(rnd.bip())) * washGain

            // --- mid patter ---------------------------------------------------
            if (rnd.nextFloat() < patterProb) {
                val p = rnd.nextFloat() * HALF_PI_F
                patter.spawn(
                    0.55f + rnd.nextFloat() * 0.80f,
                    patterLenLo + rnd.nextFloat() * patterLenSpan,
                    cos(p),
                    sin(p),
                )
            }
            patter.envelope(patterEnv)
            val pl = patterBpL.process(rnd.bip()) * patterEnv[0] * patterGain
            val pr = patterBpR.process(rnd.bip()) * patterEnv[1] * patterGain

            // --- hiss canopy, level tracking the patter density ---------------
            density += (0.5f * (patterEnv[0] + patterEnv[1]) - density) * densityK
            val hGain = hissGain * (0.30f + 0.95f * density)
            val hl = hissHpL.process(rnd.bip()) * hGain
            val hr = hissHpR.process(rnd.bip()) * hGain

            // --- close splats -------------------------------------------------
            if (rnd.nextFloat() < splatProb) {
                val p = rnd.nextFloat() * HALF_PI_F
                splats.spawn(
                    0.55f + rnd.nextFloat() * 0.70f,
                    (0.005f + rnd.nextFloat() * 0.010f) * SAMPLE_RATE,
                    splatCenter * (0.75f + rnd.nextFloat() * 0.55f),
                    0.9f,
                    cos(p),
                    sin(p),
                )
            }
            splatAcc[0] = 0f
            splatAcc[1] = 0f
            splats.add(rnd.bip(), splatAcc)

            // --- gutter drips -------------------------------------------------
            if (plopAmp > 0.01f && --plopCountdown <= 0) {
                // ±30 % jitter so it never locks into a metronome.
                plopCountdown = (plopPeriod * (0.7f + 0.6f * rnd.nextFloat()) * SAMPLE_RATE).toInt()
                val f = 175f + rnd.nextFloat() * 220f
                val p = rnd.nextFloat() * HALF_PI_F
                plops.spawn(
                    f1 = f,
                    f2 = f * 2.02f,
                    amp1 = 1f,
                    amp2 = 0.10f,
                    decaySamples = (0.09f + rnd.nextFloat() * 0.11f) * SAMPLE_RATE,
                    glide1 = 1.18f,
                    glide2 = 1.18f,
                    clickAmp = 0f,
                    clickDecaySamples = 32f,
                    attackSamples = (0.004f * SAMPLE_RATE).toInt(),
                    gainL = cos(p),
                    gainR = sin(p),
                )
            } else if (plopAmp <= 0.01f) {
                plopCountdown = SAMPLE_RATE
            }
            plopAcc[0] = 0f
            plopAcc[1] = 0f
            plops.add(0f, plopAcc)
            val plopScale = PLOP_GAIN * plopAmp

            left[i] = wl + pl + hl + splatAcc[0] * splatGain +
                plopLpL.process(plopAcc[0]) * plopScale
            right[i] = wr + pr + hr + splatAcc[1] * splatGain +
                plopLpR.process(plopAcc[1]) * plopScale
        }
    }

    private companion object {
        const val WASH_GAIN = 0.83f
        const val PATTER_GAIN = 0.157f
        const val SPLAT_GAIN = 0.138f
        const val HISS_GAIN = 0.157f
        const val PLOP_GAIN = 0.21f
    }
}

// ---------------------------------------------------------------------------
// Ocean
// ---------------------------------------------------------------------------

/**
 * One wave of [OceanV2]: an independent, randomly-lengthened cycle through
 * build → break → wash. Position is kept as a normalised cycle phase `u`, so
 * the whole shape scales with the period parameter without retuning anything.
 *
 *  - `u < 0.30` **build**: a smoothstep swell with the lowpass opening from
 *    240 Hz to about 1.2 kHz — water gathering, still dark.
 *  - `0.30 <= u < 0.44` **break**: the crest holds while the cutoff arcs up to
 *    ~3.3 kHz and a peaking EQ around 430 Hz adds up to +5 dB. The arc plus the
 *    mid lift is the soft "whoomp" of a wave folding; the 3.5 kHz ceiling is
 *    what stops it from turning into a white-noise shriek.
 *  - `u >= 0.44` **wash**: a long exponential decay (≈ 56 % of the cycle, so
 *    6–12 s at typical periods) with the cutoff *falling* back to ~190 Hz and
 *    the foam fizz thinning out with it.
 */
private class Wave(seed: Int, startU: Float, pan: Float) {
    private val r = Random(seed)
    private var u = startU
    private var mult = 0.65f + r.nextFloat() * 0.70f // period × ±35 %
    private var uInc = 1f / (14f * mult * SAMPLE_RATE)

    private val lpL = Biquad().lowpass(600f, 0.62f)
    private val lpR = Biquad().lowpass(630f, 0.62f)
    private val eqL = Biquad().peaking(420f, 0.9f, 0f)
    private val eqR = Biquad().peaking(445f, 0.9f, 0f)

    private val gainL = cos(pan * HALF_PI_F)
    private val gainR = sin(pan * HALF_PI_F)

    var envGain = 0f
        private set

    /** 0..1 spawn weight for the foam layer at the current cycle position. */
    var fizzRate = 0f
        private set

    private fun shape(x: Float): Float = when {
        x < BUILD_END -> {
            val b = x / BUILD_END
            b * b * (3f - 2f * b) // smoothstep
        }
        x < BREAK_END -> 1f
        else -> {
            val w = (x - BREAK_END) / (1f - BREAK_END)
            (exp(-3f * w) - EXP_M3) / (1f - EXP_M3) // reaches exactly 0 at u = 1
        }
    }

    private fun cutoff(x: Float): Float = when {
        x < BUILD_END -> 240f + 950f * shape(x)
        x < BREAK_END -> {
            val b = (x - BUILD_END) / (BREAK_END - BUILD_END)
            1190f + 2100f * sin(PI_F * b) // arcs up and back down, peak ≈ 3.3 kHz
        }
        else -> {
            val w = (x - BREAK_END) / (1f - BREAK_END)
            190f + 1000f * exp(-1.6f * w)
        }
    }

    /** Bump that is non-zero only across the break, zero at both edges. */
    private fun breakBump(x: Float): Float =
        if (x < BUILD_END || x >= BREAK_END) 0f
        else sin(PI_F * (x - BUILD_END) / (BREAK_END - BUILD_END))

    private fun fizzShape(x: Float): Float = when {
        x < BUILD_END -> 0.12f * shape(x)
        x < BREAK_END -> 1f
        else -> exp(-2.6f * (x - BREAK_END) / (1f - BREAK_END))
    }

    fun block(swellS: Float, foamS: Float, periodSec: Float) {
        // Glide toward the requested period so the slider moves audibly without
        // ever stepping the phase.
        val targetInc = 1f / (periodSec * mult * SAMPLE_RATE)
        uInc += (targetInc - uInc) * 0.05f

        val fc = cutoff(u).coerceIn(170f, 3500f)
        lpL.lowpass(fc, 0.62f)
        lpR.lowpass((fc * 1.05f).coerceAtMost(3500f), 0.62f)

        val s = shape(u)
        // `base` is the floor the wave never drops below — raising swell lowers
        // it, which is what "more dynamics" means here.
        val base = 0.55f - 0.34f * swellS
        // A lowpass passes noise power roughly proportional to its cutoff, so
        // sweeping 190 Hz → 3.3 kHz would swing the level ~12 dB on its own and
        // blow the gentleness budget. Undo most of that (exponent 0.40 rather
        // than the exact 0.50) so the shape envelope owns the dynamics, while
        // ~2.5 dB of natural brightness-follows-loudness coupling survives.
        envGain = (base + (1f - base) * s) * (SPEC_REF / fc).pow(0.40f)

        val brk = breakBump(u) * (0.5f + 0.5f * swellS)
        eqL.peaking(420f, 0.9f, 5f * brk)
        eqR.peaking(445f, 0.9f, 5f * brk)

        fizzRate = foamS * fizzShape(u)
    }

    /** Adds this wave into [out]; each voice gets its own noise for stereo width. */
    fun sample(nL: Float, nR: Float, out: FloatArray) {
        u += uInc
        if (u >= 1f) {
            u -= 1f
            mult = 0.65f + r.nextFloat() * 0.70f // re-randomise every cycle
        }
        out[0] += eqL.process(lpL.process(nL)) * envGain * gainL
        out[1] += eqR.process(lpR.process(nR)) * envGain * gainR
    }

    private companion object {
        const val BUILD_END = 0.30f
        const val BREAK_END = 0.44f
        const val SPEC_REF = 1000f
        val EXP_M3 = exp(-3f)
    }
}

/**
 * **OceanV2** — a gentle shore, rebuilt to fix the two things wrong with the
 * old one: it was too regular, and its crests were bright white noise.
 *
 *  - **Three overlapping wave voices**, each with its own period (the `period`
 *    parameter ± 35 %, re-rolled every cycle) and a staggered starting phase.
 *    A new wave is always building while the previous one is still washing out,
 *    so the shore is never silent between waves — which is the single biggest
 *    tell of a synthetic ocean.
 *  - **A constant sea bed**: a quiet brown + pink blend under a ~820 Hz lowpass
 *    with a slow level wander. It never disappears, so the noise floor doesn't
 *    pump.
 *  - **Granular foam**: 2–6 kHz micro-grains whose spawn density peaks at each
 *    break and decays through the wash. `foam` scales this layer *only*.
 *  - **Gentleness is enforced structurally**, not by a limiter. Three things do
 *    it: crest cutoffs are capped at 3.5 kHz; each wave's envelope floors at
 *    0.38 of its crest (at default swell) rather than at zero; and the filter
 *    sweep is level-compensated, since a lowpass opening from 190 Hz to 3.3 kHz
 *    would otherwise swing the level ~12 dB on its own. Because the three
 *    voices and the bed are uncorrelated their *powers* add, which compresses
 *    what is left: measured crest-to-trough is 6.0 dB (2nd–98th percentile of
 *    300 ms windows) and 7.9 dB worst-case at defaults, opening to 7.9/9.5 dB
 *    at `swell` = 1.
 *
 * Params: `swell` 0..1 (0.5), `period` 8..22 s (14), `foam` 0..1 (0.4).
 */
class OceanV2 : SoundGenerator {

    @Volatile private var swell = 0.5f
    @Volatile private var periodParam = 14f
    @Volatile private var foam = 0.4f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "swell" -> swell = value
            "period" -> periodParam = value
            "foam" -> foam = value
        }
    }

    private var swellS = 0.5f
    private var periodS = 14f
    private var foamS = 0.4f

    private val rnd = Random(6180)

    // Staggered phases: a third of a cycle apart, plus slight pan offsets.
    private val waves = arrayOf(
        Wave(101, 0.00f, 0.34f),
        Wave(211, 0.36f, 0.50f),
        Wave(307, 0.69f, 0.66f),
    )
    private val waveAcc = FloatArray(2)

    // Sea bed.
    private val bedBrL = BrownFilter()
    private val bedBrR = BrownFilter()
    private val bedPkL = PinkFilter()
    private val bedPkR = PinkFilter()
    private val bedLpL = Biquad().lowpass(820f, 0.7f)
    private val bedLpR = Biquad().lowpass(860f, 0.7f)
    private val bedLevel = Wander(71, 0.80f, 1.15f, 9f, 20f, 8f)

    // Foam fizz: 2–6 kHz grains (geometric centre 3.46 kHz, Q = fc / BW).
    private val foamGrains = GrainPool(40)
    private val foamBpL = Biquad().bandpass(3460f, 0.87f)
    private val foamBpR = Biquad().bandpass(3560f, 0.87f)
    private val foamEnv = FloatArray(2)

    private var blockCountdown = 0
    private var bedGain = 0f
    private var foamProbScale = 0f
    private var foamGain = 0f
    private var waveGain = 0f

    private fun updateBlock() {
        swellS += (swell - swellS) * 0.05f
        periodS += (periodParam - periodS) * 0.05f
        foamS += (foam - foamS) * 0.05f
        bedLevel.step(CTRL_BLOCK)

        val p = periodS.coerceIn(8f, 22f)
        for (w in waves) w.block(swellS.coerceIn(0f, 1f), foamS.coerceIn(0f, 1f), p)

        bedGain = BED_GAIN * bedLevel.value
        waveGain = WAVE_GAIN * (0.80f + 0.30f * swellS)
        foamProbScale = MAX_FIZZ_PER_SEC / SAMPLE_RATE
        foamGain = FOAM_GAIN
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = CTRL_BLOCK
                updateBlock()
            }

            waveAcc[0] = 0f
            waveAcc[1] = 0f
            for (w in waves) {
                w.sample(rnd.bip(), rnd.bip(), waveAcc)
                if (rnd.nextFloat() < w.fizzRate * foamProbScale) {
                    val pan = rnd.nextFloat() * HALF_PI_F
                    foamGrains.spawn(
                        0.45f + rnd.nextFloat() * 0.55f,
                        (0.0006f + rnd.nextFloat() * 0.0019f) * SAMPLE_RATE,
                        cos(pan),
                        sin(pan),
                    )
                }
            }

            val bl = bedLpL.process(
                bedBrL.next(rnd.bip()) * 0.75f + bedPkL.next(rnd.bip()) * 0.45f
            ) * bedGain
            val br = bedLpR.process(
                bedBrR.next(rnd.bip()) * 0.75f + bedPkR.next(rnd.bip()) * 0.45f
            ) * bedGain

            foamGrains.envelope(foamEnv)
            val fl = foamBpL.process(rnd.bip()) * foamEnv[0] * foamGain
            val fr = foamBpR.process(rnd.bip()) * foamEnv[1] * foamGain

            // Makeup gain so the ocean sits level with rain in a mix. This is
            // already included in the calibration: defaults measure RMS 0.102,
            // peak 0.66, -22.8 dBA. Do not add a second makeup stage.
            left[i] = (waveAcc[0] * waveGain + bl + fl) * 1.7f
            right[i] = (waveAcc[1] * waveGain + br + fr) * 1.7f
        }
    }

    private companion object {
        const val WAVE_GAIN = 0.495f
        const val BED_GAIN = 0.215f
        const val FOAM_GAIN = 0.109f
        const val MAX_FIZZ_PER_SEC = 420f
    }
}

// ---------------------------------------------------------------------------
// Water drops
// ---------------------------------------------------------------------------

/**
 * **WaterDropsV2** — drips in a place, not drips in silence. A bare sine in an
 * anechoic void is the fastest way to sound synthetic, so most of this class is
 * the room rather than the events.
 *
 *  - **Two drop types, randomly mixed.** A *stone plink* is a 1.5 ms bandpassed
 *    noise click plus two partials (fundamental and a stretched ~2.3–3.2×
 *    overtone) decaying over 180–450 ms with a slight upward glide — the tiny
 *    rising tail a hard surface gives. A *pool bloop* is a Minnaert bubble:
 *    a single lower partial whose pitch rises exponentially by 1.35–2.1× across
 *    a short 70–170 ms decay, because a bubble's resonant frequency climbs as
 *    it shrinks. Pitch, level, decay and pan are randomised per drop.
 *  - **Space**: a three-line feedback delay network at 63 / 97 / 151 ms with a
 *    2.5 kHz lowpass inside every loop and cross-fed L/R taps. The feedback
 *    matrix has row sums equal to the feedback gain, which is capped at 0.65,
 *    so the network is unconditionally stable. `space` is the wet level.
 *    All delay buffers are allocated in the constructor.
 *  - **Trickle bed**: a continuous stream of 0.4–1.5 ms micro-grains through a
 *    1–3 kHz band, plus a very quiet high-Q band wandering between 300 and
 *    500 Hz. That resonant band is the whole "cave" impression — a room tone
 *    the drips sit inside instead of on top of.
 *  - **Double-drips**: about a third of drops schedule a quieter echo of
 *    themselves 90–200 ms later, the signature rhythm of a real leak.
 *
 * Params: `rate` 0..1 (0.4), `tone` 0..1 (0.5), `space` 0..1 (0.6),
 * `trickle` 0..1 (0.35). At defaults this measures RMS 0.065, peak 0.56,
 * -23.0 dBA. It is deliberately the quietest of the three in RMS terms: it is
 * an element meant to be layered, and a sparse event stream cannot reach the
 * RMS of a continuous bed without its individual drips becoming peaky.
 */
class WaterDropsV2 : SoundGenerator {

    @Volatile private var rate = 0.4f
    @Volatile private var tone = 0.5f
    @Volatile private var space = 0.6f
    @Volatile private var trickle = 0.35f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value
            "tone" -> tone = value
            "space" -> space = value
            "trickle" -> trickle = value
        }
    }

    private var rateS = 0.4f
    private var toneS = 0.5f
    private var spaceS = 0.6f
    private var trickleS = 0.35f

    private val rnd = Random(4242)

    // Drop voices.
    private val drops = TonalPool(12)
    private val dropAcc = FloatArray(2)
    private val clickBp = Biquad().bandpass(2600f, 0.8f)
    private var dropCountdown = SAMPLE_RATE

    // Scheduled second halves of double-drips.
    private val pendLive = BooleanArray(4)
    private val pendWait = IntArray(4)
    private val pendFreq = FloatArray(4)
    private val pendAmp = FloatArray(4)
    private val pendPan = FloatArray(4)
    private val pendType = IntArray(4)

    // Echo network — buffers sized and allocated once, here.
    private val dLen = intArrayOf(2777, 4283, 6661) // ≈ 63 / 97 / 151 ms, coprime
    private val dBuf = Array(3) { FloatArray(dLen[it]) }
    private val dIdx = IntArray(3)
    private val dLp = Array(3) { OnePoleLp().setCutoff(2500f) }

    // Trickle bed.
    private val trickleGrains = GrainPool(32)
    private val trickleEnv = FloatArray(2)
    private val trickleBpL = Biquad().bandpass(1730f, 0.87f)
    private val trickleBpR = Biquad().bandpass(1810f, 0.87f)
    private val hollowL = Biquad().bandpass(380f, 4.5f)
    private val hollowR = Biquad().bandpass(395f, 4.5f)
    private val hollowTone = Wander(83, 300f, 500f, 7f, 18f, 6f)
    private val trickleLevel = Wander(97, 0.78f, 1.18f, 5f, 14f, 4f)

    private var blockCountdown = 0
    private var dropPeriod = 1.3f
    private var trickleProb = 0f
    private var trickleGain = 0f
    private var hollowGain = 0f
    private var wetGain = 0f
    private var dryGain = 1f
    private var feedback = 0.55f

    private fun updateBlock() {
        rateS += (rate - rateS) * 0.06f
        toneS += (tone - toneS) * 0.06f
        spaceS += (space - spaceS) * 0.06f
        trickleS += (trickle - trickleS) * 0.06f

        hollowTone.step(CTRL_BLOCK)
        trickleLevel.step(CTRL_BLOCK)

        val perSec = 0.15f + 3.0f * rateS.coerceIn(0f, 1f).pow(1.7f)
        dropPeriod = 1f / perSec

        // Grain density rises linearly with `trickle` while the bed gain rises
        // as its 0.6 power, so the slider reads as level rather than flooding
        // near the top. Both reach exactly zero at trickle = 0.
        val tr = trickleS.coerceIn(0f, 1f)
        val trAmp = tr.pow(0.60f)
        trickleProb = tr * MAX_TRICKLE_PER_SEC / SAMPLE_RATE
        trickleGain = TRICKLE_GAIN * trAmp * trickleLevel.value
        hollowGain = HOLLOW_GAIN * trAmp * trickleLevel.value
        hollowL.bandpass(hollowTone.value, 4.5f)
        hollowR.bandpass(hollowTone.value * 1.04f, 4.5f)

        wetGain = WET_GAIN * spaceS
        dryGain = 1f - 0.35f * spaceS
        feedback = 0.45f + 0.20f * spaceS // ≤ 0.65: row sums of the FDN matrix
    }

    /** Spawns one drop; [second] suppresses scheduling another double-drip. */
    private fun fireDrop(type: Int, freq: Float, amp: Float, pan: Float, second: Boolean) {
        val gl = cos(pan * HALF_PI_F)
        val gr = sin(pan * HALF_PI_F)
        if (type == 0) {
            // Stone plink: click + two partials, slight upward glide.
            val decay = (0.18f + rnd.nextFloat() * 0.27f) * SAMPLE_RATE
            drops.spawn(
                f1 = freq,
                f2 = freq * (2.3f + rnd.nextFloat() * 0.9f),
                amp1 = amp,
                amp2 = amp * (0.25f + rnd.nextFloat() * 0.25f),
                decaySamples = decay,
                glide1 = 1.010f + rnd.nextFloat() * 0.022f,
                glide2 = 1.006f + rnd.nextFloat() * 0.014f,
                clickAmp = amp * (0.5f + rnd.nextFloat() * 0.5f),
                clickDecaySamples = 0.0015f * SAMPLE_RATE,
                attackSamples = (0.0006f * SAMPLE_RATE).toInt(),
                gainL = gl,
                gainR = gr,
            )
        } else {
            // Minnaert bubble: one fuller, lower partial rising steeply as the
            // bubble shrinks; no click, soft 4 ms onset.
            val decay = (0.070f + rnd.nextFloat() * 0.100f) * SAMPLE_RATE
            drops.spawn(
                f1 = freq * 0.62f,
                f2 = freq * 1.26f,
                amp1 = amp * 1.15f,
                amp2 = amp * 0.10f,
                decaySamples = decay,
                glide1 = 1.35f + rnd.nextFloat() * 0.75f,
                glide2 = 1.35f + rnd.nextFloat() * 0.75f,
                clickAmp = 0f,
                clickDecaySamples = 32f,
                attackSamples = (0.004f * SAMPLE_RATE).toInt(),
                gainL = gl,
                gainR = gr,
            )
        }
        if (second) return
        if (rnd.nextFloat() < 0.34f) {
            for (k in 0 until 4) {
                if (pendLive[k]) continue
                pendLive[k] = true
                pendWait[k] = ((0.090f + rnd.nextFloat() * 0.110f) * SAMPLE_RATE).toInt()
                pendFreq[k] = freq * (0.94f + rnd.nextFloat() * 0.16f)
                pendAmp[k] = amp * (0.32f + rnd.nextFloat() * 0.22f)
                pendPan[k] = (pan + rnd.bip() * 0.08f).coerceIn(0f, 1f)
                pendType[k] = type
                break
            }
        }
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = CTRL_BLOCK
                updateBlock()
            }

            // --- schedule drops ----------------------------------------------
            if (--dropCountdown <= 0) {
                // Wide jitter: quasi-periodic like a real leak, never metronomic.
                dropCountdown =
                    (dropPeriod * (0.45f + 1.10f * rnd.nextFloat()) * SAMPLE_RATE).toInt()
                        .coerceAtLeast(1024)
                val type = if (rnd.nextFloat() < 0.45f) 1 else 0
                val base = if (type == 0) 420f + 1150f * toneS else 260f + 560f * toneS
                fireDrop(
                    type = type,
                    freq = base * (0.72f + rnd.nextFloat() * 0.66f),
                    amp = 0.55f + rnd.nextFloat() * 0.45f,
                    pan = rnd.nextFloat(),
                    second = false,
                )
            }
            for (k in 0 until 4) {
                if (!pendLive[k]) continue
                if (--pendWait[k] <= 0) {
                    pendLive[k] = false
                    fireDrop(pendType[k], pendFreq[k], pendAmp[k], pendPan[k], true)
                }
            }

            // --- drop voices --------------------------------------------------
            dropAcc[0] = 0f
            dropAcc[1] = 0f
            drops.add(clickBp.process(rnd.bip()), dropAcc)
            val dryL = dropAcc[0] * DROP_GAIN
            val dryR = dropAcc[1] * DROP_GAIN

            // --- echo network -------------------------------------------------
            val o0 = dBuf[0][dIdx[0]]
            val o1 = dBuf[1][dIdx[1]]
            val o2 = dBuf[2][dIdx[2]]
            val mid = (dryL + dryR) * 0.5f
            // Each row of the feedback matrix sums to `feedback` (≤ 0.65) and the
            // in-loop lowpass only ever reduces gain, so this cannot run away.
            dBuf[0][dIdx[0]] = dLp[0].process(dryL + feedback * (0.5f * o0 + 0.5f * o2))
            dBuf[1][dIdx[1]] = dLp[1].process(dryR + feedback * (0.5f * o1 + 0.5f * o0))
            dBuf[2][dIdx[2]] = dLp[2].process(mid + feedback * (0.5f * o2 + 0.5f * o1))
            for (k in 0 until 3) {
                val n = dIdx[k] + 1
                dIdx[k] = if (n >= dLen[k]) 0 else n
            }
            val wetL = 0.62f * o0 + 0.42f * o2
            val wetR = 0.62f * o1 + 0.42f * o0

            // --- trickle bed ---------------------------------------------------
            if (rnd.nextFloat() < trickleProb) {
                val pan = rnd.nextFloat() * HALF_PI_F
                trickleGrains.spawn(
                    0.45f + rnd.nextFloat() * 0.55f,
                    (0.0004f + rnd.nextFloat() * 0.0011f) * SAMPLE_RATE,
                    cos(pan),
                    sin(pan),
                )
            }
            trickleGrains.envelope(trickleEnv)
            // The grains *modulate* a continuous band rather than gating it.
            // Pure gating multiplies noise by a Poisson sum, whose product has a
            // very heavy tail — that is what turns a quiet bed into occasional
            // spikes. A floor of 0.5 keeps the crest factor near Gaussian.
            // ...and the modulator is capped, because a rare pile-up of grains
            // should not be audibly louder than a busy stream — without the cap
            // the Poisson tail shows up as isolated spikes.
            val tmL = (0.75f + 0.50f * trickleEnv[0]).coerceAtMost(1.55f)
            val tmR = (0.75f + 0.50f * trickleEnv[1]).coerceAtMost(1.55f)
            val tl = trickleBpL.process(rnd.bip()) * tmL * trickleGain +
                hollowL.process(rnd.bip()) * hollowGain
            val tr = trickleBpR.process(rnd.bip()) * tmR * trickleGain +
                hollowR.process(rnd.bip()) * hollowGain

            // Makeup gain: sparse plinks need headroom to read at mix level.
            // Already included in the calibration: defaults measure RMS 0.065,
            // peak 0.56, -23.0 dBA. Do not add a second makeup stage.
            left[i] = (dryL * dryGain + wetL * wetGain + tl) * 1.9f
            right[i] = (dryR * dryGain + wetR * wetGain + tr) * 1.9f
        }
    }

    private companion object {
        const val DROP_GAIN = 0.22f
        const val WET_GAIN = 0.60f
        const val TRICKLE_GAIN = 0.37f
        const val HOLLOW_GAIN = 0.095f
        const val MAX_TRICKLE_PER_SEC = 1400f
    }
}
