package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Nature.kt — a ground-up rework of the campfire, wind and forest-night scenes.
 *
 * The three generators here replace beds that each failed in the same way: one
 * mechanism, running at a constant rate, is not a soundscape. A single sweeping
 * bandpass is a filter sweep, not wind; uniform Poisson crackles over a static
 * rumble is a noise generator, not a fire; constant pink noise plus three
 * identical crickets is a test tone, not a forest.
 *
 * The fix in all three cases is the same shape of idea, borrowed from Water.kt:
 * build the scene out of several *simultaneous* mechanisms at different
 * distances, drive them from a small number of shared slow modulators so they
 * breathe together, and make every event stream *clustered* rather than
 * uniform. Burst statistics — flurries, gusts, phrases, long silences — are
 * what the ear uses to decide whether something is alive.
 *
 * Threading follows the house convention: `setParam` runs on the UI thread and
 * only writes `@Volatile` floats; the render thread reads them once per control
 * block and glides smoothed copies toward them. Filters are retuned on control
 * ticks (128 samples), never per sample. Nothing allocates after construction.
 *
 * File-private helpers are all `Nat*`/`NT_*`/`nbi`-prefixed so they cannot
 * collide with the same ideas in Water.kt, Elements.kt or Elements2.kt.
 */

/** Control-rate period in samples. Filters and gains are updated this often. */
private const val NT_BLOCK = 128

/** Soft ceiling used as a safety net; transparent well below it. */
private const val NAT_CEIL = 0.98f
private const val NAT_CEIL_INV = 1.0204082f

private val NT_TWO_PI = 2.0 * PI
private val NT_HALF_PI = (PI / 2.0).toFloat()

/**
 * Raised-cosine window sampled at 257 points. Attack ramps index this instead
 * of calling cos() per sample.
 */
private val NT_HANN = FloatArray(257) { 0.5f - 0.5f * cos(2.0 * PI * it / 256.0).toFloat() }

private fun Random.nbi(): Float = nextFloat() * 2f - 1f

/**
 * Odd, smooth soft ceiling (Padé approximation of `C * tanh(x / C)`).
 * Transparent below about a third of [NAT_CEIL], hard-bounded to ±[NAT_CEIL].
 * It exists only so a rare alignment of independent layers — a big pop landing
 * on a gust peak — cannot spike the mix.
 */
private fun natCeilClip(x: Float): Float {
    val u = (x * NAT_CEIL_INV).coerceIn(-3f, 3f)
    val u2 = u * u
    return NAT_CEIL * u * (27f + u2) / (27f + 9f * u2)
}

/**
 * A band-limited random walk: every [minSec]..[maxSec] it picks a new target
 * inside [lo]..[hi] and glides toward it with time constant [tauSec]. Because
 * it re-targets before it arrives, the output is smooth and never periodic.
 * Every continuous layer in this file has at least one of these on its level.
 */
private class NatWander(
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
 * Pool of short noise bursts, each owning its own bandpass, stereo position,
 * raised-cosine attack and exponential decay. This is the workhorse for every
 * discrete "impact" in the file: fire snaps, crackles and pops, twig breaks and
 * animal rustles. Giving each event its own filter is what lets one population
 * of bursts cover 1 ms bright ticks and 80 ms low pops without sounding like
 * the same sample at two pitches.
 */
private class NatBurstPool(private val capacity: Int) {
    private val live = BooleanArray(capacity)
    private val env = FloatArray(capacity)
    private val dec = FloatArray(capacity)
    private val atkPos = IntArray(capacity)
    private val atkLen = IntArray(capacity)
    private val gL = FloatArray(capacity)
    private val gR = FloatArray(capacity)
    private val bp = Array(capacity) { Biquad() }
    private var cursor = 0

    fun spawn(
        amp: Float,
        decaySamples: Float,
        attackSamples: Int,
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
        if (slot < 0) return // saturated: drop rather than steal a ringing voice
        cursor = if (slot + 1 >= capacity) 0 else slot + 1
        live[slot] = true
        env[slot] = amp
        dec[slot] = exp(-6.9f / decaySamples.coerceAtLeast(8f))
        atkPos[slot] = 0
        atkLen[slot] = attackSamples.coerceAtLeast(1)
        gL[slot] = gainL
        gR[slot] = gainR
        bp[slot].bandpass(centerHz.coerceIn(30f, 15000f), q.coerceIn(0.3f, 12f))
    }

    /**
     * Adds every live burst into [out] — does not clear it first.
     *
     * The envelope scales the filter's *input*, not its output. That matters:
     * these filters are pooled and retuned on every spawn, and a resonator left
     * holding state from a 5 kHz snap that is suddenly retuned to 250 Hz rings
     * its new mode at an amplitude of roughly `state / sin(w0)` — a factor of
     * 50+ down there. Driving the input means the state decays away with the
     * envelope, so every slot is quiet before it is reused. It is also the more
     * physical model: an impulsive excitation exciting a resonance.
     */
    fun add(noise: Float, out: FloatArray) {
        var l = 0f
        var r = 0f
        for (i in 0 until capacity) {
            if (!live[i]) continue
            val e = env[i]
            if (e < 0.00015f) {
                live[i] = false
                continue
            }
            var a = e
            val al = atkLen[i]
            if (atkPos[i] < al) {
                a *= NT_HANN[128 * atkPos[i] / al]
                atkPos[i]++
            }
            val s = bp[i].process(noise * a)
            l += s * gL[i]
            r += s * gR[i]
            env[i] = e * dec[i]
        }
        out[0] += l
        out[1] += r
    }
}

/**
 * Pool of low decaying sines with a downward glide — the body thump under a
 * fire pop, and the soft whump of a log settling. A pop without one of these
 * sounds like a click on a table; with one it sounds like something with mass
 * shifted inside the fire.
 */
private class NatThumpPool(private val capacity: Int) {
    private val live = BooleanArray(capacity)
    private val ph = DoubleArray(capacity)
    private val inc = DoubleArray(capacity)
    private val gl = DoubleArray(capacity)
    private val env = FloatArray(capacity)
    private val dec = FloatArray(capacity)
    private val atkPos = IntArray(capacity)
    private val atkLen = IntArray(capacity)
    private val gL = FloatArray(capacity)
    private val gR = FloatArray(capacity)
    private var cursor = 0

    fun spawn(
        freq: Float,
        amp: Float,
        decaySamples: Float,
        glideTotal: Float,
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
        val nd = decaySamples.coerceAtLeast(64f)
        live[slot] = true
        ph[slot] = 0.0
        inc[slot] = NT_TWO_PI * freq / SAMPLE_RATE
        gl[slot] = exp(ln(glideTotal.coerceIn(0.2f, 2f).toDouble()) / nd)
        env[slot] = amp
        dec[slot] = exp(-6.9f / nd)
        atkPos[slot] = 0
        atkLen[slot] = attackSamples.coerceAtLeast(4)
        gL[slot] = gainL
        gR[slot] = gainR
    }

    /** Adds every live thump into [out] — does not clear it first. */
    fun add(out: FloatArray) {
        var l = 0f
        var r = 0f
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
                a *= NT_HANN[128 * atkPos[i] / al]
                atkPos[i]++
            }
            ph[i] += inc[i]
            inc[i] *= gl[i]
            if (ph[i] > NT_TWO_PI) ph[i] -= NT_TWO_PI
            val s = sin(ph[i]).toFloat() * a
            l += s * gL[i]
            r += s * gR[i]
            env[i] = e * dec[i]
        }
        out[0] += l
        out[1] += r
    }
}

// ---------------------------------------------------------------------------
// Campfire
// ---------------------------------------------------------------------------

/**
 * **CampfireV2** — a fire is a breathing body of flame with a *clustered*
 * population of impacts on top, not a static rumble with metronomic clicks.
 *
 * The old [Campfire] failed on two counts: its bed never changed, and its
 * crackles arrived as a uniform Poisson process at one fixed rate with one
 * fixed timbre. Uniform Poisson is audibly wrong — real fires go quiet for
 * seconds and then throw a dozen snaps in half a second. That burst statistic
 * is the single largest realism win here.
 *
 *  - **Flame body.** A brown-noise rumble under ~110 Hz plus a pink-noise roar
 *    through a lowpass whose cutoff wanders 380–700 Hz. The roar's level is
 *    amplitude-modulated by *flame flicker*: a 3–12 Hz oscillation whose rate
 *    and depth are themselves on random walks, mixed with a second oscillator
 *    at 0.41× that rate so the flicker never reads as a tremolo. Over the top
 *    sits a 10–30 s "how alive is the fire" wander. Left and right take
 *    independent noise and a 0.6 rad flicker offset, so the body is wide but
 *    coherent.
 *  - **Crackle taxonomy — three populations.** *Tiny snaps* (1–4 ms, 2.5–6 kHz,
 *    Q 1.2–3.4) are the frequent ones and carry the "fine detail" impression.
 *    *Mid crackles* (5–20 ms, 900–3500 Hz) vary in both Q (0.8–4.2) and centre,
 *    so no two sound alike. *Big pops* (30–80 ms, 200–700 Hz) are rare, loud,
 *    and always accompanied by a low decaying thump an octave and a half below
 *    — the mass of the log moving.
 *  - **Clustering.** The spawn rate is multiplied by its own 2–8 s random walk
 *    spanning 0.30–2.10×, so the fire has busy and lazy stretches. On top of
 *    that, every 20–60 s a **log settle** fires: a soft 36–66 Hz whump plus a
 *    low noise thud, followed by a 0.3–1.0 s flurry of snaps at 3–5× the base
 *    rate (pops suppressed during a flurry — a flurry is fine debris, not logs).
 *  - **Sizzle.** Every 15–45 s, a 0.2–0.6 s train of very quiet tiny snaps at
 *    *one* fixed stereo position: resin boiling out of one spot in one log.
 *  - **Ember shimmer.** A barely-there 5–9 kHz band whose level wanders over
 *    10–25 s, scaled by `size`.
 *
 * `size` scales rumble depth, roar level and crackle rate (embers → big fire);
 * `crackle` scales the impact populations against the body. Every crackle is
 * individually panned.
 *
 * Params: `size` 0..1 (0.5), `crackle` 0..1 (0.5).
 *
 * Measured at defaults over 5 min: RMS 0.084, peak 0.49, 300 ms window level
 * moving 5.6 dB (p2–p98); over 2 h, RMS 0.080 / peak 0.58. Crackle traffic at
 * defaults is 21 events/s — 13 snaps, 7 mid crackles and 0.43 pops — against
 * 6.6/s at `size` = 0 and 36/s at `size` = 1. The old class measured RMS 0.086
 * with a 1.2 dB window range: identical loudness, no life.
 */
class CampfireV2 : SoundGenerator {

    @Volatile private var size = 0.5f
    @Volatile private var crackle = 0.5f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "size" -> size = value.coerceIn(0f, 1f)
            "crackle" -> crackle = value.coerceIn(0f, 1f)
        }
    }

    private var sizeS = 0.5f
    private var crackS = 0.5f

    private val rnd = Random(20604)

    // --- flame body ---------------------------------------------------------
    private val rumbleBrL = BrownFilter()
    private val rumbleBrR = BrownFilter()
    private val rumbleLpL = Biquad().lowpass(110f, 0.7f)
    private val rumbleLpR = Biquad().lowpass(118f, 0.7f)
    private val roarPkL = PinkFilter()
    private val roarPkR = PinkFilter()
    private val roarLpL = Biquad().lowpass(520f, 0.72f)
    private val roarLpR = Biquad().lowpass(548f, 0.72f)
    private val roarHpL = Biquad().highpass(105f, 0.7f)
    private val roarHpR = Biquad().highpass(105f, 0.7f)

    private val aliveness = NatWander(101, 0.58f, 1.22f, 10f, 30f, 8f)
    private val flickRate = NatWander(103, 3f, 12f, 1.5f, 5f, 1.6f)
    private val flickDepth = NatWander(107, 0.18f, 0.55f, 3f, 9f, 2.5f)
    private val roarTone = NatWander(109, 380f, 700f, 7f, 18f, 5f)
    private val shimLevel = NatWander(113, 0.35f, 1.15f, 10f, 25f, 7f)

    private var flickPhase = 0.0
    private var flickPhase2 = 0.0
    private var flickInc = 0.0
    private var flickInc2 = 0.0

    // --- ember shimmer ------------------------------------------------------
    private val shimL = Biquad().bandpass(6700f, 0.55f)
    private val shimR = Biquad().bandpass(6900f, 0.55f)

    // --- impacts ------------------------------------------------------------
    private val cracks = NatBurstPool(30)
    private val thumps = NatThumpPool(6)
    private val acc = FloatArray(2)

    private val crackRateMod = NatWander(127, 0.30f, 2.10f, 2f, 8f, 1.4f)

    private var settleCountdown = (14f * SAMPLE_RATE).toInt()
    private var flurryLeft = 0
    private var flurryMult = 1f

    private var sizzleCountdown = (9f * SAMPLE_RATE).toInt()
    private var sizzleLeft = 0
    private var sizzleProb = 0f
    private var sizzlePanL = 0.7f
    private var sizzlePanR = 0.7f

    // --- block-rate derived -------------------------------------------------
    private var blockCountdown = 0
    private var rumbleGain = 0f
    private var roarGain = 0f
    private var shimGain = 0f
    private var bodyLevel = 1f
    private var flickD = 0.35f
    private var crackProb = 0f
    private var crackAmp = 1f
    private var popAmp = 1f

    private fun updateBlock() {
        sizeS += (size - sizeS) * 0.05f
        crackS += (crackle - crackS) * 0.05f

        aliveness.step(NT_BLOCK)
        flickRate.step(NT_BLOCK)
        flickDepth.step(NT_BLOCK)
        roarTone.step(NT_BLOCK)
        shimLevel.step(NT_BLOCK)
        crackRateMod.step(NT_BLOCK)

        flickInc = NT_TWO_PI * flickRate.value / SAMPLE_RATE
        flickInc2 = flickInc * 0.41
        flickD = flickDepth.value
        bodyLevel = aliveness.value

        val tone = roarTone.value * (0.82f + 0.36f * sizeS)
        roarLpL.lowpass(tone, 0.72f)
        roarLpR.lowpass(tone * 1.05f, 0.72f)

        // Embers glow low and quiet; a big fire has a real body under it.
        rumbleGain = RUMBLE_GAIN * (0.30f + 1.05f * sizeS)
        roarGain = ROAR_GAIN * (0.34f + 0.95f * sizeS)
        shimGain = SHIM_GAIN * shimLevel.value * (0.25f + 0.85f * sizeS)

        val rate = (5f + 23f * sizeS) * crackRateMod.value
        crackProb = rate / SAMPLE_RATE
        crackAmp = CRACK_GAIN * (0.50f + 0.85f * crackS)
        popAmp = POP_GAIN * (0.50f + 0.85f * crackS)
    }

    /** One crackle. [snapBias] biases the draw toward tiny snaps (flurries). */
    private fun spawnCrackle(snapBias: Boolean) {
        val pan = rnd.nextFloat() * NT_HALF_PI
        val gl = cos(pan)
        val gr = sin(pan)
        val u = rnd.nextFloat()
        val pSnap = if (snapBias) 0.88f else 0.62f
        val pMid = if (snapBias) 1f else 0.978f
        when {
            u < pSnap -> cracks.spawn(
                amp = crackAmp * (0.35f + rnd.nextFloat() * 0.55f),
                decaySamples = (0.0010f + rnd.nextFloat() * 0.0030f) * SAMPLE_RATE,
                attackSamples = 3,
                centerHz = 2500f + rnd.nextFloat() * 3500f,
                q = 1.2f + rnd.nextFloat() * 2.2f,
                gainL = gl,
                gainR = gr,
            )
            u < pMid -> cracks.spawn(
                amp = crackAmp * (0.55f + rnd.nextFloat() * 0.90f),
                decaySamples = (0.005f + rnd.nextFloat() * 0.015f) * SAMPLE_RATE,
                attackSamples = 5,
                centerHz = 900f + rnd.nextFloat() * 2600f,
                q = 0.8f + rnd.nextFloat() * 3.4f,
                gainL = gl,
                gainR = gr,
            )
            else -> {
                val f = 200f + rnd.nextFloat() * 500f
                cracks.spawn(
                    amp = popAmp * (0.80f + rnd.nextFloat() * 0.45f),
                    decaySamples = (0.030f + rnd.nextFloat() * 0.050f) * SAMPLE_RATE,
                    attackSamples = 4,
                    centerHz = f,
                    q = 0.9f + rnd.nextFloat() * 0.9f,
                    gainL = gl,
                    gainR = gr,
                )
                thumps.spawn(
                    freq = 34f + f * 0.09f,
                    amp = popAmp * (0.10f + rnd.nextFloat() * 0.10f),
                    decaySamples = (0.06f + rnd.nextFloat() * 0.11f) * SAMPLE_RATE,
                    glideTotal = 0.80f,
                    attackSamples = (0.004f * SAMPLE_RATE).toInt(),
                    gainL = gl,
                    gainR = gr,
                )
            }
        }
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = NT_BLOCK
                updateBlock()
            }

            // --- flame flicker ------------------------------------------------
            flickPhase += flickInc
            flickPhase2 += flickInc2
            if (flickPhase > NT_TWO_PI) flickPhase -= NT_TWO_PI
            if (flickPhase2 > NT_TWO_PI) flickPhase2 -= NT_TWO_PI
            val mL = 0.62f * sin(flickPhase).toFloat() + 0.38f * sin(flickPhase2).toFloat()
            val mR = 0.62f * sin(flickPhase + 0.6).toFloat() +
                0.38f * sin(flickPhase2 + 0.95).toFloat()
            val roarAmL = bodyLevel * (1f - flickD + flickD * (0.5f + 0.5f * mL))
            val roarAmR = bodyLevel * (1f - flickD + flickD * (0.5f + 0.5f * mR))
            // The deep rumble carries only half the flicker: mass responds slower.
            val rumbAmL = bodyLevel * (1f - 0.5f * flickD + 0.5f * flickD * (0.5f + 0.5f * mL))
            val rumbAmR = bodyLevel * (1f - 0.5f * flickD + 0.5f * flickD * (0.5f + 0.5f * mR))

            // --- log settle ---------------------------------------------------
            if (--settleCountdown <= 0) {
                settleCountdown = ((20f + rnd.nextFloat() * 40f) * SAMPLE_RATE).toInt()
                val p = rnd.nextFloat() * NT_HALF_PI
                val sl = cos(p)
                val sr = sin(p)
                thumps.spawn(
                    freq = 36f + rnd.nextFloat() * 30f,
                    amp = SETTLE_GAIN * (0.55f + rnd.nextFloat() * 0.45f) * (0.4f + 0.9f * sizeS),
                    decaySamples = (0.22f + rnd.nextFloat() * 0.30f) * SAMPLE_RATE,
                    glideTotal = 0.72f,
                    attackSamples = (0.008f * SAMPLE_RATE).toInt(),
                    gainL = sl,
                    gainR = sr,
                )
                cracks.spawn(
                    amp = popAmp * 0.55f,
                    decaySamples = 0.10f * SAMPLE_RATE,
                    attackSamples = (0.006f * SAMPLE_RATE).toInt(),
                    centerHz = 120f + rnd.nextFloat() * 90f,
                    q = 0.8f,
                    gainL = sl,
                    gainR = sr,
                )
                flurryLeft = ((0.30f + rnd.nextFloat() * 0.70f) * SAMPLE_RATE).toInt()
                flurryMult = 3f + rnd.nextFloat() * 2f
            }
            var rateMul = 1f
            if (flurryLeft > 0) {
                flurryLeft--
                rateMul = flurryMult
            }

            // --- crackles -----------------------------------------------------
            if (rnd.nextFloat() < crackProb * rateMul) spawnCrackle(rateMul > 1f)

            // --- resin sizzle -------------------------------------------------
            if (sizzleLeft > 0) {
                sizzleLeft--
                if (rnd.nextFloat() < sizzleProb) {
                    cracks.spawn(
                        amp = crackAmp * (0.10f + rnd.nextFloat() * 0.16f),
                        decaySamples = (0.0008f + rnd.nextFloat() * 0.0016f) * SAMPLE_RATE,
                        attackSamples = 2,
                        centerHz = 3200f + rnd.nextFloat() * 3600f,
                        q = 2.0f + rnd.nextFloat() * 3.0f,
                        gainL = sizzlePanL,
                        gainR = sizzlePanR,
                    )
                }
            } else if (--sizzleCountdown <= 0) {
                sizzleCountdown = ((15f + rnd.nextFloat() * 30f) * SAMPLE_RATE).toInt()
                sizzleLeft = ((0.20f + rnd.nextFloat() * 0.40f) * SAMPLE_RATE).toInt()
                val p = rnd.nextFloat() * NT_HALF_PI
                sizzlePanL = cos(p)
                sizzlePanR = sin(p)
                sizzleProb = (70f + rnd.nextFloat() * 90f) / SAMPLE_RATE
            }

            // --- body ---------------------------------------------------------
            val rl = rumbleLpL.process(rumbleBrL.next(rnd.nbi())) * rumbleGain * rumbAmL
            val rr = rumbleLpR.process(rumbleBrR.next(rnd.nbi())) * rumbleGain * rumbAmR
            val ol = roarHpL.process(roarLpL.process(roarPkL.next(rnd.nbi()))) * roarGain * roarAmL
            val orr = roarHpR.process(roarLpR.process(roarPkR.next(rnd.nbi()))) * roarGain * roarAmR
            val sl2 = shimL.process(rnd.nbi()) * shimGain
            val sr2 = shimR.process(rnd.nbi()) * shimGain

            // --- impacts --------------------------------------------------------
            acc[0] = 0f
            acc[1] = 0f
            cracks.add(rnd.nbi(), acc)
            thumps.add(acc)

            left[i] = natCeilClip(rl + ol + sl2 + acc[0])
            right[i] = natCeilClip(rr + orr + sr2 + acc[1])
        }
    }

    private companion object {
        const val RUMBLE_GAIN = 0.70f
        const val ROAR_GAIN = 1.07f
        const val SHIM_GAIN = 0.055f
        const val CRACK_GAIN = 0.63f
        const val POP_GAIN = 0.45f
        const val SETTLE_GAIN = 0.26f
    }
}

// ---------------------------------------------------------------------------
// Wind
// ---------------------------------------------------------------------------

/**
 * **WindV2** — wind is broadband turbulence heard through several mechanisms at
 * once, all driven by one gust field.
 *
 * The old [Wind] was a single narrow bandpass sweeping its centre frequency,
 * which is a filter sweep: the ear hears one moving resonance, not moving air.
 * Real wind has no single centre. What it does have is *coherence* — the
 * pressure thump, the mid-frequency rush and the leaf hiss all rise and fall
 * together, but with different lags and different sensitivities. Reproducing
 * that relationship, rather than any one layer's spectrum, is what makes this
 * read as wind.
 *
 *  - **Gust engine.** Three random walks at 30–60 s (weather), 3–10 s (gusts,
 *    the main one) and 0.5–2 s (flutter), each with a depth scaled by
 *    `gustiness`, combined multiplicatively and normalised by the combination's
 *    expected mean so that raising `gustiness` adds dynamics without changing
 *    average loudness. The result is one shared envelope, `gN`, mean ≈ 1.
 *  - **Low buffet.** Brown noise under a 70–110 Hz lowpass, following `gN`
 *    through a 0.4 s one-pole *lag*. Large air masses have inertia; the thump
 *    you feel arrives slightly after the rush you hear.
 *  - **Mid whoosh.** Three *parallel* low-Q bandpasses (Q 0.88–1.15) whose
 *    centres wander independently in 180–450, 400–900 and 800–1600 Hz, each
 *    with its own 6–16 s level wander, each following `gN` directly. Left and
 *    right take independent noise and centres detuned in alternating directions
 *    (±3–4 %), which decorrelates the channels without smearing the image.
 *  - **Foliage hiss.** A 1.9–7.8 kHz band following a *fast* (60 ms) version of
 *    the gust with an exaggerated exponent — leaves rustle hardest right at the
 *    gust peak and go almost silent between. `foliage` = 0 is open moor and the
 *    layer is genuinely off; `foliage` = 1 is a leafy tree overhead.
 *  - **Whistle.** A quiet Q≈11 resonance wandering 1.5–3 kHz that only fades in
 *    above `gN` ≈ 1.45, squared so it appears suddenly and briefly, and gated
 *    further by its own 20–50 s wander so it is absent for long stretches. It
 *    is the edge tone of a wire or a gap, and it must never become a flute.
 *  - **`strength`** raises the overall level *and* shifts every centre
 *    frequency and the band-3/foliage weighting upward: harder wind is brighter,
 *    because higher air speed pushes the turbulent spectrum up.
 *
 * Params: `strength` 0..1 (0.5), `gustiness` 0..1 (0.5), `foliage` 0..1 (0.4).
 *
 * Measured at defaults over 5 min: RMS 0.082, peak 0.52, 300 ms window level
 * moving 6.9 dB (p2–p98); over 2 h, RMS 0.079 / peak 0.57. Mean gust period is
 * 5.3 s at `gustiness` = 0, 7.4 s at 0.5 and 12.5 s at 1 (the slow layer takes
 * over as depth rises), with the window range opening from 2.8 dB to 13.5 dB
 * across the same span.
 */
class WindV2 : SoundGenerator {

    @Volatile private var strength = 0.5f
    @Volatile private var gustiness = 0.5f
    @Volatile private var foliage = 0.4f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "strength" -> strength = value.coerceIn(0f, 1f)
            "gustiness" -> gustiness = value.coerceIn(0f, 1f)
            "foliage" -> foliage = value.coerceIn(0f, 1f)
        }
    }

    private var strS = 0.5f
    private var gusS = 0.5f
    private var folS = 0.4f

    private val rnd = Random(31771)

    // --- gust engine --------------------------------------------------------
    private val gSlow = NatWander(211, 0f, 1f, 30f, 60f, 14f)
    private val gMid = NatWander(223, 0f, 1f, 3f, 10f, 2.0f)
    private val gFast = NatWander(227, 0f, 1f, 0.5f, 2f, 0.35f)
    private var gN = 1f
    private var gLag = 1f
    private var gQuick = 1f
    private val kLag = NT_BLOCK / (0.40f * SAMPLE_RATE)
    private val kQuick = NT_BLOCK / (0.06f * SAMPLE_RATE)

    // --- low buffet ---------------------------------------------------------
    private val buffBrL = BrownFilter()
    private val buffBrR = BrownFilter()
    private val buffLpL = Biquad().lowpass(85f, 0.7f)
    private val buffLpR = Biquad().lowpass(90f, 0.7f)
    private val buffTone = NatWander(233, 62f, 118f, 8f, 22f, 6f)

    // --- mid whoosh (three parallel bands) ----------------------------------
    private val b1L = Biquad().bandpass(280f, 1.15f)
    private val b1R = Biquad().bandpass(295f, 1.15f)
    private val b2L = Biquad().bandpass(620f, 1.00f)
    private val b2R = Biquad().bandpass(600f, 1.00f)
    private val b3L = Biquad().bandpass(1150f, 0.88f)
    private val b3R = Biquad().bandpass(1190f, 0.88f)
    private val c1 = NatWander(239, 180f, 450f, 4f, 11f, 3.0f)
    private val c2 = NatWander(241, 400f, 900f, 5f, 13f, 3.5f)
    private val c3 = NatWander(251, 800f, 1600f, 4f, 12f, 3.0f)
    private val l1 = NatWander(257, 0.60f, 1.25f, 6f, 16f, 5f)
    private val l2 = NatWander(263, 0.60f, 1.25f, 6f, 16f, 5f)
    private val l3 = NatWander(269, 0.60f, 1.25f, 6f, 16f, 5f)

    // --- foliage hiss -------------------------------------------------------
    private val folHpL = Biquad().highpass(1900f, 0.7f)
    private val folHpR = Biquad().highpass(1950f, 0.7f)
    private val folLpL = Biquad().lowpass(7800f, 0.7f)
    private val folLpR = Biquad().lowpass(7600f, 0.7f)
    private val folLevel = NatWander(271, 0.75f, 1.25f, 7f, 18f, 5f)

    // --- whistle ------------------------------------------------------------
    private val whL = Biquad().bandpass(2100f, 11f)
    private val whR = Biquad().bandpass(2160f, 11f)
    private val whTone = NatWander(277, 1500f, 3000f, 9f, 24f, 6f)
    private val whGate = NatWander(281, 0f, 1f, 20f, 50f, 10f)

    // --- block-rate derived -------------------------------------------------
    private var blockCountdown = 0
    private var buffGain = 0f
    private var w1 = 0f
    private var w2 = 0f
    private var w3 = 0f
    private var folGain = 0f
    private var whGain = 0f

    private fun updateBlock() {
        strS += (strength - strS) * 0.05f
        gusS += (gustiness - gusS) * 0.05f
        folS += (foliage - folS) * 0.05f

        gSlow.step(NT_BLOCK)
        gMid.step(NT_BLOCK)
        gFast.step(NT_BLOCK)
        buffTone.step(NT_BLOCK)
        c1.step(NT_BLOCK)
        c2.step(NT_BLOCK)
        c3.step(NT_BLOCK)
        l1.step(NT_BLOCK)
        l2.step(NT_BLOCK)
        l3.step(NT_BLOCK)
        folLevel.step(NT_BLOCK)
        whTone.step(NT_BLOCK)
        whGate.step(NT_BLOCK)

        // Depths scale with gustiness; the product is normalised by its own
        // expected mean so the slider adds swing, not level.
        val ds = 0.18f + 0.50f * gusS
        val dm = 0.16f + 0.62f * gusS
        val df = 0.06f + 0.30f * gusS
        val raw = (1f - ds + ds * gSlow.value) *
            (1f - dm + dm * gMid.value) *
            (1f - df + df * gFast.value)
        val mean = (1f - 0.5f * ds) * (1f - 0.5f * dm) * (1f - 0.5f * df)
        gN = (raw / mean).coerceIn(0.06f, 1.95f)
        gLag += (gN - gLag) * kLag
        gQuick += (gN - gQuick) * kQuick

        // Harder wind is brighter: everything shifts up with strength.
        val fScale = 0.78f + 0.46f * strS
        val level = 0.66f + 0.50f * strS

        val bt = buffTone.value * (0.85f + 0.35f * strS)
        buffLpL.lowpass(bt, 0.7f)
        buffLpR.lowpass(bt * 1.06f, 0.7f)
        buffGain = BUFF_GAIN * level * (0.22f + 0.80f * gLag)

        val f1 = (c1.value * fScale).coerceIn(120f, 900f)
        val f2 = (c2.value * fScale).coerceIn(280f, 1800f)
        val f3 = (c3.value * fScale).coerceIn(550f, 3200f)
        b1L.bandpass(f1 * 0.96f, 1.15f)
        b1R.bandpass(f1 * 1.04f, 1.15f)
        b2L.bandpass(f2 * 1.03f, 1.00f)
        b2R.bandpass(f2 * 0.97f, 1.00f)
        b3L.bandpass(f3 * 0.98f, 0.88f)
        b3R.bandpass(f3 * 1.02f, 0.88f)

        val g = 0.27f + 0.77f * gN
        w1 = MID_GAIN * level * g * l1.value * 1.00f
        w2 = MID_GAIN * level * g * l2.value * 0.78f
        w3 = MID_GAIN * level * g * l3.value * (0.36f + 0.40f * strS)

        folHpL.highpass((1900f * fScale).coerceIn(900f, 4000f), 0.7f)
        folHpR.highpass((1950f * fScale).coerceIn(900f, 4000f), 0.7f)
        folLpL.lowpass((7800f * (0.90f + 0.22f * strS)).coerceAtMost(11000f), 0.7f)
        folLpR.lowpass((7600f * (0.90f + 0.22f * strS)).coerceAtMost(11000f), 0.7f)
        val fq = gQuick.coerceAtLeast(0f).pow(1.20f).coerceAtMost(2.4f)
        folGain = FOL_GAIN * folS * folLevel.value * level * (0.06f + 1.15f * fq)

        whL.bandpass(whTone.value, 11f)
        whR.bandpass(whTone.value * 1.02f, 11f)
        val t = ((gN - 1.45f) / 0.55f).coerceIn(0f, 1f)
        val gate = ((whGate.value - 0.45f) / 0.40f).coerceIn(0f, 1f)
        whGain = WH_GAIN * t * t * gate * level
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = NT_BLOCK
                updateBlock()
            }

            val bl = buffLpL.process(buffBrL.next(rnd.nbi())) * buffGain
            val br = buffLpR.process(buffBrR.next(rnd.nbi())) * buffGain

            val ml = b1L.process(rnd.nbi()) * w1 +
                b2L.process(rnd.nbi()) * w2 +
                b3L.process(rnd.nbi()) * w3
            val mr = b1R.process(rnd.nbi()) * w1 +
                b2R.process(rnd.nbi()) * w2 +
                b3R.process(rnd.nbi()) * w3

            val fl = folLpL.process(folHpL.process(rnd.nbi())) * folGain
            val fr = folLpR.process(folHpR.process(rnd.nbi())) * folGain

            val wl = whL.process(rnd.nbi()) * whGain
            val wr = whR.process(rnd.nbi()) * whGain

            left[i] = natCeilClip(bl + ml + fl + wl)
            right[i] = natCeilClip(br + mr + fr + wr)
        }
    }

    private companion object {
        const val BUFF_GAIN = 0.45f
        const val MID_GAIN = 0.40f
        const val FOL_GAIN = 0.21f
        const val WH_GAIN = 0.037f
    }
}

// ---------------------------------------------------------------------------
// Forest night — voices
// ---------------------------------------------------------------------------

/**
 * One near cricket. Unlike the old [ForestNight]'s three clones, each voice
 * draws its *own* syllable length, syllable period, syllable count range,
 * carrier pitch, distance lowpass and stereo position from its seed, so no two
 * share a rhythm. Every so often a voice drops out for 30–90 s: real insects
 * stop, and a chorus where nobody ever stops is the loop tell.
 */
private class NatCricket(seed: Int) {
    private val r = Random(seed)
    private val baseFreq = 3500f + r.nextFloat() * 1500f
    private val sylOn = ((0.010f + r.nextFloat() * 0.012f) * SAMPLE_RATE).toInt()
    private val sylTotal = sylOn + ((0.012f + r.nextFloat() * 0.040f) * SAMPLE_RATE).toInt()
    private val minSyl = 2 + r.nextInt(3)
    private val spanSyl = 2 + r.nextInt(5)
    private val near = r.nextFloat()
    private val amp = (0.055f + 0.075f * near)
    private val h2 = 0.10f + 0.22f * r.nextFloat()
    private val lp = Biquad().lowpass(4200f + 7000f * near, 0.7f)
    private val gainL: Float
    private val gainR: Float

    private var chirping = false
    private var counter = (r.nextFloat() * 4f * SAMPLE_RATE).toInt() + 1
    private var sylLeft = 0
    private var sylPos = 0
    private var phase = 0.0
    private var inc = 0.0

    /**
     * Samples left to keep running the distance lowpass after the last non-zero
     * sample, so it can ring out. Cheap but worth it: a silent voice would
     * otherwise burn a biquad per sample for the ~95 % of the time it is not
     * chirping, and there are five insect voices.
     */
    private var tail = 0

    init {
        val a = (0.08f + r.nextFloat() * 0.84f) * NT_HALF_PI
        gainL = cos(a)
        gainR = sin(a)
    }

    fun sample(out: FloatArray, life: Float) {
        var raw = 0f
        if (!chirping) {
            if (--counter <= 0) {
                chirping = true
                sylLeft = minSyl + r.nextInt(spanSyl)
                sylPos = 0
                // Small per-chirp pitch drift: temperature and effort vary.
                inc = NT_TWO_PI * baseFreq * (0.985 + r.nextDouble() * 0.030) / SAMPLE_RATE
            }
        } else {
            if (sylPos < sylOn) {
                val e = NT_HANN[(256 * sylPos / sylOn).coerceAtMost(256)]
                phase += inc
                if (phase > NT_TWO_PI) phase -= NT_TWO_PI
                raw = (sin(phase) + h2 * sin(2.0 * phase)).toFloat() * e * amp
            }
            if (++sylPos >= sylTotal) {
                sylPos = 0
                if (--sylLeft <= 0) {
                    chirping = false
                    counter = if (r.nextFloat() < 0.030f) {
                        // Long dropout — this voice leaves the chorus for a while.
                        ((30f + r.nextFloat() * 60f) * SAMPLE_RATE).toInt()
                    } else {
                        val scale = (2.6f - 2.2f * life).coerceAtLeast(0.30f)
                        ((0.7f + r.nextFloat() * 3.3f) * scale * SAMPLE_RATE).toInt()
                    }
                }
            }
        }
        if (raw != 0f) tail = 256
        if (tail > 0) {
            tail--
            val s = lp.process(raw)
            out[0] += s * gainL
            out[1] += s * gainR
        }
    }
}

/**
 * A katydid — the second species. Lower (2.4–3.1 kHz), raspy rather than tonal
 * (band-limited noise with a 55–130 Hz internal rasp instead of a sine), and
 * grouped into slow "ch-ch-ch" triples with long pauses. One species repeated
 * three times sounds like a loop; two species sound like a habitat.
 */
private class NatKatydid(seed: Int) {
    private val r = Random(seed)
    private val bp = Biquad()
    private val centre = 2400f + r.nextFloat() * 700f
    private val q = 3.0f + r.nextFloat() * 2.5f
    private val sylOn = ((0.045f + r.nextFloat() * 0.045f) * SAMPLE_RATE).toInt()
    private val sylTotal = sylOn + ((0.055f + r.nextFloat() * 0.075f) * SAMPLE_RATE).toInt()
    private val amp = 0.30f + 0.20f * r.nextFloat()
    private val raspInc = NT_TWO_PI * (55.0 + r.nextDouble() * 75.0) / SAMPLE_RATE
    private val gainL: Float
    private val gainR: Float

    private var chirping = false
    private var counter = (r.nextFloat() * 8f * SAMPLE_RATE).toInt() + 1
    private var sylLeft = 0
    private var sylPos = 0
    private var raspPhase = 0.0
    private var tail = 0

    init {
        val a = (0.05f + r.nextFloat() * 0.90f) * NT_HALF_PI
        gainL = cos(a)
        gainR = sin(a)
        bp.bandpass(centre, q)
    }

    fun sample(noise: Float, out: FloatArray, life: Float) {
        var e = 0f
        if (!chirping) {
            if (--counter <= 0) {
                chirping = true
                sylLeft = 2 + r.nextInt(2)
                sylPos = 0
            }
        } else {
            if (sylPos < sylOn) {
                raspPhase += raspInc
                if (raspPhase > NT_TWO_PI) raspPhase -= NT_TWO_PI
                val rasp = 0.35f + 0.65f * (0.5f + 0.5f * sin(raspPhase).toFloat())
                e = NT_HANN[(256 * sylPos / sylOn).coerceAtMost(256)] * rasp * amp
            }
            if (++sylPos >= sylTotal) {
                sylPos = 0
                if (--sylLeft <= 0) {
                    chirping = false
                    val scale = (2.4f - 1.9f * life).coerceAtLeast(0.35f)
                    counter = ((3f + r.nextFloat() * 6f) * scale * SAMPLE_RATE).toInt()
                }
            }
        }
        // The bandpass keeps running for a moment after the last syllable so it
        // rings out, then idles — see NatCricket.tail.
        if (e != 0f) tail = 256
        if (tail > 0) {
            tail--
            val s = bp.process(noise) * e
            out[0] += s * gainL
            out[1] += s * gainR
        }
    }
}


// ---------------------------------------------------------------------------
// Forest night
// ---------------------------------------------------------------------------

/**
 * One spring peeper — a tree frog whose whole call is a short, clean whistled
 * "peep" near 2.8 kHz with a quick upward slur. Peepers call steadily during
 * a bout (6–18 peeps, ~one per second), then fall silent for a long stretch.
 * Two resolvable voices plus the distant chorus wash in [ForestNightV2] read
 * instantly as "there is a wetland out past the trees".
 */
private class NatPeeper(seed: Int) {
    private val r = Random(seed)
    private val lp = Biquad()
    private val gainL: Float
    private val gainR: Float
    private val amp: Float
    private val baseF: Float
    private val interval: Float

    private var boutLeft = 0
    private var wait: Int
    private var pos = 0
    private var len = 0
    private var phase = 0.0
    private var inc = 0.0
    private var glide = 1.0

    init {
        val near = r.nextFloat()
        lp.lowpass(3200f + 2800f * near, 0.7f)
        amp = 0.09f + 0.10f * near
        baseF = 2550f + r.nextFloat() * 500f
        interval = 0.75f + r.nextFloat() * 0.70f
        val p = (0.15f + r.nextFloat() * 0.70f) * NT_HALF_PI
        gainL = cos(p)
        gainR = sin(p)
        wait = (r.nextFloat() * 20f * SAMPLE_RATE).toInt() + SAMPLE_RATE
    }

    private fun startPeep() {
        pos = 0
        len = ((0.065f + r.nextFloat() * 0.030f) * SAMPLE_RATE).toInt()
        phase = 0.0
        val f = baseF * (1f + 0.04f * (r.nextFloat() * 2f - 1f))
        inc = NT_TWO_PI * f / SAMPLE_RATE
        glide = exp(ln(1.06) / len)
    }

    fun sample(acc: FloatArray, vox: Float) {
        if (pos < len) {
            phase += inc
            inc *= glide
            if (phase > NT_TWO_PI) phase -= NT_TWO_PI
            val u = pos.toFloat() / len
            var e = when {
                u < 0.18f -> u / 0.18f
                u > 0.62f -> (1f - u) / 0.38f
                else -> 1f
            }
            e *= e
            val s = (sin(phase).toFloat() + 0.12f * sin(2.0 * phase).toFloat()) *
                e * amp * (0.40f + 0.60f * vox)
            val o = lp.process(s)
            acc[0] += o * gainL
            acc[1] += o * gainR
            pos++
        } else if (--wait <= 0) {
            if (boutLeft > 0) {
                boutLeft--
                startPeep()
                wait = ((interval * (0.88f + r.nextFloat() * 0.24f)) * SAMPLE_RATE).toInt()
            } else if (r.nextFloat() < 0.05f + 0.50f * vox) {
                boutLeft = 6 + r.nextInt(13)
                wait = 1
            } else {
                wait = ((6f + r.nextFloat() * 18f) * SAMPLE_RATE).toInt()
            }
        }
    }
}

/**
 * **ForestNightV2** — a night forest is a *space* with layered distances and
 * rare events, not a noise bed with insects on top.
 *
 * The old [ForestNight] made two mistakes. First, its backdrop was constant
 * pink noise, and nothing in a forest emits constant pink noise; second, its
 * three crickets shared one syllable rhythm, so the chorus read as one insect
 * heard through a chorus effect.
 *
 *  - **Distant cricket wash** — the signature layer, and the thing that replaces
 *    the pink bed. Dozens of unresolved crickets a hundred metres away blur into
 *    a continuous shimmering band: here, high-Q bandpassed noise around 4.3 kHz
 *    (plus a quieter partner near 5.6 kHz) with a gentle 8–20 Hz amplitude
 *    texture and slow centre/level wander. It is quiet, it never stops, and it
 *    is what makes the scene feel like outdoors-at-night rather than a room.
 *  - **Near crickets** — three resolvable voices, each with its own syllable
 *    length, syllable period, syllable-count range, pitch, distance lowpass and
 *    pan, and each liable to drop out for 30–90 s.
 *  - **A second species** — two katydid-like voices at 2.4–3.1 kHz, raspy
 *    (band-noise with a 55–130 Hz rasp) and slow, in groups of 2–3.
 *  - **Foliage breeze** — a compact version of [WindV2]'s mechanism: a 4–12 s
 *    gust times a 0.8–2.5 s flutter driving a 2–7 kHz leaf hiss, scaled by
 *    `breeze`. On top, every 8–25 s a *single tree* rustles: a 2–5 s swell at
 *    its own drifting stereo position, so the breeze has geography.
 *  - **Night voices** (`voices`) — the wetland-and-sky population: two
 *    resolvable spring peepers calling in bouts, a distant blurred peeper
 *    chorus that rises with the slider, and a far owl that delivers a soft
 *    two-hoot phrase every couple of minutes through heavy air absorption.
 *  - **Stream** (`water`) — a small brook off to one side: two burbling
 *    bandpass streams with fast independent level flutter, plus occasional
 *    rising "glug" bubbles, all under a gentle absorption lowpass and a slow
 *    level wander so it never sounds like a fountain pump.
 *  - **Rare one-off events** — every 1–3 min (scaled by `life`), one of: a twig
 *    snap (a single dull low click), an animal rustle (0.3–0.8 s of scuffling
 *    foliage with an irregular internal envelope), or a lonely night-bird note
 *    (a soft 0.3 s down-sweep from ~2.4 kHz to ~1.5 kHz). All are individually
 *    panned and share a distance lowpass. These surprises are the difference
 *    between "alive" and "looped".
 *  - **Night air** — a barely audible lowpassed brown floor so the scene never
 *    goes digitally black between events.
 *
 * Params: `life` 0..1 (0.5) — cricket/katydid density and event rate;
 * `breeze` 0..1 (0.35) — the foliage layer; `voices` 0..1 (0.4) — peepers and
 * owl; `water` 0..1 (0.25) — the brook.
 */
class ForestNightV2 : SoundGenerator {

    @Volatile private var life = 0.5f
    @Volatile private var breeze = 0.35f
    @Volatile private var voices = 0.4f
    @Volatile private var water = 0.25f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "life" -> life = value.coerceIn(0f, 1f)
            "breeze" -> breeze = value.coerceIn(0f, 1f)
            "voices" -> voices = value.coerceIn(0f, 1f)
            "water" -> water = value.coerceIn(0f, 1f)
        }
    }

    private var lifeS = 0.5f
    private var brzS = 0.35f
    private var voxS = 0.4f
    private var watS = 0.25f

    private val rnd = Random(80553)

    // --- distant cricket wash ----------------------------------------------
    private val washAL = Biquad().bandpass(4300f, 7f)
    private val washAR = Biquad().bandpass(4360f, 7f)
    private val washBL = Biquad().bandpass(5600f, 9f)
    private val washBR = Biquad().bandpass(5680f, 9f)
    private val washTone = NatWander(311, 4000f, 4700f, 9f, 22f, 7f)
    private val washLevel = NatWander(313, 0.70f, 1.25f, 8f, 20f, 6f)
    private val washAmRate = NatWander(317, 8f, 20f, 4f, 12f, 3f)
    private val washAmDepth = NatWander(331, 0.12f, 0.34f, 6f, 15f, 4f)
    private var washPhase = 0.0
    private var washInc = 0.0

    // --- voices --------------------------------------------------------------
    private val crickets = arrayOf(NatCricket(4001), NatCricket(4133), NatCricket(4271))
    private val katydids = arrayOf(NatKatydid(5101), NatKatydid(5237))
    private val acc = FloatArray(2)

    // --- night voices: peepers ------------------------------------------------
    private val peepers = arrayOf(NatPeeper(6101), NatPeeper(6247))
    private val peepWashL = Biquad().bandpass(2900f, 14f)
    private val peepWashR = Biquad().bandpass(2955f, 14f)
    private val peepWashThrob = NatWander(373, 0.45f, 1.0f, 0.7f, 1.8f, 0.4f)

    // --- night voices: far owl -------------------------------------------------
    private val owlLp = Biquad().lowpass(750f, 0.7f)
    private var owlCountdown = (35f * SAMPLE_RATE).toInt()
    private var owlStage = -1            // -1 idle, 0 first hoot, 1 gap, 2 second hoot
    private var owlPos = 0
    private var owlLen = 0
    private var owlPhase = 0.0
    private var owlInc = 0.0
    private var owlAmp = 0f
    private var owlPanL = 0.7f
    private var owlPanR = 0.7f

    // --- stream ------------------------------------------------------------------
    private val brookBp1L = Biquad().bandpass(950f, 1.1f)
    private val brookBp1R = Biquad().bandpass(1000f, 1.1f)
    private val brookBp2L = Biquad().bandpass(1850f, 1.3f)
    private val brookBp2R = Biquad().bandpass(1930f, 1.3f)
    private val brookLpL = Biquad().lowpass(3200f, 0.7f)
    private val brookLpR = Biquad().lowpass(3100f, 0.7f)
    private val brookLevel = NatWander(361, 0.78f, 1.18f, 6f, 16f, 5f)
    private var burble1 = 0.5f
    private var burble1T = 0.5f
    private var burble2 = 0.5f
    private var burble2T = 0.5f
    private val kBurble = 1f - exp(-1f / (0.020f * SAMPLE_RATE))
    private var glugCountdown = (2f * SAMPLE_RATE).toInt()
    private var glugPos = 0
    private var glugLen = 0
    private var glugPhase = 0.0
    private var glugInc = 0.0
    private var glugGlide = 1.0
    private var glugAmp = 0f
    // The brook sits off to one side of the scene.
    private val brookPanL = cos(0.68f)
    private val brookPanR = sin(0.68f)

    // --- foliage breeze -------------------------------------------------------
    private val leafHpL = Biquad().highpass(2000f, 0.7f)
    private val leafHpR = Biquad().highpass(2060f, 0.7f)
    private val leafLpL = Biquad().lowpass(7000f, 0.7f)
    private val leafLpR = Biquad().lowpass(6800f, 0.7f)
    private val brzMid = NatWander(337, 0f, 1f, 4f, 12f, 2.4f)
    private val brzFast = NatWander(347, 0f, 1f, 0.8f, 2.5f, 0.5f)
    private var brzQuick = 0.5f
    private val kBrz = NT_BLOCK / (0.10f * SAMPLE_RATE)

    // --- single-tree rustle swell ---------------------------------------------
    private val treeBpL = Biquad().bandpass(3600f, 0.8f)
    private val treeBpR = Biquad().bandpass(3750f, 0.8f)
    private var treeCountdown = (6f * SAMPLE_RATE).toInt()
    private var treePos = 0
    private var treeLen = 0
    private var treePanL = 0.7f
    private var treePanR = 0.7f
    private var treePanDrift = 0f
    private var treeAmp = 0f
    private var treeEnv = 0f

    // --- rare events ----------------------------------------------------------
    private val events = NatBurstPool(6)
    private val evLpL = Biquad().lowpass(4200f, 0.7f)
    private val evLpR = Biquad().lowpass(4200f, 0.7f)
    private var evCountdown = (28f * SAMPLE_RATE).toInt()

    // Animal rustle (one at a time).
    private val rustleBpL = Biquad().bandpass(3200f, 0.9f)
    private val rustleBpR = Biquad().bandpass(3350f, 0.9f)
    private var rustlePos = 0
    private var rustleLen = 0
    private var rustleAmp = 0f
    private var rustlePanL = 0.7f
    private var rustlePanR = 0.7f
    private var rustleGrain = 0f
    private val kRustle = 1f - exp(-1f / (0.006f * SAMPLE_RATE))

    // Night-bird note (one at a time).
    private var birdPos = 0
    private var birdLen = 0
    private var birdPhase = 0.0
    private var birdInc = 0.0
    private var birdGlide = 1.0
    private var birdAmp = 0f
    private var birdPanL = 0.7f
    private var birdPanR = 0.7f

    // --- night air -------------------------------------------------------------
    private val airBrL = BrownFilter()
    private val airBrR = BrownFilter()
    private val airLpL = Biquad().lowpass(180f, 0.7f)
    private val airLpR = Biquad().lowpass(190f, 0.7f)
    private val airLevel = NatWander(353, 0.70f, 1.25f, 10f, 26f, 8f)

    // --- block-rate derived ------------------------------------------------------
    private var blockCountdown = 0
    private var washGainA = 0f
    private var washGainB = 0f
    private var washDepth = 0.2f
    private var leafGain = 0f
    private var treeGain = 0f
    private var airGain = 0f
    private var peepWashGain = 0f
    private var brookGain = 0f

    private fun updateBlock() {
        lifeS += (life - lifeS) * 0.05f
        brzS += (breeze - brzS) * 0.05f
        voxS += (voices - voxS) * 0.05f
        watS += (water - watS) * 0.05f

        washTone.step(NT_BLOCK)
        washLevel.step(NT_BLOCK)
        washAmRate.step(NT_BLOCK)
        washAmDepth.step(NT_BLOCK)
        brzMid.step(NT_BLOCK)
        brzFast.step(NT_BLOCK)
        airLevel.step(NT_BLOCK)
        peepWashThrob.step(NT_BLOCK)
        brookLevel.step(NT_BLOCK)

        val wt = washTone.value
        washAL.bandpass(wt, 7f)
        washAR.bandpass(wt * 1.015f, 7f)
        washBL.bandpass(wt * 1.30f, 9f)
        washBR.bandpass(wt * 1.325f, 9f)
        val wl = washLevel.value * (0.55f + 0.75f * lifeS)
        washGainA = WASH_GAIN * wl
        washGainB = WASH_GAIN * wl * 0.45f
        washDepth = washAmDepth.value
        washInc = NT_TWO_PI * washAmRate.value / SAMPLE_RATE

        val bg = (0.30f + 0.70f * brzMid.value) * (0.55f + 0.60f * brzFast.value)
        brzQuick += (bg - brzQuick) * kBrz
        leafGain = LEAF_GAIN * brzS * (0.15f + 1.15f * brzQuick.coerceAtLeast(0f).pow(1.15f))
        treeGain = TREE_GAIN * (0.35f + 0.85f * brzS)

        airGain = AIR_GAIN * airLevel.value

        // Distant peeper chorus rises once the voices slider passes ~0.25.
        peepWashGain = PEEP_WASH_GAIN * ((voxS - 0.25f) / 0.75f).coerceIn(0f, 1f) *
            peepWashThrob.value

        // Brook burble targets renew a few times a second.
        if (rnd.nextFloat() < 0.035f) burble1T = rnd.nextFloat()
        if (rnd.nextFloat() < 0.035f) burble2T = rnd.nextFloat()
        // sqrt curve: the stream is already clearly present at the 0.25 default.
        brookGain = BROOK_GAIN * sqrt(watS) * brookLevel.value
    }

    /** Fires one rare one-off: a twig snap, an animal rustle or a bird note. */
    private fun fireEvent() {
        val pan = rnd.nextFloat() * NT_HALF_PI
        val gl = cos(pan)
        val gr = sin(pan)
        when (rnd.nextInt(3)) {
            0 -> {
                // Twig snap: a single dull low click.
                events.spawn(
                    amp = 0.85f + rnd.nextFloat() * 0.75f,
                    decaySamples = (0.006f + rnd.nextFloat() * 0.014f) * SAMPLE_RATE,
                    attackSamples = 3,
                    centerHz = 650f + rnd.nextFloat() * 950f,
                    q = 1.0f + rnd.nextFloat() * 1.4f,
                    gainL = gl,
                    gainR = gr,
                )
            }
            1 -> {
                if (rustlePos < rustleLen) return
                rustleLen = ((0.30f + rnd.nextFloat() * 0.50f) * SAMPLE_RATE).toInt()
                rustlePos = 0
                rustleAmp = 0.22f + rnd.nextFloat() * 0.20f
                rustlePanL = gl
                rustlePanR = gr
                val c = 2400f + rnd.nextFloat() * 1800f
                rustleBpL.bandpass(c, 0.9f)
                rustleBpR.bandpass(c * 1.05f, 0.9f)
            }
            else -> {
                if (birdPos < birdLen) return
                birdLen = ((0.24f + rnd.nextFloat() * 0.14f) * SAMPLE_RATE).toInt()
                birdPos = 0
                birdPhase = 0.0
                val f0 = 1900f + rnd.nextFloat() * 600f
                val f1 = f0 * (0.58f + rnd.nextFloat() * 0.14f)
                birdInc = NT_TWO_PI * f0 / SAMPLE_RATE
                birdGlide = exp(ln((f1 / f0).toDouble()) / birdLen)
                birdAmp = 0.10f + rnd.nextFloat() * 0.07f
                birdPanL = gl
                birdPanR = gr
            }
        }
    }

    /** Starts the far owl's two-hoot phrase. */
    private fun startOwl() {
        owlStage = 0
        owlPos = 0
        owlLen = ((0.30f + rnd.nextFloat() * 0.12f) * SAMPLE_RATE).toInt()
        owlPhase = 0.0
        owlInc = NT_TWO_PI * (330.0 + rnd.nextDouble() * 40.0) / SAMPLE_RATE
        owlAmp = (0.060f + rnd.nextFloat() * 0.025f) * (0.30f + 0.70f * voxS)
        val p = (0.25f + rnd.nextFloat() * 0.50f) * NT_HALF_PI
        owlPanL = cos(p)
        owlPanR = sin(p)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = NT_BLOCK
                updateBlock()
            }

            // --- distant cricket wash -----------------------------------------
            washPhase += washInc
            if (washPhase > NT_TWO_PI) washPhase -= NT_TWO_PI
            val wam = 1f - washDepth + washDepth * (0.5f + 0.5f * sin(washPhase).toFloat())
            val wamR = 1f - washDepth + washDepth * (0.5f + 0.5f * sin(washPhase + 1.1).toFloat())
            var l = (washAL.process(rnd.nbi()) * washGainA +
                washBL.process(rnd.nbi()) * washGainB) * wam
            var r = (washAR.process(rnd.nbi()) * washGainA +
                washBR.process(rnd.nbi()) * washGainB) * wamR

            // --- insect voices --------------------------------------------------
            acc[0] = 0f
            acc[1] = 0f
            for (c in crickets) c.sample(acc, lifeS)
            val kn = rnd.nbi()
            for (k in katydids) k.sample(kn, acc, lifeS)
            l += acc[0]
            r += acc[1]

            // --- night voices: peepers and their distant chorus ------------------
            if (voxS > 0.01f) {
                acc[0] = 0f
                acc[1] = 0f
                for (p in peepers) p.sample(acc, voxS)
                l += acc[0]
                r += acc[1]
                if (peepWashGain > 0.0005f) {
                    l += peepWashL.process(rnd.nbi()) * peepWashGain
                    r += peepWashR.process(rnd.nbi()) * peepWashGain
                }

                // Far owl: two soft hoots through a lot of air.
                if (owlStage >= 0) {
                    var hoot = 0f
                    if (owlStage == 0 || owlStage == 2) {
                        owlPhase += owlInc
                        if (owlPhase > NT_TWO_PI) owlPhase -= NT_TWO_PI
                        val u = owlPos.toFloat() / owlLen
                        val e = NT_HANN[(256f * u).toInt().coerceIn(0, 256)]
                        hoot = (sin(owlPhase).toFloat() + 0.25f * sin(2.0 * owlPhase).toFloat()) *
                            e * owlAmp
                    }
                    val o = owlLp.process(hoot)
                    l += o * owlPanL
                    r += o * owlPanR
                    if (++owlPos >= owlLen) {
                        owlPos = 0
                        owlStage++
                        when (owlStage) {
                            1 -> owlLen = (0.50f * SAMPLE_RATE).toInt()      // between hoots
                            2 -> {
                                owlLen = ((0.36f + rnd.nextFloat() * 0.14f) * SAMPLE_RATE).toInt()
                                owlPhase = 0.0
                                owlInc *= 0.965                              // second hoot sags
                            }
                            else -> owlStage = -1
                        }
                    }
                } else if (--owlCountdown <= 0) {
                    owlCountdown = ((70f + rnd.nextFloat() * 120f) *
                        (1.5f - voxS).coerceAtLeast(0.5f) * SAMPLE_RATE).toInt()
                    startOwl()
                }
            }

            // --- stream -----------------------------------------------------------
            if (watS > 0.01f) {
                burble1 += (burble1T - burble1) * kBurble
                burble2 += (burble2T - burble2) * kBurble
                val b1 = 0.35f + 0.90f * burble1
                val b2 = 0.30f + 0.80f * burble2
                var bl = brookBp1L.process(rnd.nbi()) * b1 + brookBp2L.process(rnd.nbi()) * b2 * 0.8f
                var br = brookBp1R.process(rnd.nbi()) * b1 + brookBp2R.process(rnd.nbi()) * b2 * 0.8f

                // Rising glug bubbles.
                if (glugPos < glugLen) {
                    glugPhase += glugInc
                    glugInc *= glugGlide
                    if (glugPhase > NT_TWO_PI) glugPhase -= NT_TWO_PI
                    val u = glugPos.toFloat() / glugLen
                    val e = (u / 0.05f).coerceAtMost(1f) * (1f - u).pow(2.2f)
                    val g = sin(glugPhase).toFloat() * e * glugAmp
                    bl += g
                    br += g * 0.8f
                    glugPos++
                } else if (--glugCountdown <= 0) {
                    glugCountdown = ((0.8f + rnd.nextFloat() * 3.5f) / watS.coerceAtLeast(0.15f) *
                        SAMPLE_RATE).toInt()
                    glugLen = ((0.055f + rnd.nextFloat() * 0.055f) * SAMPLE_RATE).toInt()
                    glugPos = 0
                    glugPhase = 0.0
                    val f0 = 260.0 + rnd.nextDouble() * 260.0
                    glugInc = NT_TWO_PI * f0 / SAMPLE_RATE
                    glugGlide = exp(ln(1.35) / glugLen)
                    glugAmp = 0.30f + rnd.nextFloat() * 0.35f
                }

                l += brookLpL.process(bl) * brookGain * brookPanL
                r += brookLpR.process(br) * brookGain * brookPanR
            }

            // --- foliage breeze ---------------------------------------------------
            l += leafLpL.process(leafHpL.process(rnd.nbi())) * leafGain
            r += leafLpR.process(leafHpR.process(rnd.nbi())) * leafGain

            // --- single-tree rustle swell -----------------------------------------
            if (treePos < treeLen) {
                val u = treePos.toFloat() / treeLen
                treeEnv = NT_HANN[(256f * u).toInt().coerceIn(0, 256)] * treeAmp
                // The tree drifts slowly across the field while it rustles.
                treePanL += (cos(treePanDrift) - treePanL) * 0.00002f
                treePanR += (sin(treePanDrift) - treePanR) * 0.00002f
                treePos++
            } else {
                treeEnv = 0f
                if (--treeCountdown <= 0) {
                    treeCountdown = ((8f + rnd.nextFloat() * 17f) * SAMPLE_RATE).toInt()
                    treeLen = ((2f + rnd.nextFloat() * 3f) * SAMPLE_RATE).toInt()
                    treePos = 0
                    treeAmp = 0.55f + rnd.nextFloat() * 0.55f
                    val p = rnd.nextFloat() * NT_HALF_PI
                    treePanL = cos(p)
                    treePanR = sin(p)
                    treePanDrift = (rnd.nextFloat() * NT_HALF_PI)
                    val c = 3000f + rnd.nextFloat() * 1600f
                    treeBpL.bandpass(c, 0.8f)
                    treeBpR.bandpass(c * 1.04f, 0.8f)
                }
            }
            // Enveloped *input* again, so the band is silent (not just muted)
            // before it is retuned for the next tree.
            val tg = treeEnv * treeGain
            l += treeBpL.process(rnd.nbi() * tg) * treePanL
            r += treeBpR.process(rnd.nbi() * tg) * treePanR

            // --- rare one-off events -----------------------------------------------
            if (--evCountdown <= 0) {
                val scale = (1.6f - 1.2f * lifeS).coerceAtLeast(0.30f)
                evCountdown = ((55f + rnd.nextFloat() * 110f) * scale * SAMPLE_RATE).toInt()
                fireEvent()
            }
            acc[0] = 0f
            acc[1] = 0f
            events.add(rnd.nbi(), acc)
            if (rustlePos < rustleLen) {
                val u = rustlePos.toFloat() / rustleLen
                // Fast attack, long tail, plus an irregular internal grain so it
                // scuffles rather than swells.
                val shape = (1f - u).pow(1.6f) * (u / 0.06f).coerceAtMost(1f)
                rustleGrain += (rnd.nextFloat() - rustleGrain) * kRustle
                val e = shape * rustleAmp * (0.35f + 1.30f * rustleGrain)
                acc[0] += rustleBpL.process(rnd.nbi() * e) * rustlePanL
                acc[1] += rustleBpR.process(rnd.nbi() * e) * rustlePanR
                rustlePos++
            }
            if (birdPos < birdLen) {
                birdPhase += birdInc
                birdInc *= birdGlide
                if (birdPhase > NT_TWO_PI) birdPhase -= NT_TWO_PI
                val e = NT_HANN[(256 * birdPos / birdLen).coerceIn(0, 256)] * birdAmp
                val s = sin(birdPhase).toFloat() * e
                acc[0] += s * birdPanL
                acc[1] += s * birdPanR
                birdPos++
            }
            l += evLpL.process(acc[0])
            r += evLpR.process(acc[1])

            // --- night air ----------------------------------------------------------
            l += airLpL.process(airBrL.next(rnd.nbi())) * airGain
            r += airLpR.process(airBrR.next(rnd.nbi())) * airGain

            left[i] = natCeilClip(l)
            right[i] = natCeilClip(r)
        }
    }

    private companion object {
        const val WASH_GAIN = 0.34f
        const val LEAF_GAIN = 0.235f
        const val TREE_GAIN = 0.21f
        const val AIR_GAIN = 0.16f
        const val PEEP_WASH_GAIN = 0.085f
        const val BROOK_GAIN = 0.26f
    }
}
