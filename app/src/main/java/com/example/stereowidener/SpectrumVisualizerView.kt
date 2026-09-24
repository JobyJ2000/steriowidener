package com.example.stereowidener

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

enum class VisualizerStyle(val label: String) {
    BARS("Bars"),
    MIRRORED_BARS("Mirrored Bars"),
    FILLED_CURVE("Filled Curve"),
    WAVEFORM("Waveform"),
    CIRCULAR("Circular"),
    DOT_MATRIX("Dot Matrix")
}

/**
 * A single view that can render live audio as any of several distinct
 * visualization styles. Fed by both FFT (frequency) and raw waveform (time
 * domain) data from Android's Visualizer API; each style picks whichever it
 * needs.
 */
class SpectrumVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var style: VisualizerStyle = VisualizerStyle.BARS
        set(value) { field = value; invalidate() }

    private var fftMagnitudes: FloatArray? = null
    private var waveform: ByteArray? = null

    private val barPaint = Paint().apply { color = Color.parseColor("#00E5FF") }
    private val dotPaint = Paint().apply { color = Color.parseColor("#FF4081"); isAntiAlias = true }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#00E5FF"); style = Paint.Style.STROKE
        strokeWidth = 4f; isAntiAlias = true
    }
    private val fillPaint = Paint().apply { color = Color.parseColor("#4000E5FF"); isAntiAlias = true }

    /** fft is the raw byte array from Visualizer.OnFftDataCapture (Android's compact FFT format). */
    fun updateFft(fft: ByteArray) {
        val bins = fft.size / 2
        val mags = FloatArray(bins)
        for (i in 0 until bins) {
            val re = fft[i * 2].toInt()
            val im = if (i * 2 + 1 < fft.size) fft[i * 2 + 1].toInt() else 0
            mags[i] = hypot(re.toDouble(), im.toDouble()).toFloat()
        }
        fftMagnitudes = mags
        postInvalidate()
    }

    /** wave is the raw unsigned-8-bit waveform from Visualizer.OnWaveFormDataCapture. */
    fun updateWaveform(wave: ByteArray) {
        waveform = wave
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        when (style) {
            VisualizerStyle.BARS -> drawBars(canvas, mirrored = false)
            VisualizerStyle.MIRRORED_BARS -> drawBars(canvas, mirrored = true)
            VisualizerStyle.FILLED_CURVE -> drawFilledCurve(canvas)
            VisualizerStyle.WAVEFORM -> drawWaveform(canvas)
            VisualizerStyle.CIRCULAR -> drawCircular(canvas)
            VisualizerStyle.DOT_MATRIX -> drawDotMatrix(canvas)
        }
    }

    private fun drawBars(canvas: Canvas, mirrored: Boolean) {
        val mags = fftMagnitudes ?: return
        val n = mags.size
        if (n == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val barWidth = w / n
        for (i in 0 until n) {
            val norm = (mags[i] / 128f).coerceIn(0f, 1f)
            val barHeight = norm * h
            val left = i * barWidth
            if (mirrored) {
                val center = h / 2f
                canvas.drawRect(left, center - barHeight / 2f, left + barWidth * 0.8f, center + barHeight / 2f, barPaint)
            } else {
                canvas.drawRect(left, h - barHeight, left + barWidth * 0.8f, h, barPaint)
            }
        }
    }

    private fun drawFilledCurve(canvas: Canvas) {
        val mags = fftMagnitudes ?: return
        val n = mags.size
        if (n < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        val stepX = w / (n - 1)

        val fill = Path()
        fill.moveTo(0f, h)
        val outline = Path()
        for (i in 0 until n) {
            val norm = (mags[i] / 128f).coerceIn(0f, 1f)
            val x = i * stepX
            val y = h - norm * h
            fill.lineTo(x, y)
            if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        }
        fill.lineTo(w, h)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(outline, linePaint)
    }

    private fun drawWaveform(canvas: Canvas) {
        val wave = waveform ?: return
        val n = wave.size
        if (n < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        val stepX = w / (n - 1)
        val path = Path()
        for (i in 0 until n) {
            val sample = (wave[i].toInt() and 0xFF) - 128 // unsigned byte, centered at 0
            val y = h / 2f - (sample / 128f) * (h / 2f)
            val x = i * stepX
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawCircular(canvas: Canvas) {
        val mags = fftMagnitudes ?: return
        val n = mags.size
        if (n == 0) return
        val cx = width / 2f; val cy = height / 2f
        val baseRadius = min(width, height) / 4f
        val maxExtra = min(width, height) / 4f
        for (i in 0 until n) {
            val norm = (mags[i] / 128f).coerceIn(0f, 1f)
            val angle = 2 * Math.PI * i / n
            val r1 = baseRadius
            val r2 = baseRadius + norm * maxExtra
            val x1 = cx + (r1 * cos(angle)).toFloat()
            val y1 = cy + (r1 * sin(angle)).toFloat()
            val x2 = cx + (r2 * cos(angle)).toFloat()
            val y2 = cy + (r2 * sin(angle)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, linePaint)
        }
    }

    private fun drawDotMatrix(canvas: Canvas) {
        val mags = fftMagnitudes ?: return
        val n = mags.size
        if (n == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val colWidth = w / n
        val rows = 20
        val rowHeight = h / rows
        for (i in 0 until n) {
            val norm = (mags[i] / 128f).coerceIn(0f, 1f)
            val litRows = (norm * rows).toInt()
            val cx = i * colWidth + colWidth / 2f
            for (row in 0 until litRows) {
                val cy = h - row * rowHeight - rowHeight / 2f
                canvas.drawCircle(cx, cy, min(colWidth, rowHeight) * 0.35f, dotPaint)
            }
        }
    }
}
