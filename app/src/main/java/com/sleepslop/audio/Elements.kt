package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ambient elements: small, parameterized event generators meant to be
 * layered over the broadband beds. Every one is pure synthesis.
 *
 * Parameter fields are written from the UI thread and read from the render
 * thread; float writes are atomic on the JVM so plain @Volatile suffices.
 */

private const val TWO_PI = 2.0 * PI

private fun Random.bi(): Float = nextFloat() * 2f - 1f

private fun constantPowerPan(rnd: Random): Pair<Float, Float> {
    val angle = rnd.nextFloat() * (PI / 2).toFloat()
    return cos(angle) to sin(angle)
}

// ---------------------------------------------------------------------------

/** Up to six independent cricket voices; rate compresses the silent gaps. */
class CricketsElement : SoundGenerator {
    @Volatile private var rate = 0.5f
    @Volatile private var voiceCount = 3
    @Volatile private var pitch = 4200f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value
            "voices" -> voiceCount = value.roundToInt().coerceIn(1, MAX_VOICES)
            "pitch" -> pitch = value
        }
    }

    private inner class Voice(seed: Int) {
        private val r = Random(seed)
        private val detune = 0.92f + r.nextFloat() * 0.16f
        private val gainL: Float
        private val gainR: Float
        private var phase = 0.0
        private var inc = 0.0
        private var chirping = false
        private var counter = (r.nextFloat() * SAMPLE_RATE).toInt()
        private var syllablesLeft = 0
        private var sylPos = 0
        private val sylOn = (0.016f * SAMPLE_RATE).toInt()
        private val sylTotal = (0.042f * SAMPLE_RATE).toInt()

        init {
            val pan = constantPowerPan(r)
            gainL = pan.first
            gainR = pan.second
        }

        fun sample(out: FloatArray) {
            if (!chirping) {
                if (--counter <= 0) {
                    chirping = true
                    syllablesLeft = 3 + r.nextInt(6)
                    sylPos = 0
                    inc = TWO_PI * (pitch * detune) / SAMPLE_RATE
                }
                return
            }
            var amp = 0f
            if (sylPos < sylOn) {
                amp = 0.5f - 0.5f * cos(TWO_PI * sylPos / sylOn).toFloat()
            }
            if (++sylPos >= sylTotal) {
                sylPos = 0
                if (--syllablesLeft <= 0) {
                    chirping = false
                    val gapScale = (2.2f - 2.0f * rate).coerceAtLeast(0.15f)
                    counter = (SAMPLE_RATE * (0.4f + r.nextFloat() * 3.0f) * gapScale).toInt()
                }
            }
            phase += inc
            if (phase > TWO_PI) phase -= TWO_PI
            val s = sin(phase).toFloat() * amp * 0.17f
            out[0] += s * gainL
            out[1] += s * gainR
        }
    }

    private val voices = Array(MAX_VOICES) { Voice(1000 + it * 17) }
    private val acc = FloatArray(2)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val active = voiceCount
        for (i in 0 until frames) {
            acc[0] = 0f
            acc[1] = 0f
            for (v in 0 until active) voices[v].sample(acc)
            left[i] = acc[0]
            right[i] = acc[1]
        }
    }

    private companion object {
        const val MAX_VOICES = 6
    }
}

// ---------------------------------------------------------------------------

/** Pond frogs: pulsed two-harmonic croaks with a downward pitch glide. */
class FrogsElement : SoundGenerator {
    @Volatile private var rate = 0.45f
    @Volatile private var pitch = 380f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value
            "pitch" -> pitch = value
        }
    }

    private class Croak {
        var active = false
        var pos = 0
        var dur = 1
        var f0 = 380f
        var phase = 0.0
        var pulsePhase = 0.0
        var gainL = 0.7f
        var gainR = 0.7f
    }

    private val rnd = Random(71)
    private val croaks = Array(3) { Croak() }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val spawnChance = (0.05f + rate * 0.65f) / SAMPLE_RATE
        for (i in 0 until frames) {
            if (rnd.nextFloat() < spawnChance) {
                croaks.firstOrNull { !it.active }?.let { c ->
                    c.active = true
                    c.pos = 0
                    c.dur = (SAMPLE_RATE * (0.35f + rnd.nextFloat() * 0.3f)).toInt()
                    c.f0 = pitch * (0.88f + rnd.nextFloat() * 0.24f)
                    c.phase = 0.0
                    c.pulsePhase = 0.0
                    val pan = constantPowerPan(rnd)
                    c.gainL = pan.first
                    c.gainR = pan.second
                }
            }
            var l = 0f
            var r = 0f
            for (c in croaks) {
                if (!c.active) continue
                val t = c.pos.toFloat() / c.dur
                val env = sin(PI * t).toFloat()
                val pulseRaw = 0.5f + 0.5f * cos(c.pulsePhase).toFloat()
                val pulse = 0.35f + 0.65f * pulseRaw * pulseRaw
                val f = c.f0 * (1f - 0.12f * t)
                c.phase += TWO_PI * f / SAMPLE_RATE
                c.pulsePhase += TWO_PI * 23.0 / SAMPLE_RATE
                val s = (sin(c.phase) + 0.45 * sin(2 * c.phase)).toFloat() * env * pulse * 0.40f
                l += s * c.gainL
                r += s * c.gainR
                if (++c.pos >= c.dur) c.active = false
            }
            left[i] = l
            right[i] = r
        }
    }
}

// ---------------------------------------------------------------------------

/** An owl calling "hoo-hoo-hoooo" every so often, softened by a lowpass. */
class OwlElement : SoundGenerator {
    @Volatile private var rate = 0.4f
    @Volatile private var pitch = 350f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value
            "pitch" -> pitch = value
        }
    }

    // (duration seconds, voiced) — pauses are unvoiced.
    private val pattern = arrayOf(
        0.18f to true, 0.10f to false, 0.16f to true, 0.14f to false, 0.55f to true
    )

    private val rnd = Random(83)
    private val lp = Biquad().lowpass(850f)
    private var waiting = (SAMPLE_RATE * 5).toInt()
    private var hooting = false
    private var noteIdx = 0
    private var notePos = 0
    private var noteLen = 1
    private var baseFreq = 350f
    private var phase = 0.0
    private var vibPhase = 0.0
    private var gainL = 0.7f
    private var gainR = 0.7f

    private fun nextWait(): Int {
        val scale = (1.8f - 1.6f * rate).coerceAtLeast(0.2f)
        return (SAMPLE_RATE * (10f + rnd.nextFloat() * 35f) * scale).toInt()
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (!hooting) {
                left[i] = 0f
                right[i] = 0f
                if (--waiting <= 0) {
                    hooting = true
                    noteIdx = 0
                    notePos = 0
                    noteLen = (pattern[0].first * SAMPLE_RATE).toInt()
                    baseFreq = pitch * (0.95f + rnd.nextFloat() * 0.1f)
                    val pan = constantPowerPan(rnd)
                    gainL = pan.first
                    gainR = pan.second
                }
                continue
            }
            var s = 0f
            if (pattern[noteIdx].second) {
                val t = notePos.toFloat() / noteLen
                val env = sin(PI * t).toFloat()
                // The long final note droops and carries a light vibrato.
                val isLast = noteIdx == pattern.size - 1
                val droop = if (isLast) 1f - 0.07f * t else 1f
                vibPhase += TWO_PI * 5.0 / SAMPLE_RATE
                val vib = if (isLast) 1f + 0.012f * sin(vibPhase).toFloat() else 1f
                phase += TWO_PI * baseFreq * droop * vib / SAMPLE_RATE
                s = lp.process(sin(phase).toFloat()) * env * 0.55f
            }
            left[i] = s * gainL
            right[i] = s * gainR
            if (++notePos >= noteLen) {
                notePos = 0
                if (++noteIdx >= pattern.size) {
                    hooting = false
                    waiting = nextWait()
                } else {
                    noteLen = (pattern[noteIdx].first * SAMPLE_RATE).toInt().coerceAtLeast(1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

/** Rolling thunder: enveloped, texture-modulated brown noise, far away. */
class ThunderElement : SoundGenerator {
    @Volatile private var rate = 0.4f
    @Volatile private var distance = 0.6f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value
            "distance" -> distance = value
        }
    }

    private val rnd = Random(59)
    private val brownL = BrownFilter()
    private val brownR = BrownFilter()
    private val lpL = Biquad().lowpass(150f)
    private val lpR = Biquad().lowpass(150f)
    private var waiting = (SAMPLE_RATE * 8).toInt()
    private var rumbling = false
    private var pos = 0
    private var dur = 1
    private var strength = 1f
    private var texture = 0.7f
    private var textureTarget = 0.7f
    private var textureCounter = 0

    private fun nextWait(): Int {
        val scale = (1.9f - 1.7f * rate).coerceAtLeast(0.15f)
        return (SAMPLE_RATE * (15f + rnd.nextFloat() * 55f) * scale).toInt()
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (!rumbling) {
                left[i] = 0f
                right[i] = 0f
                if (--waiting <= 0) {
                    rumbling = true
                    pos = 0
                    dur = (SAMPLE_RATE * (3.5f + rnd.nextFloat() * 4.5f)).toInt()
                    strength = 0.65f + rnd.nextFloat() * 0.35f
                    val fc = 70f + (1f - distance) * 220f
                    lpL.lowpass(fc)
                    lpR.lowpass(fc)
                }
                continue
            }
            // The rumble's internal churn: a slow random walk.
            if (--textureCounter <= 0) {
                textureCounter = 1500
                textureTarget = 0.35f + rnd.nextFloat() * 0.65f
            }
            texture += (textureTarget - texture) * 0.002f

            val t = pos.toFloat() / dur
            val attack = (t / 0.08f).coerceAtMost(1f)
            val env = attack * (1f - t).pow(1.7f)
            val gain = env * texture * strength * (1f - 0.55f * distance) * 1.6f
            left[i] = lpL.process(brownL.next(rnd.bi())) * gain
            right[i] = lpR.process(brownR.next(rnd.bi())) * gain
            if (++pos >= dur) {
                rumbling = false
                waiting = nextWait()
            }
        }
    }
}

// ---------------------------------------------------------------------------

/** Wind chimes: pentatonic two-partial tones struck by a gusting breeze. */
class ChimesElement : SoundGenerator {
    @Volatile private var rate = 0.4f
    @Volatile private var shimmer = 0.5f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value
            "shimmer" -> shimmer = value
        }
    }

    private class Tone {
        var env = 0f
        var decay = 0f
        var p1 = 0.0
        var p2 = 0.0
        var i1 = 0.0
        var i2 = 0.0
        var a2 = 0f
        var gainL = 0.7f
        var gainR = 0.7f
    }

    private val notes = floatArrayOf(523.25f, 587.33f, 659.25f, 783.99f, 880.0f)
    private val rnd = Random(37)
    private val tones = Array(8) { Tone() }
    private var gust = 0.5f
    private var gustTarget = 0.5f
    private var gustCounter = 0

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (--gustCounter <= 0) {
                gustCounter = (SAMPLE_RATE * (0.8f + rnd.nextFloat() * 2f)).toInt()
                gustTarget = 0.05f + rnd.nextFloat() * 1.25f
            }
            gust += (gustTarget - gust) * 0.00004f

            val strikesPerSec = 0.02f + 1.3f * rate * gust
            if (rnd.nextFloat() < strikesPerSec / SAMPLE_RATE) {
                tones.firstOrNull { it.env < 0.001f }?.let { tone ->
                    val f = notes[rnd.nextInt(notes.size)]
                    tone.env = 0.10f + rnd.nextFloat() * 0.10f
                    val tau = 1.2f + 2.8f * shimmer + rnd.nextFloat() * 0.5f
                    tone.decay = (0.001f).pow(1f / (tau * SAMPLE_RATE))
                    tone.p1 = 0.0
                    tone.p2 = 0.0
                    tone.i1 = TWO_PI * f / SAMPLE_RATE
                    tone.i2 = TWO_PI * f * 2.756 / SAMPLE_RATE
                    tone.a2 = 0.12f + 0.32f * shimmer
                    val pan = constantPowerPan(rnd)
                    tone.gainL = pan.first
                    tone.gainR = pan.second
                }
            }

            var l = 0f
            var r = 0f
            for (tone in tones) {
                if (tone.env < 0.001f) continue
                tone.p1 += tone.i1
                tone.p2 += tone.i2
                val s = (sin(tone.p1) + tone.a2 * sin(tone.p2)).toFloat() * tone.env
                l += s * tone.gainL
                r += s * tone.gainR
                tone.env *= tone.decay
            }
            left[i] = l
            right[i] = r
        }
    }
}

// ---------------------------------------------------------------------------

/** Cave drips: a tiny click exciting a sine plink with an upward glide. */
class WaterDropsElement : SoundGenerator {
    @Volatile private var rate = 0.4f
    @Volatile private var tone = 0.5f

    override fun setParam(id: String, value: Float) {
        when (id) {
            "rate" -> rate = value
            "tone" -> tone = value
        }
    }

    private class Drop {
        var env = 0f
        var decay = 0f
        var phase = 0.0
        var inc = 0.0
        var glide = 1.0
        var age = 0
        var gainL = 0.7f
        var gainR = 0.7f
    }

    private val rnd = Random(19)
    private val drops = Array(6) { Drop() }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val spawnChance = (0.15f + 2.6f * rate) / SAMPLE_RATE
        for (i in 0 until frames) {
            if (rnd.nextFloat() < spawnChance) {
                drops.firstOrNull { it.env < 0.001f }?.let { d ->
                    val f = (600f + tone * 1600f) * (0.85f + rnd.nextFloat() * 0.3f)
                    d.env = 0.28f + rnd.nextFloat() * 0.18f
                    val tau = 0.09f + rnd.nextFloat() * 0.13f
                    d.decay = (0.001f).pow(1f / (tau * SAMPLE_RATE))
                    d.phase = 0.0
                    d.inc = TWO_PI * f / SAMPLE_RATE
                    // ~+8% pitch rise over the ring — the classic "plink".
                    d.glide = 1.0 + 0.000012
                    d.age = 0
                    val pan = constantPowerPan(rnd)
                    d.gainL = pan.first
                    d.gainR = pan.second
                }
            }
            var l = 0f
            var r = 0f
            for (d in drops) {
                if (d.env < 0.001f) continue
                d.phase += d.inc
                d.inc *= d.glide
                var s = sin(d.phase).toFloat() * d.env
                if (d.age < 66) s += rnd.bi() * 0.15f * d.env
                d.age++
                l += s * d.gainL
                r += s * d.gainR
                d.env *= d.decay
            }
            left[i] = l
            right[i] = r
        }
    }
}
