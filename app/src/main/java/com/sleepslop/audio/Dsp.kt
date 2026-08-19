package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

const val SAMPLE_RATE = 44100

/**
 * Standard RBJ biquad filter. Coefficients can be retuned on the fly
 * (used by the wind generator to sweep its resonant band around).
 */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun lowpass(fc: Float, q: Float = 0.7071f): Biquad {
        val w0 = (2.0 * PI * fc / SAMPLE_RATE)
        val cw = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * q)).toFloat()
        val a0 = 1f + alpha
        b0 = (1f - cw) / 2f / a0
        b1 = (1f - cw) / a0
        b2 = b0
        a1 = -2f * cw / a0
        a2 = (1f - alpha) / a0
        return this
    }

    fun highpass(fc: Float, q: Float = 0.7071f): Biquad {
        val w0 = (2.0 * PI * fc / SAMPLE_RATE)
        val cw = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * q)).toFloat()
        val a0 = 1f + alpha
        b0 = (1f + cw) / 2f / a0
        b1 = -(1f + cw) / a0
        b2 = b0
        a1 = -2f * cw / a0
        a2 = (1f - alpha) / a0
        return this
    }

    /** Constant 0 dB peak-gain bandpass. */
    fun bandpass(fc: Float, q: Float = 1f): Biquad {
        val w0 = (2.0 * PI * fc / SAMPLE_RATE)
        val cw = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * q)).toFloat()
        val a0 = 1f + alpha
        b0 = alpha / a0
        b1 = 0f
        b2 = -alpha / a0
        a1 = -2f * cw / a0
        a2 = (1f - alpha) / a0
        return this
    }

    /** RBJ peaking EQ — boosts or cuts gainDb around fc. */
    fun peaking(fc: Float, q: Float, gainDb: Float): Biquad {
        val amp = 10f.pow(gainDb / 40f)
        val w0 = (2.0 * PI * fc / SAMPLE_RATE)
        val cw = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * q)).toFloat()
        val a0 = 1f + alpha / amp
        b0 = (1f + alpha * amp) / a0
        b1 = -2f * cw / a0
        b2 = (1f - alpha * amp) / a0
        a1 = -2f * cw / a0
        a2 = (1f - alpha / amp) / a0
        return this
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

/** Paul Kellet's economy pink noise filter — flat-ish -3 dB/octave slope. */
class PinkFilter {
    private var p0 = 0f
    private var p1 = 0f
    private var p2 = 0f
    private var p3 = 0f
    private var p4 = 0f
    private var p5 = 0f
    private var p6 = 0f

    fun next(white: Float): Float {
        p0 = 0.99886f * p0 + white * 0.0555179f
        p1 = 0.99332f * p1 + white * 0.0750759f
        p2 = 0.96900f * p2 + white * 0.1538520f
        p3 = 0.86650f * p3 + white * 0.3104856f
        p4 = 0.55000f * p4 + white * 0.5329522f
        p5 = -0.7616f * p5 - white * 0.0168980f
        val pink = p0 + p1 + p2 + p3 + p4 + p5 + p6 + white * 0.5362f
        p6 = white * 0.115926f
        return pink * 0.11f
    }
}

/** Leaky integrator turning white noise into brown (-6 dB/octave) noise. */
class BrownFilter {
    private var acc = 0f

    fun next(white: Float): Float {
        acc = (acc + 0.02f * white) / 1.02f
        return acc * 3.2f
    }
}
