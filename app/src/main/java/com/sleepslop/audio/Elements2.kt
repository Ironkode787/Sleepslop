package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Second wave of ambient elements — heartbeat, purring cat, mantel clock,
 * cafe murmur, foghorn and a dawn chorus. Same rules as [Elements.kt]:
 * every voice is synthesized from noise and oscillators, nothing is sampled,
 * `render` overwrites its buffers, and parameter fields are written from the
 * UI thread while the render thread reads them (float writes are atomic on
 * the JVM, so @Volatile alone is enough).
 *
 * All state is preallocated in constructors; the render loops allocate
 * nothing. Biquads are retuned on control ticks (64–256 samples), never
 * per sample.
 */

private val TAU = 2.0 * PI
private val PAN_Q = (PI / 2).toFloat()

private fun Random.nz(): Float = nextFloat() * 2f - 1f

/** Per-sample exponential decay multiplier for a given time constant. */
private fun expDecayCoef(tauSec: Float): Float = exp(-1f / (tauSec * SAMPLE_RATE))

/**
 * Odd, smooth soft ceiling (Padé approximation of `0.5 * tanh(2x)`).
 * Transparent below ~0.2, hard-bounded to ±0.5 — it exists purely so a rare
 * alignment of independent voices can never spike the mix.
 */
private fun softLimit(x: Float): Float {
    val u = (x * 2f).coerceIn(-3f, 3f)
    val u2 = u * u
    return 0.5f * u * (27f + u2) / (27f + 9f * u2)
}

// ---------------------------------------------------------------------------

/**
 * Heartbeat.
 *
 * Acoustic model: heart sounds are valve closures, not tones — a short,
 * strongly damped thump whose pitch falls as the chest cavity absorbs the
 * energy. Each "lub" (S1, mitral/tricuspid closure) is a sine burst starting
 * near 85 Hz and gliding down to ~50 Hz across its ~120 ms exponential decay,
 * with a raised-cosine attack so there is no click. The "dub" (S2, aortic /
 * pulmonic closure) follows at ~0.30 of the beat period, quieter and a little
 * lower. A shared lowpass models tissue and bedding absorption; at high
 * "soft" values the cutoff drops, the attack lengthens and a slow lowpassed
 * brown-noise whoosh (placental/blood flow) fades in — the womb sound.
 * Beat-to-beat period jitter of ±2 % keeps it from sounding like a metronome.
 *
 * Params: "bpm" 45..80 (default 58), "soft" 0..1 (default 0.6).
 */
class HeartbeatElement : SoundGenerator {
    @Volatile private var bpm = 58f
    @Volatile private var soft = 0.6f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "bpm" -> bpm = value.coerceIn(45f, 80f)
            "soft" -> soft = value.coerceIn(0f, 1f)
        }
    }

    private class Thump {
        var active = false
        var pos = 0
        var attack = 1
        var len = 1
        var amp = 0f
        var body = 1f
        var decay = 0f
        var phase = 0.0
        var fLow = 50.0
        var fSpan = 35.0
        var glideState = 1.0
        var glide = 1.0
    }

    private val rnd = Random(613)
    private val thumps = Array(3) { Thump() }
    private val muffle = Biquad().lowpass(240f, 0.75f)
    private val whooshL = BrownFilter()
    private val whooshR = BrownFilter()
    private val whooshLpL = Biquad().lowpass(130f)
    private val whooshLpR = Biquad().lowpass(130f)

    private var beatTimer = (0.35f * SAMPLE_RATE).toInt()
    private var dubTimer = -1
    private var muffleFc = 240f
    private var retune = 0
    private var whooshPhase = 0.0
    private val whooshInc = TAU * 0.085 / SAMPLE_RATE

    private fun periodSamples(): Int {
        val beatsPerSec = bpm.coerceIn(45f, 80f) / 60f
        val jitter = 1f + 0.02f * rnd.nz()
        return (SAMPLE_RATE / beatsPerSec * jitter).toInt().coerceAtLeast(1024)
    }

    private fun fire(lub: Boolean) {
        var slot = -1
        for (i in thumps.indices) {
            if (!thumps[i].active) {
                slot = i
                break
            }
        }
        if (slot < 0) return
        val t = thumps[slot]
        val s = soft
        t.active = true
        t.pos = 0
        t.body = 1f
        t.phase = 0.0
        t.len = ((if (lub) 0.21f else 0.18f) * SAMPLE_RATE).toInt()
        t.attack = ((0.006f + 0.026f * s) * SAMPLE_RATE).toInt().coerceAtLeast(8)
        t.decay = expDecayCoef(if (lub) 0.050f else 0.043f)
        t.amp = (if (lub) 0.60f else 0.38f) * (0.94f + 0.12f * rnd.nextFloat())
        val fTop = if (lub) 85.0 else 76.0
        val fEnd = if (lub) 50.0 else 45.0
        t.fLow = fEnd
        t.fSpan = fTop - fEnd
        t.glideState = 1.0
        t.glide = exp(-1.0 / (0.045 * SAMPLE_RATE))
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val s = soft
        for (i in 0 until frames) {
            if (--retune <= 0) {
                retune = 128
                val target = 380f - 240f * s
                muffleFc += (target - muffleFc) * 0.08f
                muffle.lowpass(muffleFc, 0.75f)
            }
            if (--beatTimer <= 0) {
                beatTimer = periodSamples()
                fire(true)
                dubTimer = (beatTimer * 0.30f).toInt()
            }
            if (dubTimer > 0 && --dubTimer == 0) fire(false)

            var thump = 0f
            for (t in thumps) {
                if (!t.active) continue
                val e: Float
                if (t.pos < t.attack) {
                    e = 0.5f - 0.5f * cos(PI * t.pos / t.attack).toFloat()
                } else {
                    t.body *= t.decay
                    e = t.body
                }
                val f = t.fLow + t.fSpan * t.glideState
                t.glideState *= t.glide
                t.phase += TAU * f / SAMPLE_RATE
                if (t.phase > TAU) t.phase -= TAU
                thump += sin(t.phase).toFloat() * e * t.amp
                if (++t.pos >= t.len) t.active = false
            }
            val core = muffle.process(thump)

            whooshPhase += whooshInc
            if (whooshPhase > TAU) whooshPhase -= TAU
            val swell = 0.5f + 0.5f * sin(whooshPhase).toFloat()
            val wg = ((s - 0.5f) * 2f).coerceIn(0f, 1f) * 0.34f * (0.25f + 0.75f * swell)

            left[i] = softLimit(core + whooshLpL.process(whooshL.next(rnd.nz())) * wg)
            right[i] = softLimit(core + whooshLpR.process(whooshR.next(rnd.nz())) * wg)
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * Purring cat.
 *
 * Acoustic model: a purr is not a tone, it is a pulse train — the laryngeal
 * folds are driven open and shut 20–30 times a second, and each closure
 * radiates a 4–8 ms burst of lowpassed noise. What makes it read as *cat*
 * rather than as a buzz is the respiration cycle: the animal purrs on both
 * the inhale and the exhale, alternating every 1.1–1.6 s, with the exhale
 * slightly louder and about 10 % faster. The switch is crossfaded by a 1 Hz
 * one-pole so the two phases glide into each other.
 *
 * Three layers: gated noise bursts (the "edge" of each pulse), a continuous
 * brown-noise body amplitude-modulated by the same pulse envelope (chest
 * resonance), and a broadband breath layer that follows the respiration
 * envelope only. "breath" raises the breath layer and widens the pulse
 * bandwidth. Left and right run independent noise streams for natural width.
 *
 * Params: "rate" 20..32 Hz (default 25), "breath" 0..1 (default 0.5).
 */
class CatPurrElement : SoundGenerator {
    @Volatile private var rate = 25f
    @Volatile private var breath = 0.5f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value.coerceIn(12f, 40f)
            "breath" -> breath = value.coerceIn(0f, 1f)
        }
    }

    private val rnd = Random(1187)
    private val edgeL = Biquad().lowpass(790f, 0.8f)
    private val edgeR = Biquad().lowpass(790f, 0.8f)
    private val bodyLpL = Biquad().lowpass(170f)
    private val bodyLpR = Biquad().lowpass(170f)
    private val brownL = BrownFilter()
    private val brownR = BrownFilter()
    private val airL = Biquad().bandpass(1500f, 0.7f)
    private val airR = Biquad().bandpass(1500f, 0.7f)
    private val respLp = OnePoleLp().setCutoff(1.1f)

    private var exhaling = true
    private var respTimer = (1.2f * SAMPLE_RATE).toInt()
    private var pulseAcc = 0.0
    private var pulsePos = -1
    private var pulseLen = 1
    private var edgeFc = 790f
    private var retune = 0

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val br = breath
        val baseRate = rate
        for (i in 0 until frames) {
            if (--respTimer <= 0) {
                exhaling = !exhaling
                respTimer = (SAMPLE_RATE * (1.1f + rnd.nextFloat() * 0.5f)).toInt()
            }
            val resp = respLp.process(if (exhaling) 1f else 0f)

            if (--retune <= 0) {
                retune = 128
                val target = 430f + 720f * br
                edgeFc += (target - edgeFc) * 0.05f
                edgeL.lowpass(edgeFc, 0.8f)
                edgeR.lowpass(edgeFc, 0.8f)
            }

            // Pulse train: exhale runs ~10 % faster than inhale.
            pulseAcc += (baseRate * (0.95f + 0.10f * resp)).toDouble() / SAMPLE_RATE
            if (pulseAcc >= 1.0) {
                pulseAcc -= 1.0
                pulsePos = 0
                pulseLen = ((0.004f + 0.004f * rnd.nextFloat()) * SAMPLE_RATE).toInt().coerceAtLeast(4)
            }
            var pulse = 0f
            if (pulsePos >= 0) {
                pulse = 0.5f - 0.5f * cos(TAU * pulsePos / pulseLen).toFloat()
                if (++pulsePos >= pulseLen) pulsePos = -1
            }

            val loud = 0.72f + 0.36f * resp
            val edgeGain = pulse * loud * 1.00f
            val bodyGain = (0.28f + 0.72f * pulse) * loud * 1.15f
            val airGain = br * (0.30f + 0.70f * resp) * 0.30f

            val l = edgeL.process(rnd.nz()) * edgeGain +
                bodyLpL.process(brownL.next(rnd.nz())) * bodyGain +
                airL.process(rnd.nz()) * airGain
            val r = edgeR.process(rnd.nz()) * edgeGain +
                bodyLpR.process(brownR.next(rnd.nz())) * bodyGain +
                airR.process(rnd.nz()) * airGain
            left[i] = softLimit(l)
            right[i] = softLimit(r)
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * Mantel clock.
 *
 * Acoustic model: an escapement click is a 1–2 ms impact — effectively an
 * impulse — that rings the pallet, the wheel and the case. It is synthesized
 * as a very short noise impulse driving two parallel high-Q bandpasses
 * (~1.1 kHz and ~3 kHz, Q 8–14); the resonators' own ring time (Q / πf, a few
 * milliseconds) supplies the click's decay. "Tock" retunes both resonators
 * ~15 % lower than "tick", which is what gives a pendulum its two-note gait.
 * A quiet, slightly delayed lowpassed noise tail is the mechanical
 * after-rattle of the movement inside the case. "wood" lowers the Q and
 * pushes both resonances down — a mellow wooden cabinet at 1.0, bright bare
 * mechanism at 0.0. Tick and tock sit a little to either side of centre.
 * Timing jitter is only ±1 %: clocks really are nearly periodic.
 *
 * Params: "speed" 0..1 (default 0.5; pendulum half-period 1.4 s → 0.55 s),
 * "wood" 0..1 (default 0.5).
 */
class ClockTickElement : SoundGenerator {
    @Volatile private var speed = 0.5f
    @Volatile private var wood = 0.5f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "speed" -> speed = value.coerceIn(0f, 1f)
            "wood" -> wood = value.coerceIn(0f, 1f)
        }
    }

    private val rnd = Random(4409)
    private val res1 = Biquad().bandpass(1100f, 12f)
    private val res2 = Biquad().bandpass(3000f, 10f)
    private val tailLp = Biquad().lowpass(1300f)

    private var timer = (0.4f * SAMPLE_RATE).toInt()
    private var tock = false
    private var excPos = -1
    private var excLen = 2
    private var excAmp = 3.2f
    private var w1 = 0.62f
    private var w2 = 0.34f
    private var tailDelay = -1
    private var tailEnv = 0f
    private var tailDecay = 0f
    private var gainL = 0.75f
    private var gainR = 0.66f

    private fun strike() {
        tock = !tock
        val w = wood
        val ratio = if (tock) 0.85f else 1f
        res1.bandpass((1100f * (1f - 0.30f * w) * ratio).coerceAtLeast(120f), 13f - 5f * w)
        res2.bandpass((3000f * (1f - 0.35f * w) * ratio).coerceAtLeast(300f), 11f - 4f * w)
        w1 = 0.62f
        w2 = if (tock) 0.22f else 0.34f
        excPos = 0
        excLen = ((0.0010f + 0.0010f * rnd.nextFloat()) * SAMPLE_RATE).toInt().coerceAtLeast(2)
        excAmp = 9.0f * (0.9f + 0.2f * rnd.nextFloat())
        tailDelay = ((0.008f + 0.010f * rnd.nextFloat()) * SAMPLE_RATE).toInt()
        tailLp.lowpass(1500f - 700f * w)
        val angle = (if (tock) 0.63f else 0.37f) * PAN_Q
        gainL = cos(angle)
        gainR = sin(angle)
        val half = (1.4f - 0.85f * speed).coerceIn(0.4f, 1.6f)
        timer = (half * SAMPLE_RATE * (1f + 0.01f * rnd.nz())).toInt().coerceAtLeast(2048)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--timer <= 0) strike()

            var exc = 0f
            if (excPos >= 0) {
                exc = rnd.nz() * (1f - excPos.toFloat() / excLen) * excAmp
                if (++excPos >= excLen) excPos = -1
            }
            // The resonators are always run so their ring decays to silence.
            val click = res1.process(exc) * w1 + res2.process(exc) * w2

            if (tailDelay >= 0 && --tailDelay < 0) {
                tailEnv = 0.10f + 0.05f * rnd.nextFloat()
                tailDecay = expDecayCoef(0.012f + 0.020f * rnd.nextFloat())
            }
            var tail = 0f
            if (tailEnv > 0.0002f) {
                tail = tailLp.process(rnd.nz()) * tailEnv
                tailEnv *= tailDecay
            }

            val s = click + tail
            left[i] = softLimit(s * gainL)
            right[i] = softLimit(s * gainR)
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * Cafe murmur.
 *
 * Acoustic model: intelligibility comes from formant *transitions*; a blur of
 * voices is what is left when you keep the formant structure and throw the
 * transitions away. Each of 3–5 strands is noise through two parallel
 * resonant bandpasses (a low "F1" 320–700 Hz and a high "F2" 1.1–2.6 kHz)
 * whose centres wander slowly, amplitude-modulated into syllables of
 * 75–195 ms (≈3–5 Hz, irregular) grouped into 1–4 s utterances separated by
 * 1–6 s pauses. Whichever strand last began speaking becomes the "leader" and
 * the others duck ~4 dB, so the babble takes turns without any one voice ever
 * standing out enough to invite listening. Under it all sits a room bed of
 * pink noise lowpassed at 800 Hz. "clatter" sprinkles in crockery: 1 ms noise
 * excitations of a Q≈20 bandpass at 2–5 kHz, decaying in 40–160 ms, panned at
 * random.
 *
 * Params: "crowd" 0..1 (default 0.5), "clatter" 0..1 (default 0.3).
 */
class CafeMurmurElement : SoundGenerator {
    @Volatile private var crowd = 0.5f
    @Volatile private var clatter = 0.3f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "crowd" -> crowd = value.coerceIn(0f, 1f)
            "clatter" -> clatter = value.coerceIn(0f, 1f)
        }
    }

    private inner class Babbler(seed: Int) {
        private val r = Random(seed)
        private val bp1 = Biquad()
        private val bp2 = Biquad()
        private val f1Home = 320f + r.nextFloat() * 380f
        private val f2Home = 1100f + r.nextFloat() * 1500f
        private val level = 0.85f + r.nextFloat() * 0.30f
        private var f1 = f1Home
        private var f2 = f2Home
        private var f1Target = f1Home
        private var f2Target = f2Home
        private var wander = 0
        private val gainL: Float
        private val gainR: Float
        private var uttering = false
        private var timer = 1
        private var sylPos = 0
        private var sylOn = 1
        private var sylTotal = 2
        private var started = false
        var duck = 0.7f
        var duckTarget = 0.7f

        init {
            val a = r.nextFloat() * PAN_Q
            gainL = cos(a)
            gainR = sin(a)
            bp1.bandpass(f1, 4.5f)
            bp2.bandpass(f2, 5.5f)
            timer = (r.nextFloat() * 2.5f * SAMPLE_RATE).toInt() + 1
        }

        /** Consumes the "I just started talking" flag. */
        fun takeStart(): Boolean {
            val s = started
            started = false
            return s
        }

        /** Control-rate work: formant drift and duck slew (every 64 samples). */
        fun control() {
            if (--wander <= 0) {
                wander = 8 + r.nextInt(24)
                f1Target = f1Home * (0.85f + r.nextFloat() * 0.30f)
                f2Target = f2Home * (0.82f + r.nextFloat() * 0.36f)
            }
            f1 += (f1Target - f1) * 0.03f
            f2 += (f2Target - f2) * 0.03f
            bp1.bandpass(f1.coerceIn(250f, 1000f), 4.5f)
            bp2.bandpass(f2.coerceIn(900f, 3100f), 5.5f)
            duck += (duckTarget - duck) * 0.05f
        }

        private fun nextSyllable() {
            sylOn = ((0.075f + r.nextFloat() * 0.120f) * SAMPLE_RATE).toInt().coerceAtLeast(64)
            sylTotal = sylOn + ((0.035f + r.nextFloat() * 0.100f) * SAMPLE_RATE).toInt()
            sylPos = 0
        }

        fun sample(out: FloatArray, drive: Float) {
            if (!uttering) {
                if (--timer <= 0) {
                    uttering = true
                    started = true
                    timer = ((1.0f + r.nextFloat() * 3.0f) * SAMPLE_RATE).toInt()
                    nextSyllable()
                }
                return
            }
            if (++sylPos >= sylTotal) nextSyllable()
            var env = 0.07f
            if (sylPos < sylOn) {
                env += 0.93f * (0.5f - 0.5f * cos(TAU * sylPos / sylOn).toFloat())
            }
            if (--timer <= 0) {
                uttering = false
                timer = ((1.2f + r.nextFloat() * 4.5f) * SAMPLE_RATE).toInt()
            }
            val n = r.nz()
            val s = (bp1.process(n) * 0.62f + bp2.process(n) * 0.42f) *
                env * level * duck * drive
            out[0] += s * gainL
            out[1] += s * gainR
        }
    }

    private class Clink {
        val bp = Biquad()
        var env = 0f
        var decay = 0f
        var gainL = 0.7f
        var gainR = 0.7f
    }

    private val rnd = Random(90210)
    private val voices = Array(MAX_VOICES) { Babbler(5100 + it * 137) }
    private val clinks = Array(5) { Clink() }
    private val bedL = PinkFilter()
    private val bedR = PinkFilter()
    private val bedLpL = Biquad().lowpass(800f)
    private val bedLpR = Biquad().lowpass(800f)
    private val acc = FloatArray(2)
    private var leader = 0
    private var ctl = 0

    private fun spawnClink() {
        var slot = -1
        for (i in clinks.indices) {
            if (clinks[i].env < 0.0005f) {
                slot = i
                break
            }
        }
        if (slot < 0) return
        val c = clinks[slot]
        c.env = 0.7f + rnd.nextFloat() * 0.7f
        c.decay = expDecayCoef(0.040f + rnd.nextFloat() * 0.120f)
        c.bp.bandpass(2000f + rnd.nextFloat() * 3000f, 16f + rnd.nextFloat() * 10f)
        val a = rnd.nextFloat() * PAN_Q
        c.gainL = cos(a)
        c.gainR = sin(a)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val c = crowd
        val active = (3 + (c * 2f).roundToInt()).coerceIn(3, MAX_VOICES)
        if (leader >= active) leader = 0
        val bedGain = 0.13f + 0.16f * c
        val clinkChance = (0.12f + 1.20f * clatter) / SAMPLE_RATE

        for (i in 0 until frames) {
            if (--ctl <= 0) {
                ctl = 64
                for (v in 0 until active) {
                    if (voices[v].takeStart()) leader = v
                }
                for (v in 0 until active) {
                    voices[v].duckTarget = if (v == leader) 1f else 0.58f
                    voices[v].control()
                }
            }
            if (rnd.nextFloat() < clinkChance) spawnClink()

            acc[0] = 0f
            acc[1] = 0f
            for (v in 0 until active) voices[v].sample(acc, VOICE_DRIVE)

            for (k in clinks.indices) {
                val cl = clinks[k]
                if (cl.env < 0.0005f) continue
                val s = cl.bp.process(rnd.nz()) * cl.env
                acc[0] += s * cl.gainL
                acc[1] += s * cl.gainR
                cl.env *= cl.decay
            }

            acc[0] += bedLpL.process(bedL.next(rnd.nz())) * bedGain
            acc[1] += bedLpR.process(bedR.next(rnd.nz())) * bedGain
            left[i] = softLimit(acc[0])
            right[i] = softLimit(acc[1])
        }
    }

    private companion object {
        const val MAX_VOICES = 5
        const val VOICE_DRIVE = 3.5f
    }
}

// ---------------------------------------------------------------------------

/**
 * Foghorn.
 *
 * Acoustic model: a diaphone horn is a large air-driven reed, so the blast is
 * a two-tone chord — a fundamental near 115 Hz plus a fifth-ish partner near
 * 145 Hz — with a second, slightly detuned copy of the fundamental producing
 * the slow ~1.4 Hz beating that gives real horns their throb. The valve takes
 * time to open and to shut, hence a 300 ms raised-cosine attack and a ~950 ms
 * release across a 2.5–3.5 s blast. A noise layer bandpassed around the
 * fundamentals rides the same envelope: that is the escaping air.
 *
 * "distance" does what air and water do: a heavier lowpass, less level, and a
 * pair of delayed, further-filtered reflections (~270 ms and ~530 ms) that
 * arrive from slightly different directions — the sound of a harbour. Between
 * blasts there is a barely-there sea-air floor (highpassed hiss plus a brown
 * swell) so that switching the element on is audible immediately.
 *
 * Params: "rate" 0..1 (default 0.4; one blast per 20–75 s),
 * "distance" 0..1 (default 0.6).
 */
class FoghornElement : SoundGenerator {
    @Volatile private var rate = 0.4f
    @Volatile private var distance = 0.6f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value.coerceIn(0f, 1f)
            "distance" -> distance = value.coerceIn(0f, 1f)
        }
    }

    private val rnd = Random(2711)
    private val delayLen = (0.62f * SAMPLE_RATE).toInt()
    private val delay = FloatArray(delayLen)
    private val tap1 = (0.27f * SAMPLE_RATE).toInt()
    private val tap2 = (0.53f * SAMPLE_RATE).toInt()
    private var writeIdx = 0

    private val toneLp = Biquad().lowpass(600f)
    private val airBp = Biquad().bandpass(135f, 1.1f)
    private val echoLpL = Biquad().lowpass(420f)
    private val echoLpR = Biquad().lowpass(380f)
    private val hissL = Biquad().highpass(900f)
    private val hissR = Biquad().highpass(900f)
    private val seaL = BrownFilter()
    private val seaR = BrownFilter()

    private var waiting = SAMPLE_RATE * 4
    private var blasting = false
    private var pos = 0
    private var len = 1
    private var atk = 1
    private var rel = 1
    private var blastGain = 1f
    private var p1 = 0.0
    private var p1b = 0.0
    private var p2 = 0.0
    private var inc1 = 0.0
    private var inc1b = 0.0
    private var inc2 = 0.0
    private var wobPhase = 0.0
    private var seaPhase = 0.0
    private val wobInc = TAU * 3.7 / SAMPLE_RATE
    private val seaInc = TAU * 0.07 / SAMPLE_RATE

    /** Silence between blasts, so that blast-to-blast period ≈ 20..75 s. */
    private fun nextWait(): Int {
        val seconds = (20f + (1f - rate) * 55f) * (0.85f + 0.30f * rnd.nextFloat())
        return ((seconds * SAMPLE_RATE).toInt() - len).coerceAtLeast(SAMPLE_RATE * 3)
    }

    private fun startBlast() {
        blasting = true
        pos = 0
        len = (SAMPLE_RATE * (2.5f + rnd.nextFloat())).toInt()
        atk = (0.30f * SAMPLE_RATE).toInt()
        rel = (0.95f * SAMPLE_RATE).toInt()
        val det = 0.985 + rnd.nextFloat() * 0.03
        inc1 = TAU * 115.0 * det / SAMPLE_RATE
        inc1b = TAU * 115.0 * det * 1.012 / SAMPLE_RATE
        inc2 = TAU * 145.0 * det / SAMPLE_RATE
        p1 = 0.0
        p1b = 0.0
        p2 = 0.0
        val d = distance
        toneLp.lowpass((950f - 700f * d).coerceAtLeast(180f))
        blastGain = 1f - 0.45f * d
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val d = distance
        val echo1 = 0.30f * d
        val echo2 = 0.16f * d
        for (i in 0 until frames) {
            var mono = 0f
            if (blasting) {
                val env = when {
                    pos < atk -> 0.5f - 0.5f * cos(PI * pos / atk).toFloat()
                    pos < len - rel -> 1f
                    else -> {
                        val t = (pos - (len - rel)).toFloat() / rel
                        0.5f + 0.5f * cos(PI * t.coerceIn(0f, 1f)).toFloat()
                    }
                }
                p1 += inc1
                p1b += inc1b
                p2 += inc2
                if (p1 > TAU) p1 -= TAU
                if (p1b > TAU) p1b -= TAU
                if (p2 > TAU) p2 -= TAU
                wobPhase += wobInc
                if (wobPhase > TAU) wobPhase -= TAU
                val tone = (sin(p1) * 0.30 + sin(p1b) * 0.12 + sin(p2) * 0.20).toFloat()
                val air = airBp.process(rnd.nz()) * 0.35f
                val wob = 0.93f + 0.07f * sin(wobPhase).toFloat()
                mono = toneLp.process((tone + air) * env * wob) * blastGain
                if (++pos >= len) {
                    blasting = false
                    waiting = nextWait()
                }
            } else if (--waiting <= 0) {
                startBlast()
            }

            val r1 = delay[(writeIdx + delayLen - tap1) % delayLen]
            val r2 = delay[(writeIdx + delayLen - tap2) % delayLen]
            delay[writeIdx] = mono
            if (++writeIdx >= delayLen) writeIdx = 0
            val refL = echoLpL.process(r1 * echo1 + r2 * echo2 * 0.7f)
            val refR = echoLpR.process(r1 * echo1 * 0.6f + r2 * echo2)

            seaPhase += seaInc
            if (seaPhase > TAU) seaPhase -= TAU
            val seaSwell = 0.7f + 0.3f * sin(seaPhase).toFloat()
            val floorL = hissL.process(rnd.nz()) * 0.020f * seaSwell +
                seaL.next(rnd.nz()) * 0.050f
            val floorR = hissR.process(rnd.nz()) * 0.020f * seaSwell +
                seaR.next(rnd.nz()) * 0.050f

            left[i] = softLimit(mono + refL + floorL)
            right[i] = softLimit(mono + refR + floorR)
        }
    }
}

// ---------------------------------------------------------------------------

// BirdsongElement was replaced by BirdsongV2 in Birdsong.kt (species archetypes,
// curved contours, envelope-tracked harmonics, breath noise, outdoor echo).
