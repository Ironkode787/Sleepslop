package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * **BirdsongV2** — a dawn chorus that no longer whistles like a synthesizer.
 *
 * The old element was honest about one thing: songbird syrinx output *is*
 * nearly a pure swept tone. But four things separate a bird from a sine
 * sweep, and it had none of them:
 *
 *  1. **Curved contours.** Real notes bend — they rise and hook over, or dive
 *     and level out. Every note here follows a quadratic Bézier in
 *     log-frequency (start / control / end), so sweeps are curved and "hook"
 *     notes (up-then-down) exist at all. Straight exponential glides are the
 *     single biggest tell of fake birdsong.
 *  2. **A living voice.** The tone carries envelope-tracked 2nd/3rd
 *     harmonics (louder = brighter, exactly how a syrinx overblows), a
 *     breathy band of noise around the note, and a ±0.5 % random-walk pitch
 *     jitter. Perfectly stable pitch is the second biggest tell.
 *  3. **Species.** Three archetypes, assigned round-robin so the chorus is
 *     always mixed:
 *       - *whistler* (robin/blackbird-like): 1.9–3.3 kHz, unhurried 90–220 ms
 *         flutey notes with strong harmonics and a slow amplitude tremble,
 *         melodic phrases drawn from a per-bird set of 3–5 pitches;
 *       - *chipper* (sparrow/finch-like): 3–5 kHz, fast 30–70 ms chips,
 *         mostly diving or hooked, often a whole phrase of one repeated chip;
 *       - *triller* (wren-like): a couple of intro chips, then a long
 *         16–30 Hz trill.
 *  4. **Air.** Birds are heard outdoors, across distance. A built-in stereo
 *     cross-feedback echo (113/151 ms, darkened repeats) sits behind the
 *     voices; far birds are quieter, duller *and wetter*, near birds mostly
 *     dry. This is what puts the chorus in a landscape instead of a lab.
 *
 * What survives from V1 — because it was right — is the repertoire: each
 * bird owns 2–3 phrase patterns generated from its seed and repeats them
 * with mutation ("variety") rather than improvising freshly each time.
 *
 * Params: "activity" 0..1 (voice count 2–5 and phrase rate),
 * "variety" 0..1 (mutation depth). Doubles as the wake-up alarm bed.
 */
class BirdsongV2 : SoundGenerator {

    @Volatile private var activity = 0.5f
    @Volatile private var variety = 0.5f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "activity" -> activity = value.coerceIn(0f, 1f)
            "variety" -> variety = value.coerceIn(0f, 1f)
        }
    }

    private inner class Bird(seed: Int, val species: Int) {
        private val r = Random(seed)
        private val patterns = 2 + r.nextInt(2)

        // Repertoire (generated once, never mutated). Frequencies stored as ln(Hz).
        private val pLen = IntArray(MAX_PAT)
        private val pType = Array(MAX_PAT) { IntArray(MAX_CHIRP) }   // 0 sweep, 1 hook, 2 trill
        private val pF0 = Array(MAX_PAT) { FloatArray(MAX_CHIRP) }
        private val pFc = Array(MAX_PAT) { FloatArray(MAX_CHIRP) }
        private val pF1 = Array(MAX_PAT) { FloatArray(MAX_CHIRP) }
        private val pDur = Array(MAX_PAT) { FloatArray(MAX_CHIRP) }  // seconds
        private val pGap = Array(MAX_PAT) { FloatArray(MAX_CHIRP) }

        // The phrase currently being sung (a mutated copy of one pattern).
        private val cType = IntArray(MAX_CHIRP)
        private val cF0 = FloatArray(MAX_CHIRP)
        private val cFc = FloatArray(MAX_CHIRP)
        private val cF1 = FloatArray(MAX_CHIRP)
        private val cDur = IntArray(MAX_CHIRP)
        private val cGap = IntArray(MAX_CHIRP)
        private var cLen = 0
        private var patIdx = 0

        // Voice character.
        private val h2: Float
        private val h3: Float
        private val breathAmp: Float
        private val attF: Float
        private val relF: Float
        private val trembleDepth: Float
        private val amp: Float
        private val gainL: Float
        private val gainR: Float
        private val sendGain: Float
        private val lp = Biquad()
        private val breathBp = Biquad().bandpass(3000f, 4f)

        // Singing state.
        private var singing = false
        private var gapTimer: Int
        private var chirpIdx = 0
        private var chirpPos = 0
        private var phase = 0.0
        private var trillPhase = 0.0
        private var trillInc = 0.0
        private var tremPhase = 0.0
        private var tremInc = 0.0
        private var jit = 0f
        private var jitTarget = 0f
        private var jitCount = 0

        init {
            val centre: Float
            when (species) {
                0 -> { // whistler
                    centre = 1900f + r.nextFloat() * 900f
                    h2 = 0.26f + r.nextFloat() * 0.10f
                    h3 = 0.08f + r.nextFloat() * 0.05f
                    breathAmp = 0.05f
                    attF = 0.22f; relF = 0.32f
                    trembleDepth = 0.10f + r.nextFloat() * 0.08f
                }
                1 -> { // chipper
                    centre = 3100f + r.nextFloat() * 1400f
                    h2 = 0.10f + r.nextFloat() * 0.06f
                    h3 = 0.03f
                    breathAmp = 0.10f
                    attF = 0.10f; relF = 0.24f
                    trembleDepth = 0f
                }
                else -> { // triller
                    centre = 2700f + r.nextFloat() * 1300f
                    h2 = 0.14f + r.nextFloat() * 0.06f
                    h3 = 0.05f
                    breathAmp = 0.07f
                    attF = 0.14f; relF = 0.24f
                    trembleDepth = 0f
                }
            }

            // Whistlers sing melodies: a small per-bird set of pitches.
            val scale = FloatArray(5) { ln(centre * (0.82f + 0.17f * it) ) }

            for (p in 0 until patterns) {
                var n: Int
                when (species) {
                    0 -> n = 2 + r.nextInt(4)                        // 2..5 notes
                    1 -> n = 4 + r.nextInt(6)                        // 4..9 chips
                    else -> n = 2 + r.nextInt(3)                     // chips + trills
                }
                pLen[p] = n
                // Chippers often repeat one chip for the whole phrase.
                val series = species == 1 && r.nextFloat() < 0.55f
                for (c in 0 until n) {
                    if (series && c > 0) {
                        pType[p][c] = pType[p][0]
                        pF0[p][c] = pF0[p][0]
                        pFc[p][c] = pFc[p][0]
                        pF1[p][c] = pF1[p][0]
                        pDur[p][c] = pDur[p][0]
                        pGap[p][c] = pGap[p][0]
                        continue
                    }
                    when (species) {
                        0 -> {
                            val lf = scale[r.nextInt(5)]
                            val span = 0.06f + r.nextFloat() * 0.16f  // ln-ratio
                            val hook = r.nextFloat() < 0.45f
                            pType[p][c] = if (hook) 1 else 0
                            if (hook) {
                                // rise over the top and settle
                                pF0[p][c] = lf
                                pFc[p][c] = lf + span * 1.7f
                                pF1[p][c] = lf + span * (0.1f + r.nextFloat() * 0.5f)
                            } else if (r.nextBoolean()) {
                                pF0[p][c] = lf; pF1[p][c] = lf + span
                                pFc[p][c] = lf + span * (0.2f + r.nextFloat() * 0.6f)
                            } else {
                                pF0[p][c] = lf + span; pF1[p][c] = lf
                                pFc[p][c] = lf + span * (0.4f + r.nextFloat() * 0.6f)
                            }
                            pDur[p][c] = 0.09f + r.nextFloat() * 0.13f
                            pGap[p][c] = 0.07f + r.nextFloat() * 0.13f
                        }
                        1 -> {
                            val lf = ln((centre * (0.85f + r.nextFloat() * 0.35f)).coerceIn(2600f, 5600f))
                            val span = 0.10f + r.nextFloat() * 0.22f
                            val hook = r.nextFloat() < 0.35f
                            pType[p][c] = if (hook) 1 else 0
                            if (hook) {
                                pF0[p][c] = lf - span * 0.3f
                                pFc[p][c] = lf + span
                                pF1[p][c] = lf - span * 0.6f
                            } else {
                                // chips mostly dive
                                pF0[p][c] = lf + span
                                pF1[p][c] = lf - span * 0.4f
                                pFc[p][c] = lf + span * 0.5f
                            }
                            pDur[p][c] = 0.028f + r.nextFloat() * 0.042f
                            pGap[p][c] = 0.030f + r.nextFloat() * 0.080f
                        }
                        else -> {
                            val trill = c == n - 1 || r.nextFloat() < 0.35f
                            if (trill) {
                                val lf = ln((centre * (0.9f + r.nextFloat() * 0.2f)).coerceIn(2200f, 4800f))
                                pType[p][c] = 2
                                pF0[p][c] = lf
                                pF1[p][c] = lf + 0.06f + r.nextFloat() * 0.13f
                                pFc[p][c] = 16f + r.nextFloat() * 14f    // trill rate, Hz
                                pDur[p][c] = 0.20f + r.nextFloat() * 0.25f
                                pGap[p][c] = 0.06f + r.nextFloat() * 0.10f
                            } else {
                                val lf = ln((centre * (0.9f + r.nextFloat() * 0.3f)).coerceIn(2200f, 5000f))
                                val span = 0.10f + r.nextFloat() * 0.15f
                                pType[p][c] = 0
                                pF0[p][c] = lf + span
                                pF1[p][c] = lf - span * 0.3f
                                pFc[p][c] = lf + span * 0.4f
                                pDur[p][c] = 0.035f + r.nextFloat() * 0.045f
                                pGap[p][c] = 0.05f + r.nextFloat() * 0.09f
                            }
                        }
                    }
                }
            }

            // Placement: near birds louder, brighter, drier.
            val near = r.nextFloat()
            lp.lowpass(4500f + 7000f * near)
            amp = 0.17f + 0.13f * near
            sendGain = 0.16f + 0.34f * (1f - near)
            val a = (0.10f + r.nextFloat() * 0.80f) * PAN_Q
            gainL = cos(a)
            gainR = sin(a)
            gapTimer = (r.nextFloat() * 4f * SAMPLE_RATE).toInt() + 1
        }

        private fun startChirp() {
            chirpPos = 0
            phase = 0.0
            if (cType[chirpIdx] == 2) {
                trillPhase = 0.0
                trillInc = TAU * cFc[chirpIdx] / SAMPLE_RATE
            }
            tremPhase = 0.0
            tremInc = TAU * (4.5 + r.nextDouble() * 2.5) / SAMPLE_RATE
            // Breath band sits on the note's centre.
            val lfMid = if (cType[chirpIdx] == 2) {
                0.5f * (cF0[chirpIdx] + cF1[chirpIdx])
            } else {
                0.5f * (cF0[chirpIdx] + cF1[chirpIdx])
            }
            breathBp.bandpass(exp(lfMid).coerceIn(1500f, 6500f), 4f)
        }

        private fun startPhrase(v: Float, act: Float) {
            if (r.nextFloat() < 0.25f + 0.40f * v) patIdx = r.nextInt(patterns)
            val src = pLen[patIdx]
            var n = src
            if (r.nextFloat() < 0.25f * v) {
                val step = if (r.nextBoolean()) 1 else -1
                n = (n + step).coerceIn(2, MAX_CHIRP)
            }
            cLen = n
            for (c in 0 until n) {
                val k = if (c < src) c else src - 1
                cType[c] = pType[patIdx][k]
                val jf = 0.05f * v * r.nz()                  // ln-space pitch jitter
                cF0[c] = pF0[patIdx][k] + jf
                cF1[c] = pF1[patIdx][k] + jf
                cFc[c] = if (cType[c] == 2) pFc[patIdx][k] else pFc[patIdx][k] + jf
                cDur[c] = (pDur[patIdx][k] * (1f + 0.25f * v * r.nz()) * SAMPLE_RATE)
                    .toInt().coerceIn(1100, 22000)
                cGap[c] = (pGap[patIdx][k] * (1f + 0.35f * v * r.nz()) * SAMPLE_RATE)
                    .toInt().coerceIn(400, 12000)
            }
            chirpIdx = 0
            startChirp()
            if (act > 0.9f) cGap[0] = (cGap[0] * 0.85f).toInt().coerceAtLeast(400)
        }

        /** Adds this bird's dry signal to [out] and its echo send to [send]. */
        fun sample(out: FloatArray, send: FloatArray, act: Float, v: Float) {
            var raw = 0f
            if (singing) {
                val n = cDur[chirpIdx]
                if (chirpPos < n) {
                    val t = chirpPos.toFloat() / n

                    // Envelope: raised-cosine attack/release, species-shaped.
                    var e = when {
                        t < attF -> 0.5f - 0.5f * cos(PI_F * t / attF)
                        t > 1f - relF -> 0.5f - 0.5f * cos(PI_F * (1f - t) / relF)
                        else -> 1f
                    }
                    if (trembleDepth > 0f) {
                        tremPhase += tremInc
                        e *= 1f + trembleDepth * sin(tremPhase).toFloat()
                    }

                    // Pitch contour.
                    val lf: Float
                    if (cType[chirpIdx] == 2) {
                        trillPhase += trillInc
                        if (trillPhase > TAU) trillPhase -= TAU
                        val m = 0.5f + 0.5f * sin(trillPhase).toFloat()
                        lf = cF0[chirpIdx] + (cF1[chirpIdx] - cF0[chirpIdx]) * m
                    } else {
                        val u = 1f - t
                        lf = u * u * cF0[chirpIdx] +
                            2f * u * t * cFc[chirpIdx] +
                            t * t * cF1[chirpIdx]
                    }

                    // Micro-jitter: a ±0.5 % random-walk detune.
                    if (--jitCount <= 0) {
                        jitCount = 96
                        jitTarget = r.nz()
                    }
                    jit += (jitTarget - jit) * 0.05f
                    val f = exp(lf) * (1f + 0.005f * jit)

                    phase += TAU * f / SAMPLE_RATE
                    if (phase > TAU) phase -= TAU
                    val p = phase
                    // Envelope-tracked brightness: quiet = flutier, loud = richer.
                    val tone = sin(p).toFloat() +
                        sin(2 * p).toFloat() * h2 * (0.35f + 0.65f * e) +
                        sin(3 * p).toFloat() * h3 * e
                    val breath = breathBp.process(r.nz()) * breathAmp
                    raw = (tone + breath) * e * amp
                    chirpPos++
                } else if (chirpPos < n + cGap[chirpIdx]) {
                    chirpPos++
                } else {
                    chirpIdx++
                    if (chirpIdx >= cLen) {
                        singing = false
                        val gap = (1f + r.nextFloat() * 5f) * (1.45f - 0.90f * act)
                        gapTimer = (gap * SAMPLE_RATE).toInt().coerceAtLeast(4410)
                    } else {
                        startChirp()
                    }
                }
            } else if (--gapTimer <= 0) {
                singing = true
                startPhrase(v, act)
            }
            val s = lp.process(raw)
            out[0] += s * gainL
            out[1] += s * gainR
            send[0] += s * gainL * sendGain
            send[1] += s * gainR * sendGain
        }
    }

    // One triller, two whistlers, two chippers.
    private val birds = Array(MAX_BIRDS) { Bird(7700 + it * 311, it % 3) }
    private val acc = FloatArray(2)
    private val sendAcc = FloatArray(2)

    // Distant flock: the unresolvable rest of the dawn chorus, blurred into a
    // faint shimmering band with a fast irregular flutter. It is what keeps the
    // element from dropping to digital silence between phrases.
    private val flockBpL = Biquad().bandpass(3350f, 5f)
    private val flockBpR = Biquad().bandpass(3520f, 5f)
    private val flockRnd = Random(9219)
    private var flockFlut = 0.5f
    private var flockFlutT = 0.5f
    private var flockCount = 0

    // Outdoor air: a small stereo cross-feedback echo with darkened repeats.
    private val echoL = FloatArray(ECHO_LEN)
    private val echoR = FloatArray(ECHO_LEN)
    private var echoIdxL = 0
    private var echoIdxR = 0
    private val echoLpL = OnePoleLp().setCutoff(3400f)
    private val echoLpR = OnePoleLp().setCutoff(3100f)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val act = activity
        val v = variety
        val n = (2 + (act * 3f).roundToInt()).coerceIn(2, MAX_BIRDS)
        for (i in 0 until frames) {
            acc[0] = 0f
            acc[1] = 0f
            sendAcc[0] = 0f
            sendAcc[1] = 0f
            for (b in 0 until n) birds[b].sample(acc, sendAcc, act, v)

            // Distant flock shimmer.
            if (--flockCount <= 0) {
                flockCount = 640
                if (flockRnd.nextFloat() < 0.30f) flockFlutT = flockRnd.nextFloat()
            }
            flockFlut += (flockFlutT - flockFlut) * 0.0012f
            val fg = FLOCK_GAIN * (0.5f + 0.5f * act) * (0.45f + 0.9f * flockFlut)
            val fl = flockBpL.process(flockRnd.nz()) * fg
            val fr = flockBpR.process(flockRnd.nz()) * fg
            acc[0] += fl * 0.55f
            acc[1] += fr * 0.55f
            sendAcc[0] += fl
            sendAcc[1] += fr

            // Echo: two unequal delay lines, cross-coupled, darkened each pass.
            val eL = echoL[echoIdxL]
            val eR = echoR[echoIdxR]
            echoL[echoIdxL] = echoLpL.process(sendAcc[0] + eR * FEEDBACK)
            echoR[echoIdxR] = echoLpR.process(sendAcc[1] + eL * FEEDBACK)
            if (++echoIdxL >= DELAY_L) echoIdxL = 0
            if (++echoIdxR >= DELAY_R) echoIdxR = 0

            left[i] = birdLimit(acc[0] + eL)
            right[i] = birdLimit(acc[1] + eR)
        }
    }

    private companion object {
        const val MAX_BIRDS = 5
        const val MAX_PAT = 3
        const val MAX_CHIRP = 10
        const val FEEDBACK = 0.34f
        const val FLOCK_GAIN = 0.085f
        const val DELAY_L = 4983          // 113 ms
        const val DELAY_R = 6659          // 151 ms
        const val ECHO_LEN = 6659
        val TAU = 2.0 * PI
        val PI_F = PI.toFloat()
        val PAN_Q = (PI / 2).toFloat()
    }
}

private fun Random.nz(): Float = nextFloat() * 2f - 1f

/** Odd smooth soft ceiling (Padé of 0.5·tanh 2x), bounded to ±0.5. */
private fun birdLimit(x: Float): Float {
    val u = (x * 2f).coerceIn(-3f, 3f)
    val u2 = u * u
    return 0.5f * u * (27f + u2) / (27f + 9f * u2)
}
