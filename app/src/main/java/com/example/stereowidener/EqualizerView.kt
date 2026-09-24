package com.example.stereowidener

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Draws 48 vertical bars, one per EQ band, centered on a 0 dB line.
 * Dragging anywhere sets that band's gain (top of view = +12dB, bottom = -12dB).
 */
class EqualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onBandChanged: ((index: Int, gainDb: Float) -> Unit)? = null

    private val bandCount = EqualizerEngine.BAND_COUNT
    private val maxDb = EqualizerEngine.MAX_GAIN_DB
    private val gains = FloatArray(bandCount)

    private val barPaint = Paint().apply { color = Color.parseColor("#4CAF50") }
    private val trackPaint = Paint().apply { color = Color.parseColor("#2A2A2A") }
    private val zeroLinePaint = Paint().apply { color = Color.parseColor("#777777"); strokeWidth = 2f }

    fun setGain(index: Int, gainDb: Float) {
        if (index !in gains.indices) return
        gains[index] = gainDb.coerceIn(-maxDb, maxDb)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val bandWidth = w / bandCount
        val zeroY = h / 2f
        canvas.drawLine(0f, zeroY, w, zeroY, zeroLinePaint)

        for (i in 0 until bandCount) {
            val left = i * bandWidth + bandWidth * 0.15f
            val right = (i + 1) * bandWidth - bandWidth * 0.15f
            canvas.drawRect(left, 0f, right, h, trackPaint)

            val norm = (gains[i] / maxDb).coerceIn(-1f, 1f)
            val top: Float
            val bottom: Float
            if (norm >= 0f) {
                top = zeroY - norm * zeroY
                bottom = zeroY
            } else {
                top = zeroY
                bottom = zeroY - norm * zeroY
            }
            canvas.drawRect(left, top, right, bottom, barPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (width == 0 || height == 0) return true
                val bandWidth = width / bandCount.toFloat()
                val index = (event.x / bandWidth).toInt().coerceIn(0, bandCount - 1)
                // top of view = +maxDb, bottom = -maxDb
                val norm = (1f - (event.y / height) * 2f).coerceIn(-1f, 1f)
                val gainDb = norm * maxDb
                gains[index] = gainDb
                onBandChanged?.invoke(index, gainDb)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
