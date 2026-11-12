package io.chagok.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator

/** Animation demo only; no microphone, speech synthesis or network access. */
class CompanionOrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var colorField: Shader? = null
    private var haze: Shader? = null
    private var pulse = 0f
    private var animator: ValueAnimator? = null
    var speaking = false
        set(value) {
            field = value
            contentDescription = if (value) "말하기 애니메이션 멈추기" else "말하기 애니메이션 보기"
            updateAnimation()
        }
    init {
        isClickable = true; isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "말하기 애니메이션 보기"
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        colorField = SweepGradient(w / 2f, h / 2f, intArrayOf(0xffefd4d8.toInt(), 0xffe6cff3.toInt(),
            0xffd6f3ed.toInt(), 0xffd7f8dd.toInt(), 0xfff6f8cf.toInt(), 0xfff2e6d1.toInt(), 0xffefd4d8.toInt()), null)
        haze = RadialGradient(w * .38f, h * .28f, maxOf(1f, minOf(w, h) * .65f),
            0x66ffffff, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) * .43f * (1f + pulse * .045f)
        paint.shader = colorField; canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.shader = haze; canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.shader = null
        if (isFocused) {
            paint.color = 0xff8d9e88.toInt(); paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 2
            canvas.drawCircle(width / 2f, height / 2f, radius + 5, paint)
            paint.style = Paint.Style.FILL
        }
    }
    private fun updateAnimation() {
        animator?.cancel(); animator = null; pulse = 0f
        if (speaking && isAttachedToWindow && isShown && windowVisibility == VISIBLE && !isInEditMode && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 1250; repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }; start()
            }
        }
        invalidate()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateAnimation() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"; info.isCheckable = true; info.isChecked = speaking
    }
}
