package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * **ThunderV2** — far-away thunder that *rolls*.
 *
 * A lightning channel is kilometres long, so its sound never arrives as one
 * burst: segments of the channel are at different distances, and terrain and
 * cloud faces echo the peal back, so a real distant strike is heard as a
 * 10–30 s *roll* — several overlapping swells that surge, fade, and surge
 * again while drifting across the sky. The old element rendered a single
 * 3.5–8 s enveloped noise burst, which read as "a low whoosh", not thunder.
 *
 * The model:
 *
 *  - **A strike is a cluster of 4–8 sub-peals.** Each sub-peal is its own
 *    brown-noise voice with its own start delay (spread over the roll span,
 *    denser at the front), duration (2–6 s, longer at the tail), level trend
 *    (decaying, with ~1-in-3 resurgences — the "rolls back around" moment),
 *    lowpass (later peals darker: they travelled farther), stereo position
 *    interpolated along the strike's sky-path, attack (the front of a close
 *    strike is abrupt, everything after swells in), and a 1.5–5 Hz "growl"
 *    AM plus a slow texture walk so the rumble churns instead of hissing.
 *  - **Distance** sets the master lowpass (≈285 Hz up close, ≈55 Hz on the
 *    horizon), the roll span (far strikes smear longer), the level, and the
 *    onset: below ≈0.45 the strike opens with a *whump* — a 90→42 Hz gliding
 *    thump, the felt-more-than-heard pressure front — and a sharper first
 *    peal.
 *  - **Sky-path panning**: each strike picks an entry and exit azimuth; its
 *    sub-peals sit along that path, so the roll audibly travels.
 *  - **Storm bed**: between strikes a barely-there 45 Hz breathing rumble
 *    floor (scaled by activity, gone at activity 0) keeps the storm present
 *    on the horizon rather than switching the element off.
 *  - **Double strikes**: ~1 in 4 waits is short (4–10 s), so a new roll
 *    overlaps the tail of the last — storms cluster, they don't tick.
 *
 * A 22 Hz output highpass keeps the deep material out of DC/speaker-abuse
 * territory. Params unchanged: "rate" (storm activity) 0..1, "distance" 0..1.
 */
class ThunderV2 : SoundGenerator {

    @Volatile private var rate = 0.4f
    @Volatile private var distance = 0.6f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value.coerceIn(0f, 1f)
            "distance" -> distance = value.coerceIn(0f, 1f)
        }
    }

    private val rnd = Random(5909)

    // --- sub-peal voices ------------------------------------------------------
    private class Peal {
        val brownL = BrownFilter()
        val brownR = BrownFilter()
        val lpL = Biquad().lowpass(120f)
        val lpR = Biquad().lowpass(120f)
        var active = false
        var delay = 0
        var pos = 0
        var dur = 1
        var amp = 0f
        var attack = 0.15f
        var gainL = 0.7f
        var gainR = 0.7f
        var growlPhase = 0.0
        var growlInc = 0.0
        var growlDepth = 0f
        var tex = 0.7f
        var texTarget = 0.7f
    }

    private val peals = Array(MAX_PEALS) { Peal() }

    // --- close-strike whump -----------------------------------------------------
    private var whumpPos = 0
    private var whumpLen = 0
    private var whumpPhase = 0.0
    private var whumpInc = 0.0
    private var whumpGlide = 1.0
    private var whumpAmp = 0f

    // --- storm bed ---------------------------------------------------------------
    private val bedBrL = BrownFilter()
    private val bedBrR = BrownFilter()
    private val bedLpL = Biquad().lowpass(48f)
    private val bedLpR = Biquad().lowpass(48f)
    private var bedBreath = 0.8f
    private var bedTarget = 0.8f
    private var bedGain = 0f

    // --- output shaping -------------------------------------------------------------
    private val hpL = Biquad().highpass(22f, 0.7f)
    private val hpR = Biquad().highpass(22f, 0.7f)

    private var waiting = (SAMPLE_RATE * 6)
    private var blockCountdown = 0
    private var rateS = 0.4f
    private var distS = 0.6f

    private fun nextWait(): Int {
        // ~1 in 4 strikes arrives on the heels of the last one.
        if (rnd.nextFloat() < 0.25f) {
            return (SAMPLE_RATE * (4f + rnd.nextFloat() * 6f)).toInt()
        }
        val scale = (1.9f - 1.7f * rateS).coerceAtLeast(0.15f)
        return (SAMPLE_RATE * (14f + rnd.nextFloat() * 46f) * scale).toInt()
    }

    private fun startStrike() {
        val near = 1f - distS
        val n = 4 + rnd.nextInt(5)                       // 4..8 sub-peals
        val span = (6f + rnd.nextFloat() * 10f + distS * 9f)   // seconds
        val baseFc = 55f + near * 230f
        val baseAmp = (0.55f + rnd.nextFloat() * 0.45f) * (1f - 0.52f * distS) * PEAL_GAIN
        // The roll travels: entry and exit azimuths for this strike.
        val a0 = rnd.nextFloat() * HALF_PI
        val a1 = (a0 + (rnd.nextFloat() - 0.5f) * 1.3f).coerceIn(0.10f, HALF_PI - 0.10f)

        var trend = 1f
        var launched = 0
        for (k in 0 until n) {
            val v = peals.firstOrNull { !it.active } ?: break
            val u = k.toFloat() / (n - 1).coerceAtLeast(1)
            v.active = true
            v.delay = (span * u.pow(1.35f) * (0.75f + rnd.nextFloat() * 0.5f) * SAMPLE_RATE).toInt()
            v.dur = ((2.0f + rnd.nextFloat() * 2.6f + u * 1.8f) * SAMPLE_RATE).toInt()
            v.pos = 0
            // Decaying level trend with occasional resurgences.
            if (k > 0) trend *= if (rnd.nextFloat() < 0.30f) 1.35f else 0.72f
            v.amp = baseAmp * trend.coerceIn(0.10f, 1.1f) * (0.7f + rnd.nextFloat() * 0.3f)
            // Later peals travelled farther: darker.
            val fc = (baseFc * (1f - 0.07f * k) * (0.85f + rnd.nextFloat() * 0.30f))
                .coerceAtLeast(42f)
            v.lpL.lowpass(fc)
            v.lpR.lowpass(fc * 1.06f)
            v.attack = if (k == 0) (0.02f + 0.20f * distS) else (0.14f + rnd.nextFloat() * 0.22f)
            val ang = a0 + (a1 - a0) * u + (rnd.nextFloat() - 0.5f) * 0.18f
            val a = ang.coerceIn(0.05f, HALF_PI - 0.05f)
            v.gainL = cos(a)
            v.gainR = sin(a)
            v.growlInc = TAU_D * (1.5 + rnd.nextDouble() * 3.5) / SAMPLE_RATE
            v.growlDepth = 0.15f + rnd.nextFloat() * 0.20f
            v.tex = 0.5f + rnd.nextFloat() * 0.4f
            v.texTarget = v.tex
            launched++
        }

        // Close strikes open with a felt whump.
        if (distS < 0.45f && launched > 0) {
            val punch = (0.45f - distS) / 0.45f
            whumpLen = ((0.45f + rnd.nextFloat() * 0.35f) * SAMPLE_RATE).toInt()
            whumpPos = 0
            whumpPhase = 0.0
            val f0 = 80.0 + rnd.nextDouble() * 20.0
            whumpInc = TAU_D * f0 / SAMPLE_RATE
            whumpGlide = Math.exp(Math.log(42.0 / f0) / whumpLen)
            whumpAmp = 0.42f * punch * (0.7f + rnd.nextFloat() * 0.3f)
        }
    }

    private fun updateBlock() {
        rateS += (rate - rateS) * 0.05f
        distS += (distance - distS) * 0.05f

        // Bed breathing: a slow random walk between swells.
        if (rnd.nextFloat() < 0.02f) bedTarget = 0.45f + rnd.nextFloat() * 0.85f
        bedBreath += (bedTarget - bedBreath) * 0.006f
        bedGain = 0.30f * rateS * (1f - 0.35f * distS) * bedBreath

        // Per-peal texture walks.
        for (v in peals) {
            if (!v.active) continue
            if (rnd.nextFloat() < 0.04f) v.texTarget = 0.35f + rnd.nextFloat() * 0.65f
            v.tex += (v.texTarget - v.tex) * 0.03f
        }
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = 256
                updateBlock()
            }
            if (--waiting <= 0) {
                waiting = nextWait()
                startStrike()
            }

            var l = 0f
            var r = 0f

            for (v in peals) {
                if (!v.active) continue
                if (v.delay > 0) {
                    v.delay--
                    continue
                }
                val t = v.pos.toFloat() / v.dur
                var att = (t / v.attack).coerceAtMost(1f)
                att *= att
                val env = att * (1f - t).pow(1.85f)
                v.growlPhase += v.growlInc
                if (v.growlPhase > TAU_D) v.growlPhase -= TAU_D
                val g = 1f + v.growlDepth * sin(v.growlPhase).toFloat()
                val a = env * v.tex * g * v.amp
                l += v.lpL.process(v.brownL.next(rnd.b())) * a * v.gainL
                r += v.lpR.process(v.brownR.next(rnd.b())) * a * v.gainR
                if (++v.pos >= v.dur) v.active = false
            }

            // Whump: the pressure front of a close strike, centred.
            if (whumpPos < whumpLen) {
                whumpPhase += whumpInc
                whumpInc *= whumpGlide
                if (whumpPhase > TAU_D) whumpPhase -= TAU_D
                val u = whumpPos.toFloat() / whumpLen
                val e = (u / 0.06f).coerceAtMost(1f) * (1f - u).pow(2.4f)
                val s = sin(whumpPhase).toFloat() * e * whumpAmp
                l += s
                r += s
                whumpPos++
            }

            // Storm bed.
            l += bedLpL.process(bedBrL.next(rnd.b())) * bedGain
            r += bedLpR.process(bedBrR.next(rnd.b())) * bedGain

            left[i] = thunderCeil(hpL.process(l))
            right[i] = thunderCeil(hpR.process(r))
        }
    }

    private companion object {
        const val MAX_PEALS = 6
        const val PEAL_GAIN = 3.6f
        const val TAU_D = 2.0 * PI
        val HALF_PI = (PI / 2).toFloat()
    }
}

private fun Random.b(): Float = nextFloat() * 2f - 1f

/** Odd smooth soft ceiling (Padé of 0.65·tanh(x/0.65)), bounded to ±0.65. */
private fun thunderCeil(x: Float): Float {
    val u = (x / 0.65f).coerceIn(-3f, 3f)
    val u2 = u * u
    return 0.65f * u * (27f + u2) / (27f + 9f * u2)
}
