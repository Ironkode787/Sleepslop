package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Train.kt — a ground-up rework of the night-train scene.
 *
 * The class this replaces (`NightTrain`) was a brown drone with two noise
 * bursts fired at fixed offsets inside a 0.62 s bar. Three things were wrong
 * with it, and all three are structural rather than cosmetic:
 *
 *  1. **It was a metronome.** A perfectly periodic two-hit bar is a drum
 *     machine. Real clickety-clack is *quasi*-periodic: the interval between
 *     the two axles of one bogie is short and fixed by the wheelbase, the
 *     interval between bogies is long and fixed by the car, and the interval
 *     between joints jitters with the track. The ear locks onto that
 *     long/short/long lilt and finds it hypnotic precisely because it is not a
 *     even pulse.
 *  2. **It had no train.** There was no consist, no car length, no speed — so
 *     nothing about the sound said how fast or how long the train was, and
 *     nothing could ever change.
 *  3. **It had no journey.** Forty minutes of it is forty minutes of the same
 *     0.62 s bar. Real night trains give you a horn across a valley, the hollow
 *     boom of a viaduct, the faint moan of a long curve — rarely, gently, and
 *     unpredictably. That is the life of the sound.
 *
 * The rework fixes all three by simulating the *geometry* instead of a rhythm.
 *
 * ### The consist
 *
 * A position `x` in metres advances at the train's speed. Five cars' worth of
 * axles sit at their real offsets behind that point (car pitch 26.6 m, bogie
 * centres 19.0 m, wheelbase 2.6 m); rail joints sit at their real positions
 * along the track (30 m European jointed rail, each joint displaced up to
 * ±0.45 m, so joint-to-joint spacing varies by up to ±3 %). A clack fires
 * whenever an axle passes a joint. That
 * is the whole rhythm engine — there is no bar, no tempo and no sequencer.
 *
 * The ratios matter. With 30 m rail, 19 m bogie centres and a 2.6 m wheelbase,
 * one car's four axles cross a rail length at 2.2 / 4.8 / 21.2 / 23.8 m — a
 * tight pair, a 16.4 m pause, another tight pair, then 8.4 m to its own next
 * pair. At the default speed (25 m/s) that measures
 * `clack-clack (103 ms) — 650 ms — clack-clack (103 ms) — 336 ms — …`: the
 * classic two-long two-short lilt, in which only the joint-to-joint gaps carry
 * the ±3 % jitter, because both axles of a bogie strike the *same* joint. The
 * neighbouring cars' pairs land in the gaps, quieter and duller.
 *
 * `rails` is the fraction of joints that exist at all. At 1 every joint is a
 * joint (old bolted track); at 0 only about 3 % are, which is modern continuous
 * welded rail with the occasional insulated joint or crossing — one every
 * half-minute or so, and when it comes you hear the *whole consist* run over
 * it, car by car, which is exactly right.
 *
 * ### The layers
 *
 *  - **Rolling drone.** Brown + pink through a lowpass whose cutoff and level
 *    both climb with speed, plus a coach-body resonance around 60–110 Hz, plus
 *    a 15–40 s random walk on cutoff and level — the track surface changing
 *    under you — plus a faint speed-proportional 2–4 kHz shimmer (rail singing).
 *  - **Coach sway.** Two incommensurate oscillators at 0.25–0.6 Hz, taken with
 *    a 0.55 rad channel offset so the same modulator both breathes the level
 *    (±1.4 dB) and rocks the image very slightly. Depth falls to zero with
 *    distance: you do not feel a coach rock from a hillside.
 *  - **Wind rush.** 260–1300 Hz turbulence scaled by speed and by
 *    `(1 - distance)^1.4`, with 2–6 s swells every 20–60 s — a cutting, a
 *    bridge parapet, a passing train — which also duck the top end and drop a
 *    sub-40 Hz pressure whump in.
 *  - **Journey events**, one every 60–180 s: a two-note horn (~310 + 370 Hz,
 *    minor third, heavily lowpassed, with a valley echo returning from the
 *    other side of the image), a bridge passage (8–20 s of hollow ~150 Hz
 *    resonance with a metallic ring added to every clack), or a long curve
 *    (10–25 s of very quiet flange moan — a wandering Q≈17 resonance at
 *    1.2–2 kHz, hard-capped so it can never become a squeal).
 *
 * ### Perspective
 *
 * `distance` is a single control that changes what kind of sound this is. At 0
 * you are in the coach: full band, rocking, close bright clacks, wind rush,
 * your own car much louder than its neighbours. At 1 you are across the valley:
 * everything through a 380 Hz / 590 Hz two-pole cascade, the stereo image
 * collapsed most of the way to mono (with the power that costs normalised back
 * out), level down about 6 dB, clacks stretched from 20 ms to 95 ms and
 * darkened into soft thuds, the whole consist at one level because every car is
 * now the same distance away, and a 20–60 s undulation on the level as the air
 * and the terrain move it around. No interior cue survives: sway depth, wind
 * rush and the coach-body resonance all scale to zero or near it.
 *
 * Threading follows the house convention: `setParam` runs on the UI thread and
 * only writes `@Volatile` floats; the render thread reads them once per control
 * block and glides its own smoothed copies toward them. Filters are retuned on
 * control ticks (128 samples), never per sample. Nothing allocates after
 * construction. File-private helpers are `TR_*`/`Trn*`/`tnz`-prefixed so they
 * cannot be confused with the same ideas in Water.kt or Nature.kt.
 */

/** Control-rate period in samples. Filters and gains are updated this often. */
private const val TR_BLOCK = 128

/** Soft ceiling used as a safety net; transparent well below it. */
private const val TR_CEIL = 0.98f
private const val TR_CEIL_INV = 1.0204082f

private val TR_TWO_PI = 2.0 * PI
private val TR_HALF_PI = (PI / 2.0).toFloat()

/** Raised-cosine window sampled at 257 points; attack ramps index this. */
private val TR_HANN = FloatArray(257) { 0.5f - 0.5f * cos(2.0 * PI * it / 256.0).toFloat() }

private fun Random.tnz(): Float = nextFloat() * 2f - 1f

/**
 * Odd, smooth soft ceiling (Padé approximation of `C * tanh(x / C)`).
 * Transparent below about a third of [TR_CEIL], hard-bounded to ±[TR_CEIL].
 * It exists only so a rare alignment — a bridge clack landing on a sway peak
 * during a horn — cannot spike the mix.
 */
private fun trnCeilClip(x: Float): Float {
    val u = (x * TR_CEIL_INV).coerceIn(-3f, 3f)
    val u2 = u * u
    return TR_CEIL * u * (27f + u2) / (27f + 9f * u2)
}

/**
 * Deterministic integer hash → [0, 1). Joint jitter and the welded/jointed
 * decision must be a pure function of the joint's index, because twenty axles
 * spread over four car lengths cross the same joint at different times and all
 * of them have to agree on where it is.
 */
private fun trnHash(n: Int, salt: Int): Float {
    var h = n * 374761393 + salt * 668265263
    h = h xor (h ushr 13)
    h *= -1640531527
    h = h xor (h ushr 16)
    h *= 668265261
    h = h xor (h ushr 15)
    return (h ushr 8) * (1f / 16777216f)
}

/**
 * Smoothstep trapezoid: 0 outside `0..len`, ramping over `ramp` samples at both
 * ends. Used for the multi-second journey events, which must fade in and out
 * rather than switch.
 */
private fun trnEdge(pos: Int, len: Int, ramp: Int): Float {
    if (pos <= 0 || pos >= len || len <= 0) return 0f
    val r = if (ramp < 1) 1 else ramp
    val a = pos.toFloat() / r
    val b = (len - pos).toFloat() / r
    val u = (if (a < b) a else b).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

/**
 * A band-limited random walk: every [minSec]..[maxSec] it picks a new target
 * inside [lo]..[hi] and glides toward it with time constant [tauSec]. Because
 * it re-targets before it arrives, the output is smooth and never periodic.
 * Every continuous layer here has at least one of these on it — including the
 * train's own speed, which drifts ±2.5 % the way a real one does.
 */
private class TrnWander(
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
 * raised-cosine attack and exponential decay. Every axle clack is one of these
 * (bridges add a second, high-Q one for the metallic ring). Per-slot filters
 * are what let the same population cover a 9 ms bright joint strike heard from
 * the vestibule and a 70 ms dark thud heard from a mile away.
 *
 * The envelope scales the filter's *input*, not its output — these filters are
 * retuned on every spawn, and a resonator still holding state from a 3 kHz ring
 * that is suddenly retuned to 300 Hz would ring its new mode at roughly
 * `state / sin(w0)`. Driving the input means the state decays with the envelope,
 * so every slot is quiet before it is reused. It is also the more physical
 * model: an impulsive excitation exciting a resonance.
 */
private class TrnClackPool(private val capacity: Int) {
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
        dec[slot] = exp(-6.9f / decaySamples.coerceAtLeast(16f))
        atkPos[slot] = 0
        atkLen[slot] = attackSamples.coerceAtLeast(1)
        gL[slot] = gainL
        gR[slot] = gainR
        bp[slot].bandpass(centerHz.coerceIn(40f, 14000f), q.coerceIn(0.3f, 14f))
    }

    /** Adds every live burst into [out] — does not clear it first. */
    fun add(noise: Float, out: FloatArray) {
        var l = 0f
        var r = 0f
        for (i in 0 until capacity) {
            if (!live[i]) continue
            val e = env[i]
            if (e < 0.00012f) {
                live[i] = false
                continue
            }
            var a = e
            val al = atkLen[i]
            if (atkPos[i] < al) {
                a *= TR_HANN[128 * atkPos[i] / al]
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
 * Pool of low decaying sines with a downward glide: the mass response of the
 * coach floor to an axle strike, and the pressure whump of a rush swell. A
 * clack without one of these is a click on a table; with one it is something
 * heavy moving underneath you.
 */
private class TrnBodyPool(private val capacity: Int) {
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
        inc[slot] = TR_TWO_PI * freq.coerceIn(18f, 400f) / SAMPLE_RATE
        gl[slot] = exp(ln(glideTotal.coerceIn(0.25f, 2f).toDouble()) / nd)
        env[slot] = amp
        dec[slot] = exp(-6.9f / nd)
        atkPos[slot] = 0
        atkLen[slot] = attackSamples.coerceAtLeast(4)
        gL[slot] = gainL
        gR[slot] = gainR
    }

    /** Adds every live body voice into [out] — does not clear it first. */
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
                a *= TR_HANN[128 * atkPos[i] / al]
                atkPos[i]++
            }
            ph[i] += inc[i]
            inc[i] *= gl[i]
            if (ph[i] > TR_TWO_PI) ph[i] -= TR_TWO_PI
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
// Night train
// ---------------------------------------------------------------------------

/**
 * **NightTrainV2** — see the file header for the model. Replaces `NightTrain`.
 *
 * Params:
 *  - `speed` 0..1 (0.5) — 12–38 m/s (43–137 km/h). Sets the joint-crossing
 *    tempo through the geometry, and scales drone brightness/level, rail
 *    shimmer and wind rush.
 *  - `distance` 0..1 (0.4) — inside the coach (0) to across the valley (1).
 *  - `rails` 0..1 (0.6) — fraction of rail joints that are joints: old bolted
 *    track (1) to modern welded rail (0).
 *
 * **Measured.** At defaults over 2 h: RMS 0.086, peak 0.64, the 300 ms window
 * level moving 5.8 dB (p2–p98), ten-minute chunk RMS staying inside
 * 0.083–0.089 (no drift), no NaN. Over 3 min: RMS 0.081, peak 0.45. Riding
 * inside at full speed reaches RMS 0.124 over half an hour; across the valley
 * it settles at 0.049 with the terrain undulation opening the window range to
 * 8.4 dB. For scale, over the same 2 h [RainV2] measures RMS 0.096 / peak 0.66,
 * [OceanV2] 0.102 / 0.74 and [CampfireV2] 0.080 / 0.58 — and the old
 * `NightTrain` measured 0.094 with a 1.1 dB window range: the same loudness,
 * and nothing ever happening.
 *
 * **Clack traffic** (jointed track, inside). At `speed` 0 the consist produces
 * 8.0 clacks/s, 1.6 of them from your own car, whose gaps run
 * 214 / 1367 / 214 / 700 ms; at 0.5 it is 16.7/s (3.4 own car) at
 * 103 / 650 / 103 / 336 ms; at 1 it is 25.4/s (5.1 own car) at
 * 68 / 428 / 68 / 220 ms. Only the last gap in each cycle carries jitter, and
 * it measures ±6 %. Sweeping `rails` down at the default speed takes the rate
 * from 16.7/s through 10.5/s at the 0.6 default to 0.32/s — which is not a
 * quieter clack but one *joint* every ~60 s that all twenty axles run over in
 * turn. About 11 % of strikes get a soft double.
 *
 * **Journey events** arrive every 120 s on average (measured over an hour:
 * 12 horns, 9 bridges, 9 curves), with a wind-rush swell every 43 s. The horn
 * lifts the loudest 100 ms window 3.2 dB above the bed from inside, 5.1 dB at
 * the default distance and 7.3 dB from across the valley — the one event that
 * gets more prominent the further away you are. A bridge lifts the median
 * level 1.0 dB for its 8–20 s and peaks 5 dB over. The flange moan sits about
 * 19 dB under the bed and disappears entirely at distance.
 *
 * **Cost.** 136x realtime at defaults on a desktop JVM (87x in the busiest
 * corner, inside at full speed on jointed track), against 70x for [RainV2] on
 * the same machine. Nothing allocates after construction; a full 60 s sweep of
 * all three sliders produces a largest sample-to-sample step of 0.164, below
 * the 0.230 of the brightest *static* setting — no zipper, no click.
 */
class NightTrainV2 : SoundGenerator {

    @Volatile private var speed = 0.5f
    @Volatile private var distance = 0.4f
    @Volatile private var rails = 0.6f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "speed" -> speed = value.coerceIn(0f, 1f)
            "distance" -> distance = value.coerceIn(0f, 1f)
            "rails" -> rails = value.coerceIn(0f, 1f)
        }
    }

    // Smoothed copies — render thread only.
    private var spdS = 0.5f
    private var distS = 0.4f
    private var railS = 0.6f

    private val rnd = Random(58201)

    // --- the consist ---------------------------------------------------------
    /** Metres behind the reference point for every simulated axle. */
    private val axleOff = DoubleArray(AXLES)

    /** Level of this axle heard from inside the coach, and from far away. */
    private val axleWNear = FloatArray(AXLES)
    private val axleWFar = FloatArray(AXLES)
    private val axleW = FloatArray(AXLES)

    /** Timbre scalers: a neighbouring car's clacks are darker ([axleTone]) and
     *  longer ([axleBlur]) — that is most of what "further down the train" is. */
    private val axleTone = FloatArray(AXLES)
    private val axleBlur = FloatArray(AXLES)

    /** Stable stereo geography — the front bogie is always in the same place. */
    private val axlePan = FloatArray(AXLES)
    private val axleOwn = BooleanArray(AXLES)

    private val nextIdx = IntArray(AXLES)
    private val nextPos = DoubleArray(AXLES)
    private var trainX = 0.0
    private var vStep = 25.0 / SAMPLE_RATE

    init {
        var k = 0
        for (c in -2..2) {
            val ac = if (c < 0) -c else c
            for (j in 0 until 4) {
                axleOff[k] = c * CAR_PITCH + AXLE_A[j]
                axleWNear[k] = when (ac) {
                    0 -> 1.00f
                    1 -> 0.34f
                    else -> 0.11f
                }
                axleWFar[k] = when (ac) {
                    0 -> 1.00f
                    1 -> 0.88f
                    else -> 0.74f
                }
                axleTone[k] = when (ac) {
                    0 -> 1.00f
                    1 -> 0.82f
                    else -> 0.68f
                }
                axleBlur[k] = when (ac) {
                    0 -> 1.00f
                    1 -> 1.35f
                    else -> 1.70f
                }
                axleOwn[k] = ac == 0
                val half = when (ac) {
                    0 -> 0.12f
                    1 -> 0.28f
                    else -> 0.38f
                }
                axlePan[k] = 0.5f + (trnHash(k, 91) * 2f - 1f) * half
                axleW[k] = axleWNear[k]
                k++
            }
        }
        for (i in 0 until AXLES) {
            val p = -axleOff[i]
            var n = floor(p / JOINT_M).toInt()
            while (jointPos(n) <= p) n++
            while (n > 0 && jointPos(n - 1) > p) n--
            nextIdx[i] = n
            nextPos[i] = jointPos(n)
        }
    }

    /**
     * Absolute position of rail joint [n]: nominal spacing plus a deterministic
     * ±0.45 m displacement, so consecutive spacings vary by up to ±3 %. Kept in
     * Double — eight hours at 140 km/h is a megametre of track, and Float would
     * be down to 6 cm of resolution there.
     */
    private fun jointPos(n: Int): Double =
        n * JOINT_M + JOINT_JIT * (trnHash(n, 17) * 2.0 - 1.0)

    // --- rolling drone -------------------------------------------------------
    private val rollBrL = BrownFilter()
    private val rollBrR = BrownFilter()
    private val rollPkL = PinkFilter()
    private val rollPkR = PinkFilter()
    private val rollLpL = Biquad().lowpass(800f, 0.72f)
    private val rollLpR = Biquad().lowpass(848f, 0.72f)
    private val bodyEqL = Biquad().peaking(80f, 1.15f, 0f)
    private val bodyEqR = Biquad().peaking(83f, 1.15f, 0f)
    private val hollowL = Biquad().peaking(150f, 2.4f, 0f)
    private val hollowR = Biquad().peaking(155f, 2.4f, 0f)

    private val trackTone = TrnWander(1301, 0.78f, 1.30f, 15f, 40f, 11f)
    private val trackLevel = TrnWander(1303, 0.80f, 1.22f, 15f, 40f, 10f)
    private val bodyTone = TrnWander(1307, 60f, 110f, 20f, 50f, 12f)
    private val speedWander = TrnWander(1319, 0f, 1f, 20f, 60f, 12f)

    // --- rail shimmer --------------------------------------------------------
    private val shimL = Biquad().bandpass(2800f, 1.4f)
    private val shimR = Biquad().bandpass(2940f, 1.4f)
    private val shimTone = TrnWander(1321, 2200f, 3800f, 8f, 20f, 6f)

    // --- coach sway ----------------------------------------------------------
    private val swayRate = TrnWander(1327, 0.25f, 0.60f, 8f, 22f, 5f)
    private var swayPhase1 = 0.0
    private var swayPhase2 = 1.7

    // --- wind rush -----------------------------------------------------------
    private val rushHpL = Biquad().highpass(290f, 0.7f)
    private val rushHpR = Biquad().highpass(300f, 0.7f)
    private val rushLpL = Biquad().lowpass(950f, 0.7f)
    private val rushLpR = Biquad().lowpass(990f, 0.7f)
    private val rushTone = TrnWander(1361, 620f, 1300f, 5f, 14f, 4f)
    private val rushLevel = TrnWander(1367, 0.78f, 1.24f, 6f, 16f, 5f)
    private var swellCountdown = (17f * SAMPLE_RATE).toInt()
    private var swellPos = 0
    private var swellLen = 0
    private var swellAmp = 0f
    private var swellEnv = 0f

    // --- clacks --------------------------------------------------------------
    private val clacks = TrnClackPool(28)
    private val bodies = TrnBodyPool(8)
    private val acc = FloatArray(2)
    private val clackTone = TrnWander(1373, 760f, 1900f, 5f, 14f, 4f)

    // Scheduled second halves of double-strikes (a stepped joint or a flat).
    private val dblLive = BooleanArray(4)
    private val dblWait = IntArray(4)
    private val dblAmp = FloatArray(4)
    private val dblCtr = FloatArray(4)
    private val dblDec = FloatArray(4)
    private val dblQ = FloatArray(4)
    private val dblPanL = FloatArray(4)
    private val dblPanR = FloatArray(4)

    // --- journey events ------------------------------------------------------
    private var eventCountdown = (38f * SAMPLE_RATE).toInt()

    // Horn.
    private val hornPh = DoubleArray(6)
    private val hornInc = DoubleArray(6)
    private val hornPAmp = FloatArray(6)
    private var hornPos = 0
    private var hornLen = 0
    private var hornAmp = 0f
    private var hornGlide = 1.0
    private var hornVibPh = 0.0
    private var hornVibInc = 0.0
    private var hornPanL = 0.7f
    private var hornPanR = 0.7f
    private val hornLp = Biquad().lowpass(760f, 0.72f)
    private val hornEcho = FloatArray(13697) // ≈ 311 ms — a valley, not a room
    private var hornEchoIdx = 0
    private var hornEchoLeft = 0
    private val hornEchoLp = OnePoleLp().setCutoff(820f)

    // Bridge / viaduct.
    private var bridgePos = 0
    private var bridgeLen = 0
    private var bridgeEnv = 0f
    private var bridgeF = 150f

    // Long curve.
    private val moanL = Biquad().bandpass(1600f, 17f)
    private val moanR = Biquad().bandpass(1648f, 17f)
    private val moanTone = TrnWander(1381, 1200f, 2000f, 3f, 9f, 2.5f)
    private var curvePos = 0
    private var curveLen = 0
    private var curveEnv = 0f
    private var moanTremPh = 0.0
    private var moanTremOff = 0.0
    private var moanTremInc = 0.0

    // --- far perspective -----------------------------------------------------
    private val farAL = Biquad().lowpass(4000f, 0.7f)
    private val farAR = Biquad().lowpass(4000f, 0.7f)
    private val farBL = Biquad().lowpass(4000f, 0.7f)
    private val farBR = Biquad().lowpass(4000f, 0.7f)
    private val farUndul = TrnWander(1399, 0.58f, 1.38f, 20f, 60f, 13f)

    // --- block-rate derived --------------------------------------------------
    private var blockCountdown = 0
    private var droneGainL = 0f
    private var droneGainR = 0f
    private var shimGain = 0f
    private var rushGain = 0f
    private var hollowDb = 0f
    private var moanGain = 0f
    private var hornEchoGain = 0f
    private var hornLevel = 1f
    private var jointProb = 0.6f
    private var clackAmp = 0f
    private var clackCentre = 1200f
    private var clackQv = 2.2f
    private var clackDecaySamp = 500f
    private var clackAtkSamp = 3
    private var bodyAmp = 0f
    private var widthCollapse = 0f
    private var outGain = 1f
    /** Half-depth sway, for the layers that should rock but not lead the rock. */
    private var swayHalf = 1f

    private fun updateBlock() {
        spdS += (speed - spdS) * 0.04f
        distS += (distance - distS) * 0.04f
        railS += (rails - railS) * 0.04f

        trackTone.step(TR_BLOCK)
        trackLevel.step(TR_BLOCK)
        bodyTone.step(TR_BLOCK)
        speedWander.step(TR_BLOCK)
        shimTone.step(TR_BLOCK)
        swayRate.step(TR_BLOCK)
        rushTone.step(TR_BLOCK)
        rushLevel.step(TR_BLOCK)
        clackTone.step(TR_BLOCK)
        moanTone.step(TR_BLOCK)
        farUndul.step(TR_BLOCK)

        val sp = spdS.coerceIn(0f, 1f)
        val di = distS.coerceIn(0f, 1f)
        val ra = railS.coerceIn(0f, 1f)

        // --- speed: metres per sample, drifting ±2.5 % like a real driver -----
        val v = (V_MIN + (V_MAX - V_MIN) * sp) * (0.975f + 0.050f * speedWander.value)
        vStep = v.toDouble() / SAMPLE_RATE

        // --- journey events ----------------------------------------------------
        stepEvents()

        // --- coach sway --------------------------------------------------------
        // One modulator, read at two phases: that gives level *and* image rock
        // from a single pair of oscillators.
        val si1 = TR_TWO_PI * swayRate.value * TR_BLOCK / SAMPLE_RATE
        swayPhase1 += si1
        swayPhase2 += si1 * 0.6137
        if (swayPhase1 > TR_TWO_PI) swayPhase1 -= TR_TWO_PI
        if (swayPhase2 > TR_TWO_PI) swayPhase2 -= TR_TWO_PI
        val sA = 0.62f * sin(swayPhase1).toFloat() + 0.38f * sin(swayPhase2).toFloat()
        val sB = 0.62f * sin(swayPhase1 + 0.55).toFloat() +
            0.38f * sin(swayPhase2 + 0.95).toFloat()
        val swayDepth = 0.175f * (1f - di)
        val swL = 1f + swayDepth * sA
        val swR = 1f + swayDepth * sB
        swayHalf = 1f + 0.5f * swayDepth * sA

        // --- rolling drone ------------------------------------------------------
        val fc = ((280f + 1000f * sp) * trackTone.value).coerceIn(140f, 4200f)
        rollLpL.lowpass(fc, 0.72f)
        rollLpR.lowpass((fc * 1.06f).coerceAtMost(4600f), 0.72f)
        val bf = bodyTone.value.coerceIn(50f, 130f)
        val bdb = (5.0f + 2.8f * sp) * (1f - 0.50f * di)
        bodyEqL.peaking(bf, 1.15f, bdb)
        bodyEqR.peaking(bf * 1.04f, 1.15f, bdb)
        hollowDb = 8.5f * bridgeEnv * (1f - 0.55f * di)
        if (hollowDb > 0.05f) {
            hollowL.peaking(bridgeF, 2.4f, hollowDb)
            hollowR.peaking(bridgeF * 1.03f, 2.4f, hollowDb)
        }
        // Level rises with speed but saturates: most of the speed cue lives in
        // the clack tempo and the opening cutoff, not in raw loudness.
        val droneBase = ROLL_GAIN * (0.80f + 0.06f * sp.pow(0.50f)) * trackLevel.value *
            (1f + 0.12f * bridgeEnv)
        droneGainL = droneBase * swL
        droneGainR = droneBase * swR

        // --- rail shimmer -------------------------------------------------------
        val st = shimTone.value
        shimL.bandpass(st, 1.4f)
        shimR.bandpass(st * 1.05f, 1.4f)
        // Ducked during a rush swell — the pressure change eats the top end.
        shimGain = SHIM_GAIN * sp.pow(1.7f) * (1f - 0.30f * di) *
            (1f - 0.35f * swellEnv) * swayHalf

        // --- wind rush ----------------------------------------------------------
        val rt = (rushTone.value * (0.85f + 0.42f * sp)).coerceIn(400f, 1900f)
        rushLpL.lowpass(rt, 0.7f)
        rushLpR.lowpass(rt * 1.05f, 0.7f)
        val rh = 250f + 95f * sp
        rushHpL.highpass(rh, 0.7f)
        rushHpR.highpass(rh * 1.04f, 0.7f)
        rushGain = RUSH_GAIN * sp.pow(0.95f) * (1f - di).pow(1.4f) *
            rushLevel.value * (1f + 1.05f * swellEnv) * swL

        // --- clacks --------------------------------------------------------------
        // `rails` is the fraction of joints that exist; the floor leaves welded
        // track with the occasional insulated joint the whole train runs over.
        jointProb = 0.035f + 0.965f * ra.pow(1.30f)
        // The per-strike level falls slightly as the train speeds up: twice the
        // joint rate at the same amplitude would pile the clacks into a rattle.
        clackAmp = CLACK_GAIN * (0.42f + 0.58f * ra) * (1f - 0.24f * sp) *
            (1f - 0.30f * di) * swayHalf
        clackCentre = (clackTone.value * (0.85f + 0.35f * sp) * (1f - 0.60f * di))
            .coerceIn(150f, 3000f)
        // Low Q on purpose: a rail joint is a broadband impact, not a bell. A
        // narrow band here is the single fastest way to make it read as "bonk".
        clackQv = 1.25f - 0.42f * di
        clackDecaySamp = (0.020f + 0.075f * di * di + 0.016f * (1f - sp)) * SAMPLE_RATE
        // A 1.7 ms raised-cosine onset rather than a hard edge. It costs almost
        // no energy but takes ~2.5 dB off the peak, which is the difference
        // between a clack you can sleep through and a click that wakes you.
        clackAtkSamp = ((0.0017f + 0.0075f * di) * SAMPLE_RATE).toInt()
        bodyAmp = BODY_GAIN * (1f - 0.45f * di) * (0.58f + 0.42f * sp)
        for (k in 0 until AXLES) axleW[k] = axleWNear[k] + (axleWFar[k] - axleWNear[k]) * di

        // --- flange moan ----------------------------------------------------------
        if (curveEnv > 0.002f) {
            val mt = moanTone.value
            moanL.bandpass(mt, 17f)
            moanR.bandpass(mt * 1.03f, 17f)
            moanTremPh += moanTremInc
            if (moanTremPh > TR_TWO_PI) moanTremPh -= TR_TWO_PI
            val trem = 0.62f + 0.38f * (0.5f + 0.5f * sin(moanTremPh + moanTremOff).toFloat())
            moanGain = MOAN_GAIN * curveEnv * trem * (1f - 0.70f * di) * (0.45f + 0.75f * sp)
        } else {
            moanGain = 0f
        }

        // --- far perspective -------------------------------------------------------
        val cut = exp(FAR_LN_HI + (FAR_LN_LO - FAR_LN_HI) * di)
        farAL.lowpass(cut, 0.7f)
        farAR.lowpass(cut * 1.02f, 0.7f)
        // The second pole sits above the first: 12 dB/octave overall, but with
        // enough 400–900 Hz left for the far clacks to still read as thuds.
        val cut2 = (cut * 1.55f).coerceAtMost(15000f)
        farBL.lowpass(cut2, 0.62f)
        farBR.lowpass(cut2 * 1.02f, 0.62f)
        // Collapsing two uncorrelated channels toward mono costs up to 3 dB of
        // power on its own; normalise it away so `distance` moves the image and
        // the tone, and the level drop stays an explicit, tunable decision.
        val w = 0.85f * di.pow(0.85f)
        widthCollapse = w
        val half = 0.5f * w
        val wn = 1f / sqrt((1f - half) * (1f - half) + half * half)
        outGain = wn * (1f - 0.34f * di) * (1f + di * (farUndul.value - 1f))
        // The one event that gets *louder* with distance. Inside the coach the
        // horn is a muffled honk through a bulkhead two cars away; from a
        // hillside at 3am it is the whole reason the sound exists, and the
        // valley echo comes up with it.
        hornLevel = 0.85f + 0.75f * di
        hornEchoGain = HORN_ECHO_GAIN * (0.45f + 0.65f * di)
    }

    /** Countdowns and envelopes for the three journey events. Block rate. */
    private fun stepEvents() {
        // Bridge.
        if (bridgePos < bridgeLen) {
            bridgePos += TR_BLOCK
            bridgeEnv = trnEdge(bridgePos, bridgeLen, BRIDGE_RAMP)
        } else {
            bridgeEnv = 0f
        }
        // Curve.
        if (curvePos < curveLen) {
            curvePos += TR_BLOCK
            curveEnv = trnEdge(curvePos, curveLen, CURVE_RAMP)
        } else {
            curveEnv = 0f
        }
        // Rush swell — separate, more frequent, and inside-only in effect.
        if (swellPos < swellLen) {
            swellPos += TR_BLOCK
            swellEnv = trnEdge(swellPos, swellLen, swellLen / 3) * swellAmp
        } else {
            swellEnv = 0f
            swellCountdown -= TR_BLOCK
            if (swellCountdown <= 0) {
                swellCountdown = ((20f + rnd.nextFloat() * 40f) * SAMPLE_RATE).toInt()
                swellLen = ((2f + rnd.nextFloat() * 4f) * SAMPLE_RATE).toInt()
                swellPos = 0
                swellAmp = 0.45f + rnd.nextFloat() * 0.55f
                // The pressure whump: felt more than heard, and gone in half a second.
                val p = rnd.nextFloat() * TR_HALF_PI
                bodies.spawn(
                    freq = 34f + rnd.nextFloat() * 14f,
                    amp = WHUMP_GAIN * swellAmp * (1f - distS.coerceIn(0f, 1f)),
                    decaySamples = (0.30f + rnd.nextFloat() * 0.25f) * SAMPLE_RATE,
                    glideTotal = 0.80f,
                    attackSamples = (0.05f * SAMPLE_RATE).toInt(),
                    gainL = cos(p),
                    gainR = sin(p),
                )
            }
        }
        // One journey event every 60–180 s.
        eventCountdown -= TR_BLOCK
        if (eventCountdown <= 0) {
            eventCountdown = ((60f + rnd.nextFloat() * 120f) * SAMPLE_RATE).toInt()
            when (rnd.nextInt(3)) {
                0 -> fireHorn()
                1 -> if (bridgePos >= bridgeLen) {
                    bridgeLen = ((8f + rnd.nextFloat() * 12f) * SAMPLE_RATE).toInt()
                    bridgePos = 0
                    bridgeF = 128f + rnd.nextFloat() * 62f
                }
                else -> if (curvePos >= curveLen) {
                    curveLen = ((10f + rnd.nextFloat() * 15f) * SAMPLE_RATE).toInt()
                    curvePos = 0
                    moanTremOff = rnd.nextDouble() * TR_TWO_PI
                    // Each curve gets its own slow waver rate — nothing here repeats.
                    moanTremInc = TR_TWO_PI * (0.7 + rnd.nextDouble() * 0.9) * TR_BLOCK / SAMPLE_RATE
                }
            }
        }
    }

    /**
     * A two-note horn: a minor third around 310 Hz, three partials each,
     * lowpassed hard, with a slow droop and a valley echo. Louder the further
     * away you are — inside the coach the horn is two cars ahead of you and
     * mostly behind a bulkhead; from a hillside it is the only thing you hear.
     */
    private fun fireHorn() {
        if (hornPos < hornLen) return
        hornLen = ((1.5f + rnd.nextFloat() * 1.0f) * SAMPLE_RATE).toInt()
        hornPos = 0
        val f1 = 300f + rnd.nextFloat() * 22f
        val f2 = f1 * (1.175f + rnd.nextFloat() * 0.030f)
        val base = floatArrayOf(1.00f, 0.34f, 0.13f)
        for (h in 0 until 3) {
            hornPh[h] = rnd.nextDouble() * TR_TWO_PI
            hornInc[h] = TR_TWO_PI * f1 * (h + 1) / SAMPLE_RATE
            hornPAmp[h] = base[h]
            hornPh[h + 3] = rnd.nextDouble() * TR_TWO_PI
            hornInc[h + 3] = TR_TWO_PI * f2 * (h + 1) / SAMPLE_RATE
            hornPAmp[h + 3] = base[h] * 0.86f
        }
        hornGlide = exp(ln(0.986) / hornLen)
        hornVibPh = 0.0
        hornVibInc = TR_TWO_PI * (4.2 + rnd.nextDouble() * 1.8) / SAMPLE_RATE
        hornAmp = HORN_GAIN * (0.55f + rnd.nextFloat() * 0.45f)
        val p = (0.15f + rnd.nextFloat() * 0.70f) * TR_HALF_PI
        hornPanL = cos(p)
        hornPanR = sin(p)
        hornLp.lowpass(640f + rnd.nextFloat() * 240f, 0.72f)
        // Eight laps of the delay line after the note stops: at 0.42 feedback
        // through a lowpass that is ~-70 dB, so the tail is silent long before
        // the gate closes on it.
        hornEchoLeft = hornLen + hornEcho.size * 8
    }

    /** One axle passing joint [n]. */
    private fun fireClack(k: Int, n: Int) {
        if (trnHash(n, 3) >= jointProb) return // welded here
        val w = axleW[k]
        val amp = clackAmp * w * (0.71f + 0.70f * rnd.nextFloat()) // ±3 dB
        if (amp < 0.0015f) return
        val pan = (axlePan[k] + rnd.tnz() * 0.03f).coerceIn(0.03f, 0.97f) * TR_HALF_PI
        val gl = cos(pan)
        val gr = sin(pan)
        val c = clackCentre * axleTone[k] * (0.80f + 0.42f * rnd.nextFloat())
        val q = clackQv * (0.80f + 0.55f * rnd.nextFloat())
        val dc = clackDecaySamp * axleBlur[k] * (0.75f + 0.55f * rnd.nextFloat())
        clacks.spawn(amp, dc, clackAtkSamp, c, q, gl, gr)

        if (bridgeEnv > 0.02f) {
            // The deck rings: a high-Q partial riding on every strike.
            clacks.spawn(
                amp = amp * 0.24f * bridgeEnv,
                decaySamples = dc * 2.6f,
                attackSamples = clackAtkSamp,
                centerHz = 1800f + rnd.nextFloat() * 1700f,
                q = 7f + rnd.nextFloat() * 5f,
                gainL = gl,
                gainR = gr,
            )
        }
        if (axleOwn[k] && bodyAmp > 0.002f) {
            bodies.spawn(
                freq = 58f + rnd.nextFloat() * 34f,
                amp = bodyAmp * (0.60f + 0.55f * rnd.nextFloat()),
                decaySamples = (0.045f + rnd.nextFloat() * 0.055f) * SAMPLE_RATE,
                glideTotal = 0.82f,
                attackSamples = (0.003f * SAMPLE_RATE).toInt(),
                gainL = gl,
                gainR = gr,
            )
        }
        // Soft double-strike: a stepped joint or a wheel flat. The gap is a
        // physical distance, so it shortens as the train speeds up.
        if (rnd.nextFloat() < 0.11f) {
            for (s in 0 until 4) {
                if (dblLive[s]) continue
                dblLive[s] = true
                val gap = (0.35f + rnd.nextFloat() * 0.55f).toDouble() // metres
                dblWait[s] = (gap / vStep).toInt().coerceIn(160, 4000)
                dblAmp[s] = amp * (0.30f + rnd.nextFloat() * 0.28f)
                dblCtr[s] = c * (0.86f + rnd.nextFloat() * 0.22f)
                dblDec[s] = dc * (0.6f + rnd.nextFloat() * 0.5f)
                dblQ[s] = q
                dblPanL[s] = gl
                dblPanR[s] = gr
                break
            }
        }
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--blockCountdown <= 0) {
                blockCountdown = TR_BLOCK
                updateBlock()
            }

            // --- the consist rolls forward ------------------------------------
            trainX += vStep
            for (k in 0 until AXLES) {
                if (trainX - axleOff[k] >= nextPos[k]) {
                    val n = nextIdx[k]
                    nextIdx[k] = n + 1
                    nextPos[k] = jointPos(n + 1)
                    fireClack(k, n)
                }
            }
            for (s in 0 until 4) {
                if (!dblLive[s]) continue
                if (--dblWait[s] <= 0) {
                    dblLive[s] = false
                    clacks.spawn(
                        dblAmp[s], dblDec[s], clackAtkSamp, dblCtr[s], dblQ[s],
                        dblPanL[s], dblPanR[s],
                    )
                }
            }

            // --- rolling drone --------------------------------------------------
            var dl = rollBrL.next(rnd.tnz()) * 0.86f + rollPkL.next(rnd.tnz()) * 0.52f
            var dr = rollBrR.next(rnd.tnz()) * 0.86f + rollPkR.next(rnd.tnz()) * 0.52f
            dl = bodyEqL.process(rollLpL.process(dl))
            dr = bodyEqR.process(rollLpR.process(dr))
            if (hollowDb > 0.05f) {
                dl = hollowL.process(dl)
                dr = hollowR.process(dr)
            }
            var l = dl * droneGainL
            var r = dr * droneGainR

            // --- rail shimmer ----------------------------------------------------
            if (shimGain > 1e-5f) {
                l += shimL.process(rnd.tnz()) * shimGain
                r += shimR.process(rnd.tnz()) * shimGain
            }

            // --- wind rush --------------------------------------------------------
            if (rushGain > 1e-5f) {
                l += rushLpL.process(rushHpL.process(rnd.tnz())) * rushGain
                r += rushLpR.process(rushHpR.process(rnd.tnz())) * rushGain
            }

            // --- clacks and coach body ---------------------------------------------
            acc[0] = 0f
            acc[1] = 0f
            clacks.add(rnd.tnz(), acc)
            bodies.add(acc)
            l += acc[0]
            r += acc[1]

            // --- flange moan --------------------------------------------------------
            if (moanGain > 1e-6f) {
                l += moanL.process(rnd.tnz()) * moanGain
                r += moanR.process(rnd.tnz()) * moanGain
            }

            // --- horn ----------------------------------------------------------------
            if (hornEchoLeft > 0) {
                var dry = 0f
                if (hornPos < hornLen) {
                    var s = 0f
                    for (h in 0 until 6) {
                        hornPh[h] += hornInc[h]
                        hornInc[h] *= hornGlide
                        if (hornPh[h] > TR_TWO_PI) hornPh[h] -= TR_TWO_PI
                        s += sin(hornPh[h]).toFloat() * hornPAmp[h]
                    }
                    hornVibPh += hornVibInc
                    if (hornVibPh > TR_TWO_PI) hornVibPh -= TR_TWO_PI
                    val a = trnEdge(hornPos, hornLen, HORN_RAMP) *
                        (1f + 0.055f * sin(hornVibPh).toFloat())
                    dry = hornLp.process(s * a) * hornAmp * hornLevel
                    hornPos++
                }
                val d = hornEcho[hornEchoIdx]
                hornEcho[hornEchoIdx] = hornEchoLp.process(dry + 0.42f * d)
                if (++hornEchoIdx >= hornEcho.size) hornEchoIdx = 0
                hornEchoLeft--
                val wet = d * hornEchoGain
                // The echo comes back from the far side of the image.
                l += dry * hornPanL + wet * hornPanR
                r += dry * hornPanR + wet * hornPanL
            }

            // --- perspective -----------------------------------------------------------
            val mid = 0.5f * (l + r)
            val cl = l + (mid - l) * widthCollapse
            val cr = r + (mid - r) * widthCollapse
            left[i] = trnCeilClip(farBL.process(farAL.process(cl)) * outGain)
            right[i] = trnCeilClip(farBR.process(farAR.process(cr)) * outGain)
        }
    }

    private companion object {
        // --- consist geometry (metres) -----------------------------------------
        const val AXLES = 20
        const val CAR_PITCH = 26.6      // 26 m body + coupling
        const val JOINT_M = 30.0        // European jointed rail
        const val JOINT_JIT = 0.45      // → up to ±3 % spacing jitter
        val AXLE_A = doubleArrayOf(2.2, 4.8, 21.2, 23.8) // bogies 19 m apart, 2.6 m base

        // --- speed --------------------------------------------------------------
        const val V_MIN = 12f           // 43 km/h
        const val V_MAX = 38f           // 137 km/h

        // --- event shaping --------------------------------------------------------
        val BRIDGE_RAMP = (0.55f * SAMPLE_RATE).toInt()
        val CURVE_RAMP = (2.4f * SAMPLE_RATE).toInt()
        val HORN_RAMP = (0.30f * SAMPLE_RATE).toInt()

        // --- perspective ------------------------------------------------------------
        val FAR_LN_HI = ln(12000f)
        val FAR_LN_LO = ln(380f)

        // --- levels --------------------------------------------------------------------
        const val ROLL_GAIN = 0.55f
        const val SHIM_GAIN = 0.060f
        const val RUSH_GAIN = 0.20f
        const val CLACK_GAIN = 1.50f
        const val BODY_GAIN = 0.30f
        const val WHUMP_GAIN = 0.22f
        const val MOAN_GAIN = 0.28f
        const val HORN_GAIN = 0.16f
        const val HORN_ECHO_GAIN = 0.55f
    }
}
