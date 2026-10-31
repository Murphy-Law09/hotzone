package com.dsh.hotzone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * 悬浮球本体。半透明、可拖动、单击发声。
 * 不做任何"覆盖热区"的事，所以不会挡到别的应用。
 */
@SuppressLint("ViewConstructor")
class BallView(context: Context) : View(context) {

    var opacity = 0.55f
        set(value) {
            field = value
            invalidate()
        }

    // 注意：不能叫 pressed —— View 自带 setPressed()，会签名冲突
    var heldDown = false
        set(value) {
            field = value
            invalidate()
        }

    var onDown: ((BallView, MotionEvent) -> Unit)? = null
    var onMove: ((BallView, MotionEvent) -> Unit)? = null
    var onUp: ((BallView, MotionEvent) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(cx, cy) - ringPaint.strokeWidth - 1f
        if (r <= 0f) return

        val alpha = (opacity.coerceIn(0.05f, 1f) * 255f).toInt()
        val boost = if (heldDown) 1.4f else 1f

        ringPaint.color = Color.argb(
            (alpha * 0.95f * boost).toInt().coerceIn(0, 255), 255, 255, 255
        )
        dotPaint.color = Color.argb(
            (alpha * 0.9f * boost).toInt().coerceIn(0, 255), 255, 255, 255
        )

        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawCircle(cx, cy, r * 0.42f, dotPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                heldDown = true
                onDown?.invoke(this, event)
            }
            MotionEvent.ACTION_MOVE -> onMove?.invoke(this, event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                heldDown = false
                onUp?.invoke(this, event)
            }
        }
        return true
    }
}
