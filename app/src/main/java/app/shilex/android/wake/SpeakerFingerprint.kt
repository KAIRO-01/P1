package app.shilex.android.wake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A lightweight, on-device acoustic similarity fingerprint used ONLY by the offline wake
 * fallback. This is NOT a biometric speaker-verification model or a security boundary.
 * Samples of the spoken wake phrase never leave the device.
 */
object SpeakerFingerprint {
    const val VECTOR_SIZE = 48
    private const val SAMPLE_RATE = 16_000
    private const val FRAME = 512
    private const val HOP = 256
    private const val BANDS = VECTOR_SIZE / 2

    fun extract(audio: ShortArray, from: Int = 0, to: Int = audio.size): FloatArray? {
        val start = from.coerceIn(0, audio.size)
        val end = to.coerceIn(start, audio.size)
        if (end - start < FRAME * 4) return null

        val frames = ArrayList<Pair<Double, DoubleArray>>()
        var offset = start
        while (offset + FRAME <= end) {
            val real = DoubleArray(FRAME)
            val imag = DoubleArray(FRAME)
            var energy = 0.0
            for (i in 0 until FRAME) {
                val s = audio[offset + i].toDouble()
                energy += s * s
                real[i] = s * (0.5 - 0.5 * cos(2.0 * PI * i / (FRAME - 1)))
            }
            val rms = sqrt(energy / FRAME)
            if (rms > 4.0) {
                fft(real, imag)
                val bands = DoubleArray(BANDS)
                for (bin in 5 until 130) {
                    val frequency = bin.toDouble() * SAMPLE_RATE / FRAME
                    val band = (ln(frequency / 150.0) / ln(4_000.0 / 150.0) * BANDS)
                        .toInt().coerceIn(0, BANDS - 1)
                    val power = real[bin] * real[bin] + imag[bin] * imag[bin]
                    bands[band] += power
                }
                val sum = bands.sum().coerceAtLeast(1e-12)
                val spectrum = DoubleArray(BANDS) { ln(1e-5 + bands[it] / sum) }
                frames.add(rms to spectrum)
            }
            offset += HOP
        }
        if (frames.size < 5) return null
        val loudest = frames.maxOf { it.first }
        val selected = frames.filter { it.first > max(12.0, loudest * 0.2) }
        if (selected.size < 5) return null

        val result = FloatArray(VECTOR_SIZE)
        for (band in 0 until BANDS) {
            val mean = selected.sumOf { it.second[band] } / selected.size
            val variance = selected.sumOf { (it.second[band] - mean) * (it.second[band] - mean) } / selected.size
            result[band] = mean.toFloat()
            result[band + BANDS] = sqrt(variance).toFloat()
        }
        return result
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imag[i]; imag[i] = imag[j]; imag[j] = im
            }
        }
        var size = 2
        while (size <= n) {
            val half = size shr 1
            val angle = -2.0 * PI / size
            for (base in 0 until n step size) {
                for (k in 0 until half) {
                    val a = angle * k
                    val wr = cos(a); val wi = sin(a)
                    val other = base + k + half
                    val tr = wr * real[other] - wi * imag[other]
                    val ti = wr * imag[other] + wi * real[other]
                    real[other] = real[base + k] - tr
                    imag[other] = imag[base + k] - ti
                    real[base + k] += tr
                    imag[base + k] += ti
                }
            }
            size = size shl 1
        }
    }
}
