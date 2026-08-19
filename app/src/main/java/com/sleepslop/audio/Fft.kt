package com.sleepslop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Minimal in-place iterative radix-2 complex FFT. Size must be a power of two. */
object Fft {

    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        // Butterflies.
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vR = re[b] * curR - im[b] * curI
                    val vI = re[b] * curI + im[b] * curR
                    re[b] = (re[a] - vR).toFloat()
                    im[b] = (im[a] - vI).toFloat()
                    re[a] = (re[a] + vR).toFloat()
                    im[a] = (im[a] + vI).toFloat()
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
