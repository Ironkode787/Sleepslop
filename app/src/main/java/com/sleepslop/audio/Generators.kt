package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A generator writes one buffer of stereo audio (overwriting, not mixing).
 * All sounds are synthesized — nothing is sampled or recorded.
 */
interface SoundGenerator {
    fun render(left: FloatArray, right: FloatArray, frames: Int)
}

private fun Random.bipolar(): Float = nextFloat() * 2f - 1f

// ---------------------------------------------------------------------------
// Core noise colors
// ---------------------------------------------------------------------------

class WhiteNoise : SoundGenerator {
    private val rl = Random(41)
    private val rr = Random(97)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = rl.bipolar() * 0.28f
            right[i] = rr.bipolar() * 0.28f
        }
    }
}

class PinkNoise : SoundGenerator {
    private val rl = Random(11)
    private val rr = Random(23)
    private val fl = PinkFilter()
    private val fr = PinkFilter()

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = fl.next(rl.bipolar()) * 0.75f
            right[i] = fr.next(rr.bipolar()) * 0.75f
        }
    }
}

class BrownNoise : SoundGenerator {
    private val rl = Random(5)
    private val rr = Random(17)
    private val fl = BrownFilter()
    private val fr = BrownFilter()

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = fl.next(rl.bipolar()) * 0.9f
            right[i] = fr.next(rr.bipolar()) * 0.9f
        }
    }
}

/**
 * Binaural delta-wave tones: 110 Hz in the left ear, 114 Hz in the right.
 * The brain perceives the 4 Hz difference — squarely in the delta band
 * associated with deep sleep. A very slow swell keeps it organic.
 */
class DeepTones : SoundGenerator {
    private var phaseL = 0.0
    private var phaseR = 0.0
    private var swellPhase = 0.0
    private val incL = 2.0 * PI * 110.0 / SAMPLE_RATE
    private val incR = 2.0 * PI * 114.0 / SAMPLE_RATE
    private val incSwell = 2.0 * PI * 0.08 / SAMPLE_RATE

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val swell = 0.85f + 0.15f * sin(swellPhase).toFloat()
            left[i] = sin(phaseL).toFloat() * 0.25f * swell
            right[i] = sin(phaseR).toFloat() * 0.25f * swell
            phaseL += incL
            phaseR += incR
            swellPhase += incSwell
            if (phaseL > 2 * PI) phaseL -= 2 * PI
            if (phaseR > 2 * PI) phaseR -= 2 * PI
            if (swellPhase > 2 * PI) swellPhase -= 2 * PI
        }
    }
}

// ---------------------------------------------------------------------------
// Textured scenes (still 100% synthesized)
// ---------------------------------------------------------------------------

/** Shared machinery: a pool of short decaying noise bursts (drops, crackles). */
private class BurstPool(size: Int) {
    val env = FloatArray(size)
    val decay = FloatArray(size)
    val gainL = FloatArray(size)
    val gainR = FloatArray(size)
    val filter = Array(size) { Biquad() }
    val size = size

    /** Returns the index of a free slot, or -1. */
    fun freeSlot(): Int {
        for (i in 0 until size) if (env[i] < 0.001f) return i
        return -1
    }

    fun spawn(rnd: Random, amp: Float, decayMs: Float, centerHz: Float, q: Float) {
        val s = freeSlot()
        if (s < 0) return
        env[s] = amp
        decay[s] = (0.001f / amp).pow(1f / (decayMs / 1000f * SAMPLE_RATE))
        val pan = rnd.nextFloat() * (PI / 2).toFloat()
        gainL[s] = cos(pan)
        gainR[s] = sin(pan)
        filter[s].bandpass(centerHz, q)
    }

    /** Sum of all active bursts for one sample; also advances envelopes. */
    fun sample(rnd: Random, outLR: FloatArray) {
        var l = 0f
        var r = 0f
        for (i in 0 until size) {
            if (env[i] < 0.001f) continue
            val burst = filter[i].process(rnd.bipolar()) * env[i]
            l += burst * gainL[i]
            r += burst * gainR[i]
            env[i] *= decay[i]
        }
        outLR[0] = l
        outLR[1] = r
    }
}

/**
 * Rain: a dense low "patter" bed of filtered noise, a high hiss,
 * and a Poisson process of individual droplet bursts.
 */
class Rain : SoundGenerator {
    private val rnd = Random(2024)
    private val bedLo = Biquad().lowpass(1100f)
    private val bedHiL = Biquad().highpass(3800f)
    private val bedHiR = Biquad().highpass(3800f)
    private val drops = BurstPool(20)
    private val burst = FloatArray(2)
    private val dropChance = 34f / SAMPLE_RATE

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (rnd.nextFloat() < dropChance) {
                drops.spawn(
                    rnd,
                    amp = 0.10f + rnd.nextFloat() * 0.25f,
                    decayMs = 6f + rnd.nextFloat() * 26f,
                    centerHz = 1800f + rnd.nextFloat() * 5500f,
                    q = 2.2f
                )
            }
            val body = bedLo.process(rnd.bipolar()) * 0.42f
            val hissL = bedHiL.process(rnd.bipolar()) * 0.10f
            val hissR = bedHiR.process(rnd.bipolar()) * 0.10f
            drops.sample(rnd, burst)
            left[i] = body + hissL + burst[0]
            right[i] = body + hissR + burst[1]
        }
    }
}

/**
 * Ocean: brown-noise surf whose loudness follows a slow, randomly-timed
 * wave envelope, with a bright hiss of spray released at each crest.
 */
class Ocean : SoundGenerator {
    private val rnd = Random(7)
    private val surfL = BrownFilter()
    private val surfR = BrownFilter()
    private val sprayL = Biquad().highpass(1400f)
    private val sprayR = Biquad().highpass(1400f)
    private var phase = 0.0
    private var inc = wavePeriod()

    private fun wavePeriod(): Double =
        2.0 * PI / ((9.0 + rnd.nextDouble() * 6.0) * SAMPLE_RATE)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            phase += inc
            if (phase > 2 * PI) {
                phase -= 2 * PI
                inc = wavePeriod()
            }
            val raw = (0.5 - 0.5 * cos(phase)).toFloat()
            val env = raw * raw
            val surfGain = 0.30f + 0.85f * env
            val sprayGain = env * env * 0.45f
            left[i] = surfL.next(rnd.bipolar()) * surfGain +
                sprayL.process(rnd.bipolar()) * sprayGain
            right[i] = surfR.next(rnd.bipolar()) * surfGain +
                sprayR.process(rnd.bipolar()) * sprayGain
        }
    }
}

/**
 * Wind: white noise through a resonant bandpass whose center frequency
 * and intensity wander slowly and independently, like gusts.
 */
class Wind : SoundGenerator {
    private val rnd = Random(31)
    private val bpL = Biquad().bandpass(400f, 1.4f)
    private val bpR = Biquad().bandpass(430f, 1.4f)
    private var freq = 400f
    private var freqTarget = 400f
    private var gust = 0.6f
    private var gustTarget = 0.6f
    private var retuneCounter = 0
    private var wanderCounter = 0

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--wanderCounter <= 0) {
                wanderCounter = (SAMPLE_RATE * (1.5f + rnd.nextFloat() * 3f)).toInt()
                freqTarget = 170f + rnd.nextFloat() * 580f
                gustTarget = 0.30f + rnd.nextFloat() * 0.70f
            }
            if (--retuneCounter <= 0) {
                retuneCounter = 256
                freq += (freqTarget - freq) * 0.02f
                gust += (gustTarget - gust) * 0.02f
                bpL.bandpass(freq, 1.4f)
                bpR.bandpass(freq * 1.06f, 1.4f)
            }
            left[i] = bpL.process(rnd.bipolar()) * gust * 0.85f
            right[i] = bpR.process(rnd.bipolar()) * gust * 0.85f
        }
    }
}

/**
 * Forest night: a whisper of pink-noise foliage plus three synthesized
 * crickets — sine chirps around 4 kHz, pulsed into syllables and phrases.
 */
class ForestNight : SoundGenerator {
    private val rnd = Random(3)
    private val bedL = PinkFilter()
    private val bedR = PinkFilter()
    private val bedLp = Biquad().lowpass(900f)

    private inner class Cricket(seed: Int) {
        private val r = Random(seed)
        private val freq = 3700f + r.nextFloat() * 900f
        private val inc = (2.0 * PI * freq / SAMPLE_RATE)
        private val gainL: Float
        private val gainR: Float
        private var phase = 0.0
        private var state = 0 // 0 = silent gap, 1 = chirping
        private var counter = (r.nextFloat() * SAMPLE_RATE).toInt()
        private var syllablesLeft = 0
        private var sylPos = 0
        private val sylOn = (0.016f * SAMPLE_RATE).toInt()
        private val sylTotal = (0.042f * SAMPLE_RATE).toInt()

        init {
            val pan = r.nextFloat() * (PI / 2).toFloat()
            gainL = cos(pan)
            gainR = sin(pan)
        }

        fun sample(out: FloatArray) {
            if (state == 0) {
                if (--counter <= 0) {
                    state = 1
                    syllablesLeft = 3 + r.nextInt(6)
                    sylPos = 0
                }
                return
            }
            var amp = 0f
            if (sylPos < sylOn) {
                // Raised-cosine syllable envelope.
                amp = (0.5f - 0.5f * cos(2.0 * PI * sylPos / sylOn).toFloat())
            }
            if (++sylPos >= sylTotal) {
                sylPos = 0
                if (--syllablesLeft <= 0) {
                    state = 0
                    counter = (SAMPLE_RATE * (0.6f + r.nextFloat() * 2.8f)).toInt()
                }
            }
            phase += inc
            if (phase > 2 * PI) phase -= 2 * PI
            val s = sin(phase).toFloat() * amp * 0.16f
            out[0] += s * gainL
            out[1] += s * gainR
        }
    }

    private val crickets = arrayOf(Cricket(101), Cricket(202), Cricket(303))
    private val chirp = FloatArray(2)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val rustle = bedLp.process(rnd.bipolar()) * 0.16f
            chirp[0] = 0f
            chirp[1] = 0f
            for (c in crickets) c.sample(chirp)
            left[i] = bedL.next(rnd.bipolar()) * 0.22f + rustle + chirp[0]
            right[i] = bedR.next(rnd.bipolar()) * 0.22f + rustle + chirp[1]
        }
    }
}

/**
 * Campfire: a warm low rumble with random crackles and the
 * occasional louder, lower-pitched pop.
 */
class Campfire : SoundGenerator {
    private val rnd = Random(13)
    private val rumbleL = BrownFilter()
    private val rumbleR = BrownFilter()
    private val rumbleLp = Biquad().lowpass(260f)
    private val crackles = BurstPool(16)
    private val burst = FloatArray(2)
    private val crackleChance = 11f / SAMPLE_RATE
    private val popChance = 0.7f / SAMPLE_RATE

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (rnd.nextFloat() < crackleChance) {
                crackles.spawn(
                    rnd,
                    amp = 0.15f + rnd.nextFloat() * 0.40f,
                    decayMs = 3f + rnd.nextFloat() * 18f,
                    centerHz = 900f + rnd.nextFloat() * 3200f,
                    q = 1.6f
                )
            }
            if (rnd.nextFloat() < popChance) {
                crackles.spawn(
                    rnd,
                    amp = 0.5f + rnd.nextFloat() * 0.35f,
                    decayMs = 25f + rnd.nextFloat() * 50f,
                    centerHz = 260f + rnd.nextFloat() * 500f,
                    q = 1.1f
                )
            }
            val glow = rumbleLp.process(rnd.bipolar()) * 0.30f
            crackles.sample(rnd, burst)
            left[i] = rumbleL.next(rnd.bipolar()) * 0.45f + glow + burst[0]
            right[i] = rumbleR.next(rnd.bipolar()) * 0.45f + glow + burst[1]
        }
    }
}

/**
 * Box fan: a soft motor hum (fundamental + harmonics), a gentle blade
 * wobble, and broadband moving air.
 */
class BoxFan : SoundGenerator {
    private val rnd = Random(29)
    private var phase = 0.0
    private var wobble = 0.0
    private val inc = 2.0 * PI * 53.0 / SAMPLE_RATE
    private val incWobble = 2.0 * PI * 11.3 / SAMPLE_RATE
    private val airL = Biquad().lowpass(720f)
    private val airR = Biquad().lowpass(720f)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            phase += inc
            wobble += incWobble
            if (phase > 2 * PI) phase -= 2 * PI
            if (wobble > 2 * PI) wobble -= 2 * PI
            val hum = (sin(phase) * 0.5 + sin(2 * phase) * 0.20 + sin(3 * phase) * 0.08)
                .toFloat() * 0.30f * (1f + 0.05f * sin(wobble).toFloat())
            left[i] = hum + airL.process(rnd.bipolar()) * 0.40f
            right[i] = hum + airR.process(rnd.bipolar()) * 0.40f
        }
    }
}

/**
 * Night train: distant rhythmic rumble — clickety-clack softened into a
 * hypnotic rocking pattern over a low rolling drone.
 */
class NightTrain : SoundGenerator {
    private val rnd = Random(47)
    private val droneL = BrownFilter()
    private val droneR = BrownFilter()
    private val droneLp = Biquad().lowpass(200f)
    private val clacks = BurstPool(8)
    private val burst = FloatArray(2)
    private var beat = 0
    private val beatLen = (0.62f * SAMPLE_RATE).toInt()

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (beat == 0 || beat == (0.14f * SAMPLE_RATE).toInt()) {
                clacks.spawn(
                    rnd,
                    amp = 0.20f + rnd.nextFloat() * 0.10f,
                    decayMs = 35f + rnd.nextFloat() * 20f,
                    centerHz = 130f + rnd.nextFloat() * 90f,
                    q = 1.0f
                )
            }
            if (++beat >= beatLen) beat = 0
            val roll = droneLp.process(rnd.bipolar()) * 0.35f
            clacks.sample(rnd, burst)
            left[i] = droneL.next(rnd.bipolar()) * 0.50f + roll + burst[0]
            right[i] = droneR.next(rnd.bipolar()) * 0.50f + roll + burst[1]
        }
    }
}

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

enum class Sound(
    val label: String,
    val emoji: String,
    val blurb: String,
    val create: () -> SoundGenerator,
) {
    WHITE("White noise", "🌫️", "Even energy across every frequency", ::WhiteNoise),
    PINK("Pink noise", "🌸", "Softer, naturally balanced hiss", ::PinkNoise),
    BROWN("Brown noise", "🟤", "Deep, gentle low-frequency rumble", ::BrownNoise),
    DEEP("Deep tones", "🌀", "110 Hz binaural delta-wave beat", ::DeepTones),
    RAIN("Rain", "🌧️", "Steady rainfall with soft droplets", ::Rain),
    OCEAN("Ocean", "🌊", "Slow waves rolling onto the shore", ::Ocean),
    WIND("Wind", "🍃", "Gusts drifting through the dark", ::Wind),
    FOREST("Forest night", "🦗", "Crickets in a quiet wood", ::ForestNight),
    FIRE("Campfire", "🔥", "Crackling embers, warm rumble", ::Campfire),
    FAN("Box fan", "💨", "Low motor hum and moving air", ::BoxFan),
    TRAIN("Night train", "🚂", "Hypnotic clickety-clack, far away", ::NightTrain),
}
