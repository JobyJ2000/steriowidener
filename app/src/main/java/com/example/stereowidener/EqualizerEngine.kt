package com.example.stereowidener

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A 48-band graphic equalizer implemented as a cascade of peaking biquads,
 * log-spaced from 20 Hz to 20 kHz. Each band's gain is independently
 * adjustable from -12 dB to +12 dB. Runs entirely in Kotlin on raw PCM
 * (no native code needed - 48 cascaded biquads per channel is cheap for a
 * modern phone CPU even at 48kHz stereo).
 */
class EqualizerEngine(initialSampleRate: Int) {

    companion object {
        const val BAND_COUNT = 48
        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20000.0
        private const val Q = 4.0 // controls how narrow each band is
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
    }

    val frequencies: DoubleArray = DoubleArray(BAND_COUNT) { i ->
        val t = i.toDouble() / (BAND_COUNT - 1)
        MIN_FREQ * Math.pow(MAX_FREQ / MIN_FREQ, t)
    }

    private val gainsDb = FloatArray(BAND_COUNT)
    private val leftBands = Array(BAND_COUNT) { BiquadFilter() }
    private val rightBands = Array(BAND_COUNT) { BiquadFilter() }

    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var sampleRate: Int = initialSampleRate

    init {
        for (i in 0 until BAND_COUNT) recalcBand(i)
    }

    private fun recalcBand(index: Int) {
        leftBands[index].setPeakingCoefficients(frequencies[index], sampleRate.toDouble(), gainsDb[index].toDouble(), Q)
        rightBands[index].setPeakingCoefficients(frequencies[index], sampleRate.toDouble(), gainsDb[index].toDouble(), Q)
    }

    fun setSampleRate(rate: Int) {
        if (rate == sampleRate) return
        sampleRate = rate
        for (i in 0 until BAND_COUNT) recalcBand(i)
    }

    fun setBandGain(index: Int, gainDb: Float) {
        if (index !in 0 until BAND_COUNT) return
        gainsDb[index] = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        recalcBand(index)
    }

    fun getBandGain(index: Int): Float = gainsDb[index]

    fun resetAll() {
        for (i in 0 until BAND_COUNT) setBandGain(i, 0f)
    }

    /** Processes interleaved 16-bit stereo PCM in place. */
    fun process(pcm: ByteArray) {
        if (!enabled) return
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = buffer.asShortBuffer()
        val n = shortBuffer.limit()
        var i = 0
        while (i + 1 < n) {
            var l = shortBuffer.get(i).toFloat()
            var r = shortBuffer.get(i + 1).toFloat()
            for (b in 0 until BAND_COUNT) {
                l = leftBands[b].process(l)
                r = rightBands[b].process(r)
            }
            l = l.coerceIn(-32768f, 32767f)
            r = r.coerceIn(-32768f, 32767f)
            shortBuffer.put(i, l.toInt().toShort())
            shortBuffer.put(i + 1, r.toInt().toShort())
            i += 2
        }
    }
}
