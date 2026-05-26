package com.voicex.app.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barCount = 48
    private val bars = FloatArray(barCount)
    private var currentLevel = 0f
    private var animPhase = 0f

    private val bgColor = Color.parseColor("#0d1117")
    private val barColors = intArrayOf(
        Color.parseColor("#00f5ff"),
        Color.parseColor("#0080ff"),
        Color.parseColor("#ff006e")
    )

    init {
        postDelayed(object : Runnable {
            override fun run() {
                tick()
                invalidate()
                postDelayed(this, 16)
            }
        }, 16)
    }

    fun updateLevel(level: Float) { currentLevel = level }

    private fun tick() {
        animPhase += 0.08f
        for (i in bars.indices) {
            val wave = (sin(animPhase + i * 0.35f) * 0.5f + 0.5f).toFloat()
            val target = if (currentLevel > 0.01f) {
                wave * currentLevel * 0.8f + currentLevel * 0.2f
            } else {
                wave * 0.04f
            }
            bars[i] = bars[i] * 0.6f + target * 0.4f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(bgColor)
        val barW = w / (barCount * 1.4f)
        val gap = barW * 0.4f
        val totalW = barW + gap
        val offsetX = (w - totalW * barCount) / 2f
        for (i in bars.indices) {
            val barH = bars[i] * h * 0.9f
            val x = offsetX + i * totalW
            val top = (h - barH) / 2f
            val rect = RectF(x, top, x + barW, top + barH.coerceAtLeast(2f))
            val fraction = i.toFloat() / barCount
            val color = lerpColor(barColors[0], barColors[1], fraction * 2f.coerceAtMost(1f))
            val color2 = lerpColor(barColors[1], barColors[2], ((fraction * 2f) - 1f).coerceIn(0f, 1f))
            paint.color = if (fraction < 0.5f) color else color2
            paint.alpha = (160 + bars[i] * 95).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, barW / 2, barW / 2, paint)
        }
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        return Color.rgb(
            (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt(),
            (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt(),
            (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt()
        )
    }
}
