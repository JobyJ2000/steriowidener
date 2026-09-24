package com.example.stereowidener

/**
 * A single second-order IIR "peaking" (bell) filter, per the RBJ Audio EQ
 * Cookbook. One instance = one band, holding both its coefficients and its
 * own delay-line state (so left/right channels need separate instances).
 */
class BiquadFilter {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun setPeakingCoefficients(freqHz: Double, sampleRateHz: Double, gainDb: Double, q: Double) {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2 * Math.PI * freqHz / sampleRateHz
        val alpha = Math.sin(w0) / (2 * q)
        val cosw0 = Math.cos(w0)

        val b0d = 1 + alpha * a
        val b1d = -2 * cosw0
        val b2d = 1 - alpha * a
        val a0d = 1 + alpha / a
        val a1d = -2 * cosw0
        val a2d = 1 - alpha / a

        b0 = (b0d / a0d).toFloat()
        b1 = (b1d / a0d).toFloat()
        b2 = (b2d / a0d).toFloat()
        a1 = (a1d / a0d).toFloat()
        a2 = (a2d / a0d).toFloat()
    }

    fun process(input: Float): Float {
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = input
        y2 = y1; y1 = output
        return output
    }
}
