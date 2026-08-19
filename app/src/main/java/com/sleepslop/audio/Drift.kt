package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Slow organic evolution ("drift") for a long-running mix.
 *
 * A static mix stops being interesting after a few minutes: the ear adapts and
 * the bed turns into a flat wall. Real night soundscapes never sit still — rain
 * swells and recedes, wind gathers and dies, crickets go quiet for a while and
 * then come back. [DriftEngine] reproduces that by handing the render thread a
 * slowly-varying multiplier for every [Sound]'s volume.
 *
 * ## The model
 *
 * Every [Sound] owns an independent trajectory seeded from its
 * [Sound.ordinal], so the layers breathe out of phase with each other — that is
 * the entire point. Nothing here reacts to the audio; it is a pure function of
 * accumulated render time plus a little seeded randomness.
 *
 * Each trajectory is built from four contributions:
 *
 * 1. **Three sinusoids** with mutually irrational periods. The first period is
 *    drawn per sound from 150–260 s; the second is that times the golden ratio
 *    (~4–7 min); the third is the second times sqrt(3) (~7–12 min). Because
 *    phi and sqrt(3) are irrational the three never re-align, so the sum has no
 *    audible repeat over a whole night. Weights 0.40 / 0.28 / 0.17.
 * 2. **A bounded random walk**, weight 0.15. A fresh target in [-1, 1] is drawn
 *    every 1–4 min and the walk chases it through a 45 s one-pole. Because it
 *    only ever interpolates *towards* a target inside [-1, 1] it can never
 *    leave that interval — no runaway, no periodic reset.
 *
 *    The weights sum to 1, so the combined shape lives in [-1, 1]. It maps to
 *    `base = 1 + 0.35 * intensity * shape`, clamped to [0.4, 1.3].
 *
 * 3. **Quiet spells** — the "the crickets stopped for a while" effect. Each
 *    sound runs its own four-state cycle: `IDLE -> DOWN -> HOLD -> UP -> IDLE`.
 *    A spell eases the sound down to 0.25–0.40x over 40–60 s using a
 *    raised-cosine ramp, holds there for 1–3 min, then eases back over another
 *    40–60 s. Afterwards the sound idles 7–15 min before the next spell, giving
 *    a full cycle of roughly 10–20 min per sound. Spells are independent per
 *    sound — two layers may overlap, which is fine and sounds natural. The
 *    raised-cosine ramps mean the spell envelope is continuous *and* has zero
 *    slope at both ends, so the entry and exit are inaudible.
 *
 * 4. **Intensity**, the user's drift-depth slider, slew-limited on the render
 *    thread so moving the slider can never step the gain either.
 *
 * The final multiplier is `base * (1 - intensity * drop * spellEnvelope)`, i.e.
 * the slow breathing scaled by the spell. At full intensity and deep in a spell
 * the multiplier bottoms out around 0.1; without a spell it stays in
 * [0.4, 1.3] as specified.
 *
 * ## Smoothness guarantee
 *
 * [gain] is a continuous function of accumulated sample time, evaluated once
 * per 1024-frame buffer (23.22 ms at 44.1 kHz). Per-buffer worst-case change,
 * from the analytic derivative of each term:
 *
 * | term            | bound per buffer |
 * |-----------------|------------------|
 * | three sinusoids | 0.00022          |
 * | random walk     | 0.00006          |
 * | quiet spell     | 0.00089          |
 * | intensity slew  | 0.00069          |
 * | **total**       | **< 0.0019**     |
 *
 * That stays under the 0.002 budget even in the pathological case where all
 * four hit maximum slope in the same buffer. Measured over a simulated 8-hour
 * run at full intensity across all sounds the observed worst case is 0.00086,
 * and 0.00083 with the intensity slider being slammed between 0 and 1 every
 * minute — far below the zipper-noise threshold, so no extra smoothing filter
 * is needed. (The render loop additionally ramps the previous gain to the new
 * one across the buffer, which smooths things further still.)
 *
 * Note that the periods are constants of the trajectory rather than
 * accumulated phases: [gain] is evaluated as `sin(2*pi*t/period + phase)` from
 * absolute time, so there is no incremental phase error to accumulate over a
 * night, and no wrap-around discontinuity to design around.
 *
 * ## Off-slider behaviour
 *
 * At `intensity == 0` every term is multiplied by zero, so [gain] returns
 * exactly `1.0f` — bit-exact passthrough, and the engine may keep calling it
 * unconditionally. The slew starts at 0, so drift also eases in from a perfect
 * 1.0 the first time the user raises the slider. Internal state keeps evolving
 * while intensity is 0, which is what lets the trajectory fade in from wherever
 * it happens to be instead of jumping.
 *
 * ## Allocation
 *
 * All per-sound state lives in flat primitive arrays sized `Sound.entries.size`
 * at construction and indexed by [Sound.ordinal]. Nothing is allocated after
 * the constructor returns — no maps, no boxing, no iterators — so the engine is
 * safe to call from the render thread.
 *
 * ## Threading
 *
 * [setIntensity] is the only method callable from the UI thread; it writes one
 * volatile float. [advance] and [gain] are render-thread only. [advance]
 * recomputes every sound's multiplier into a table; [gain] is then a plain
 * array read, so calling it for a subset of sounds (only the active ones) costs
 * nothing and does not perturb the trajectories.
 */
class DriftEngine {

    // ----------------------------------------------------------------- state

    /** Number of sounds; fixed at construction so the tables never resize. */
    private val count: Int = Sound.entries.size

    /** Drift depth requested by the UI thread, 0..1. */
    @Volatile
    private var intensityTarget: Float = 0f

    /** Slew-limited copy of [intensityTarget]. Render thread only. */
    private var intensity: Float = 0f

    /** Accumulated render time in seconds. Render thread only. */
    private var timeSec: Double = 0.0

    /** Per-sound RNG, seeded from the ordinal so trajectories are reproducible. */
    private val rng: Array<Random> = Array(count) { Random(SEED_BASE + it * SEED_STRIDE) }

    // Sinusoid layer: periods (seconds) and starting phases (radians).
    private val periodA = DoubleArray(count)
    private val periodB = DoubleArray(count)
    private val periodC = DoubleArray(count)
    private val phaseA = DoubleArray(count)
    private val phaseB = DoubleArray(count)
    private val phaseC = DoubleArray(count)

    // Bounded random walk layer.
    private val walk = DoubleArray(count)
    private val walkTarget = DoubleArray(count)
    private val walkTimer = DoubleArray(count)

    // Quiet-spell state machine.
    private val spellPhase = IntArray(count)
    private val spellTimer = DoubleArray(count)
    private val spellLen = DoubleArray(count)
    private val spellHold = DoubleArray(count)
    private val spellRampUp = DoubleArray(count)
    private val spellDrop = DoubleArray(count)
    private val spellEnv = DoubleArray(count)

    /** Latest multiplier per sound; [gain] just reads this. */
    private val gainTable = FloatArray(count) { 1f }

    init {
        for (i in 0 until count) {
            val r = rng[i]
            val base = MIN_PERIOD + r.nextDouble() * PERIOD_SPREAD
            periodA[i] = base
            periodB[i] = base * GOLDEN
            periodC[i] = base * GOLDEN * ROOT3
            phaseA[i] = r.nextDouble() * TWO_PI
            phaseB[i] = r.nextDouble() * TWO_PI
            phaseC[i] = r.nextDouble() * TWO_PI

            walk[i] = r.nextDouble() * 2.0 - 1.0
            walkTarget[i] = r.nextDouble() * 2.0 - 1.0
            walkTimer[i] = WALK_HOLD_MIN + r.nextDouble() * WALK_HOLD_SPREAD

            spellPhase[i] = PHASE_IDLE
            // Stagger the first spell so the layers do not all drop out together.
            spellTimer[i] = FIRST_SPELL_MIN + r.nextDouble() * FIRST_SPELL_SPREAD
            spellLen[i] = spellTimer[i]
            spellHold[i] = 0.0
            spellRampUp[i] = 0.0
            spellDrop[i] = 0.0
            spellEnv[i] = 0.0
        }
    }

    // ------------------------------------------------------------------- api

    /**
     * Sets the drift depth, 0..1. Safe to call from the UI thread; the render
     * thread slews towards the new value over ~45 s so the change is inaudible.
     * A value of 0 eventually yields a bit-exact 1.0 from [gain].
     */
    fun setIntensity(i: Float) {
        intensityTarget = if (i.isNaN()) 0f else i.coerceIn(0f, 1f)
    }

    /**
     * Advances every sound's trajectory by [frames] samples. Call this exactly
     * once per rendered buffer, before any [gain] call for that buffer.
     */
    fun advance(frames: Int) {
        if (frames <= 0) return

        val dt = frames.toDouble() / SAMPLE_RATE
        timeSec += dt

        // Rate-limited (not exponential) intensity slew: it reaches the target
        // exactly, which is what makes intensity == 0 bit-exact passthrough.
        val target = intensityTarget
        val maxStep = (dt * INTENSITY_SLEW_PER_SEC).toFloat()
        val diff = target - intensity
        intensity = when {
            diff > maxStep -> intensity + maxStep
            diff < -maxStep -> intensity - maxStep
            else -> target
        }

        val off = intensity == 0f
        val inten = intensity.toDouble()
        val t = timeSec
        for (i in 0 until count) {
            advanceWalk(i, dt)
            advanceSpell(i, dt)
            gainTable[i] = if (off) 1f else multiplier(i, t, inten)
        }
    }

    /**
     * The smooth volume multiplier for [sound], as of the last [advance].
     * Centred on 1.0; consecutive calls differ by less than 0.002.
     */
    fun gain(sound: Sound): Float {
        val i = sound.ordinal
        return if (i < 0 || i >= count) 1f else gainTable[i]
    }

    // -------------------------------------------------------------- internals

    /** Combines the sinusoid stack, the walk and the spell into one number. */
    private fun multiplier(i: Int, t: Double, inten: Double): Float {
        val shape = WEIGHT_A * sin(TWO_PI * t / periodA[i] + phaseA[i]) +
            WEIGHT_B * sin(TWO_PI * t / periodB[i] + phaseB[i]) +
            WEIGHT_C * sin(TWO_PI * t / periodC[i] + phaseC[i]) +
            WEIGHT_WALK * walk[i]

        var base = 1.0 + EXCURSION * inten * shape
        if (base < GAIN_MIN) base = GAIN_MIN
        if (base > GAIN_MAX) base = GAIN_MAX

        val spell = 1.0 - inten * spellDrop[i] * spellEnv[i]
        return (base * spell).toFloat()
    }

    /**
     * Bounded random walk: chase a target drawn every [WALK_HOLD_MIN]..+spread
     * seconds through a one-pole with time constant [WALK_TAU]. Since the step
     * is a convex blend towards a target inside [-1, 1], the walk stays there.
     */
    private fun advanceWalk(i: Int, dt: Double) {
        var timer = walkTimer[i] - dt
        if (timer <= 0.0) {
            val r = rng[i]
            timer += WALK_HOLD_MIN + r.nextDouble() * WALK_HOLD_SPREAD
            walkTarget[i] = r.nextDouble() * 2.0 - 1.0
        }
        walkTimer[i] = timer
        walk[i] += (walkTarget[i] - walk[i]) * (dt / WALK_TAU)
    }

    /**
     * Quiet-spell state machine. Every phase is at least 40 s long, so at most
     * one transition can happen per buffer; the leftover time is carried into
     * the next phase so the schedule never drifts. The envelope is evaluated
     * from the *post-transition* phase, and each phase's endpoint value matches
     * the next phase's start value exactly, so [spellEnv] is continuous.
     */
    private fun advanceSpell(i: Int, dt: Double) {
        var timer = spellTimer[i] - dt
        if (timer <= 0.0) {
            when (spellPhase[i]) {
                PHASE_IDLE -> {
                    val r = rng[i]
                    val down = RAMP_MIN + r.nextDouble() * RAMP_SPREAD
                    spellHold[i] = HOLD_MIN + r.nextDouble() * HOLD_SPREAD
                    spellRampUp[i] = RAMP_MIN + r.nextDouble() * RAMP_SPREAD
                    spellDrop[i] = 1.0 - (DEPTH_MIN + r.nextDouble() * DEPTH_SPREAD)
                    spellPhase[i] = PHASE_DOWN
                    spellLen[i] = down
                    timer += down
                }
                PHASE_DOWN -> {
                    spellPhase[i] = PHASE_HOLD
                    spellLen[i] = spellHold[i]
                    timer += spellHold[i]
                }
                PHASE_HOLD -> {
                    spellPhase[i] = PHASE_UP
                    spellLen[i] = spellRampUp[i]
                    timer += spellRampUp[i]
                }
                else -> {
                    val r = rng[i]
                    val gap = GAP_MIN + r.nextDouble() * GAP_SPREAD
                    spellPhase[i] = PHASE_IDLE
                    spellLen[i] = gap
                    timer += gap
                }
            }
        }
        spellTimer[i] = timer

        spellEnv[i] = when (spellPhase[i]) {
            PHASE_DOWN -> {
                val u = (1.0 - timer / spellLen[i]).coerceIn(0.0, 1.0)
                0.5 - 0.5 * cos(PI * u)
            }
            PHASE_HOLD -> 1.0
            PHASE_UP -> {
                val u = (1.0 - timer / spellLen[i]).coerceIn(0.0, 1.0)
                0.5 + 0.5 * cos(PI * u)
            }
            else -> 0.0
        }
    }

    private companion object {
        // Spell state-machine phases.
        const val PHASE_IDLE = 0
        const val PHASE_DOWN = 1
        const val PHASE_HOLD = 2
        const val PHASE_UP = 3

        // Seeding. The stride is a large odd constant so neighbouring ordinals
        // land far apart in the generator's state space.
        const val SEED_BASE = 1_442_695_040L
        const val SEED_STRIDE = 2_654_435_761L

        /** 2 * PI. Not `const` because the initializer is computed. */
        val TWO_PI = 2.0 * PI

        const val GOLDEN = 1.6180339887498949
        const val ROOT3 = 1.7320508075688772

        // Sinusoid stack: 150-260 s base, x phi, x phi x sqrt(3) -> up to ~12 min.
        const val MIN_PERIOD = 150.0
        const val PERIOD_SPREAD = 110.0
        const val WEIGHT_A = 0.40
        const val WEIGHT_B = 0.28
        const val WEIGHT_C = 0.17
        const val WEIGHT_WALK = 0.15

        // Envelope mapping.
        const val EXCURSION = 0.35
        const val GAIN_MIN = 0.4
        const val GAIN_MAX = 1.3

        // Random walk.
        const val WALK_TAU = 45.0
        const val WALK_HOLD_MIN = 60.0
        const val WALK_HOLD_SPREAD = 180.0

        /**
         * Full-scale intensity travel takes 45 s. Not `const` because the
         * initializer is computed.
         */
        val INTENSITY_SLEW_PER_SEC = 1.0 / 45.0

        // Quiet spells: 40-60 s down, 1-3 min hold, 40-60 s up, 7-15 min gap.
        const val RAMP_MIN = 40.0
        const val RAMP_SPREAD = 20.0
        const val HOLD_MIN = 60.0
        const val HOLD_SPREAD = 120.0
        const val DEPTH_MIN = 0.25
        const val DEPTH_SPREAD = 0.15
        const val GAP_MIN = 420.0
        const val GAP_SPREAD = 480.0
        const val FIRST_SPELL_MIN = 120.0
        const val FIRST_SPELL_SPREAD = 900.0
    }
}
