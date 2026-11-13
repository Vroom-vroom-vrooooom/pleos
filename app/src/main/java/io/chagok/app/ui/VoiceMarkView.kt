package io.chagok.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Chagok's voice mark (design v4 `.mark`): a circle with three stacked bars that breathe while Chagok is talking. */
class VoiceMarkView(context: Context, private val circle: Int, private val bar: Int) : View(context) {
    var talking = false
        set(value) {
            if (field == value) return
            field = value
            if (value) anim.start() else { anim.cancel(); phase = 0f }
            invalidate()
        }
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        paint.color = circle; canvas.drawCircle(cx, cy, s / 2, paint)
        paint.color = bar
        // .mark.lg: bars 38/27/16 wide, 6 tall, 5 apart on an 88 circle.
        val h = s * 6 / 88; val gap = s * 5 / 88
        var y = cy - (3 * h + 2 * gap) / 2
        listOf(38f, 27f, 16f).forEachIndexed { i, w ->
            val t = ((phase - i * 0.14f) % 1f + 1f) % 1f
            val half = s * w / 88 * (if (talking) 1f - 0.5f * sin(PI * t).toFloat() else 1f) / 2
            canvas.drawRoundRect(cx - half, y, cx + half, y + h, h / 2, h / 2, paint)
            y += h + gap
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (talking) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
}
