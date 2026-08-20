package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A physically-inspired fan simulation, built from the actual acoustics of
 * a fan rather than plain filtered noise:
 *
 *  - **Motor/blade hum**: a harmonic stack at the blade-pass frequency
 *    (BPF = rotation rate × blade count), the tonal signature of any fan.
 *  - **Blade-modulated turbulence**: broadband air noise amplitude-modulated
 *    once per blade passage. Each blade carries a fixed random gain offset,
 *    so the modulation pattern repeats per *revolution* — the realistic
 *    slightly-unbalanced wobble of a real fan.
 *  - **Vortex whoosh**: a resonant mid band whose center rises with speed,
 *    the sound of vortices shed from the blade tips.
 *  - **Motor buzz**: magnetic hum at twice the mains frequency (100 Hz) with
 *    a raspy 1/k harmonic tail and a light ~11 Hz winding flutter. Electrical,
 *    so its pitch stays fixed as rpm changes — only its level rises with load.
 *  - **Distance**: one-pole air-absorption lowpass plus level drop.
 *  - **Oscillation**: a slow constant-power pan sweep with a gain dip at
 *    the extremes, like an oscillating stand fan turning past you.
 *
 * All parameters are live; targets are smoothed per block so slider moves
 * glide instead of zippering.
 */
class SimulatedFan : SoundGenerator {

    // Targets written by the UI thread.
    @Volatile private var rpm = 1050f
    @Volatile private var bladesParam = 5f
    @Volatile private var size = 0.5f
    @Volatile private var distance = 0.35f
    @Volatile private var sway = 0f
    @Volatile private var buzz = 0.3f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rpm" -> rpm = value
            "blades" -> bladesParam = value
            "size" -> size = value
            "distance" -> distance = value
            "sway" -> sway = value
            "buzz" -> buzz = value
        }
    }

    // Smoothed working values (render thread only).
    private var rpmS = 1050f
    private var sizeS = 0.5f
    private var distS = 0.35f
    private var swayS = 0f
    private var buzzS = 0.3f

    private val rnd = Random(2718)
    private val bladeGains = FloatArray(7) { 0.85f + rnd.nextFloat() * 0.30f }

    private var rotPhase = 0f
    private val humPhases = DoubleArray(4)
    private val humIncs = DoubleArray(4)
    private val humAmps = FloatArray(4)
    private var oscPhase = 0.0

    // Electrical motor buzz: magnetic hum at 2x mains (100 Hz) with a raspy
    // harmonic tail and a light flutter, as if a winding lamination is loose.
    private val buzzPhases = DoubleArray(8)
    private val buzzIncs = DoubleArray(8)
    private val buzzAmps = FloatArray(8)
    private var flutterPhase = 0.0

    private val turbLpL = OnePoleLp().setCutoff(1500f)
    private val turbLpR = OnePoleLp().setCutoff(1500f)
    private val distLpL = OnePoleLp().setCutoff(8000f)
    private val distLpR = OnePoleLp().setCutoff(8000f)
    private val whooshL = Biquad().bandpass(600f, 0.9f)
    private val whooshR = Biquad().bandpass(640f, 0.9f)

    private var blockCountdown = 0
    private var frPerSample = 0f
    private var turbGain = 0f
    private var whooshGain = 0f
    private var distGain = 1f
    private var blades = 5

    private fun updateBlock() {
        rpmS += (rpm - rpmS) * 0.06f
        sizeS += (size - sizeS) * 0.06f
        distS += (distance - distS) * 0.06f
        swayS += (sway - swayS) * 0.06f

        blades = bladesParam.roundToInt().coerceIn(3, 7)
        val fr = rpmS / 60f                       // revolutions per second
        val bpf = fr * blades                     // blade-pass frequency, Hz
        val speed = ((rpmS - 500f) / 1100f).coerceIn(0f, 1f)
        frPerSample = fr / SAMPLE_RATE

        // Hum stack: bigger fans hum deeper and louder; faster fans harder.
        val humBase = (0.22f + 0.50f * sizeS) * (0.30f + 0.70f * speed) * 0.30f
        val harmonicAmps = floatArrayOf(1f, 0.45f, 0.22f, 0.10f)
        for (k in 0..3) {
            humIncs[k] = 2.0 * PI * bpf * (k + 1) / SAMPLE_RATE
            humAmps[k] = humBase * harmonicAmps[k]
        }

        // Motor buzz: fixed 100 Hz mains fundamental (independent of rpm —
        // it's electrical, not mechanical), 1/k harmonic rolloff for rasp,
        // level rising modestly with load (speed). Normalized by the harmonic
        // series sum (~2.72) so buzz=1 contributes ~0.11 peak before the
        // distance lowpass.
        buzzS += (buzz - buzzS) * 0.06f
        val buzzBase = 0.11f * buzzS * (0.45f + 0.55f * speed) / 2.72f
        for (k in 0..7) {
            buzzIncs[k] = 2.0 * PI * 100.0 * (k + 1) / SAMPLE_RATE
            buzzAmps[k] = buzzBase / (k + 1)
        }

        // Turbulence: aerodynamic noise grows ~quadratically with speed;
        // larger fans move more (lower-pitched) air.
        val turbFc = 350f + speed * 2800f - sizeS * 600f
        turbLpL.setCutoff(turbFc.coerceIn(250f, 4000f))
        turbLpR.setCutoff((turbFc * 1.07f).coerceIn(250f, 4000f))
        turbGain = (0.15f + 0.85f * speed * speed) * (0.45f + 0.55f * sizeS) * 0.55f

        // Tip-vortex whoosh rides up with speed.
        val whooshFc = 260f + speed * 850f
        whooshL.bandpass(whooshFc, 0.9f)
        whooshR.bandpass(whooshFc * 1.05f, 0.9f)
        whooshGain = speed * sqrt(speed) * 0.30f

        // Distance: air absorption + inverse level.
        val distFc = 900f + (1f - distS) * 8000f
        distLpL.setCutoff(distFc)
        distLpR.setCutoff(distFc)
        distGain = 1f / (1f + 2.2f * distS)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = 256
                updateBlock()
            }

            // Rotation and per-blade modulation.
            rotPhase += frPerSample
            if (rotPhase >= 1f) rotPhase -= 1f
            val bladePos = rotPhase * blades
            val bladeIdx = bladePos.toInt() % blades
            val within = bladePos - bladePos.toInt()
            val bladeEnv = (0.5f - 0.5f * cos(TWO_PI_F * within)) * bladeGains[bladeIdx]
            val mod = 0.80f + 0.40f * bladeEnv

            // Hum.
            var hum = 0f
            for (k in 0..3) {
                humPhases[k] += humIncs[k]
                if (humPhases[k] > 2 * PI) humPhases[k] -= 2 * PI
                hum += sin(humPhases[k]).toFloat() * humAmps[k]
            }

            // Motor buzz with winding flutter.
            var buzzSum = 0f
            if (buzzS > 0.005f) {
                for (k in 0..7) {
                    buzzPhases[k] += buzzIncs[k]
                    if (buzzPhases[k] > 2 * PI) buzzPhases[k] -= 2 * PI
                    buzzSum += sin(buzzPhases[k]).toFloat() * buzzAmps[k]
                }
                flutterPhase += TWO_PI_D * 10.7 / SAMPLE_RATE
                if (flutterPhase > 2 * PI) flutterPhase -= 2 * PI
                buzzSum *= 1f + 0.10f * sin(flutterPhase).toFloat()
                hum += buzzSum
            }

            // Air.
            val airL = turbLpL.process(rnd.nextFloat() * 2f - 1f) * turbGain * mod
            val airR = turbLpR.process(rnd.nextFloat() * 2f - 1f) * turbGain * mod
            val whL = whooshL.process(rnd.nextFloat() * 2f - 1f) * whooshGain * mod
            val whR = whooshR.process(rnd.nextFloat() * 2f - 1f) * whooshGain * mod

            var l = distLpL.process(hum + airL + whL) * distGain
            var r = distLpR.process(hum + airR + whR) * distGain

            // Oscillation: slow pan sweep + gain dip when pointed away.
            if (swayS > 0.01f) {
                oscPhase += TWO_PI_D * 0.11 / SAMPLE_RATE
                if (oscPhase > 2 * PI) oscPhase -= 2 * PI
                val pan = (swayS * 0.85f * sin(oscPhase)).toFloat()
                val away = 0.5f + 0.5f * cos(oscPhase).toFloat()
                val dip = 1f - 0.22f * swayS * away
                val gl = sqrt(0.5f * (1f - pan)) * 1.41421f
                val gr = sqrt(0.5f * (1f + pan)) * 1.41421f
                l *= gl * dip
                r *= gr * dip
            }

            left[i] = l
            right[i] = r
        }
    }

    private companion object {
        val TWO_PI_F = (2.0 * PI).toFloat()
        const val TWO_PI_D = 2.0 * PI
    }
}
