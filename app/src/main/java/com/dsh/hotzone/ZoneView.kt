package com.dsh.hotzone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * 单个热区的视图。运行模式下几乎透明，编辑模式下显示蓝色描边。
 */
@SuppressLint("ViewConstructor")
class ZoneView(context: Context, val zone: Zone) : View(context) {

    var editing = false
        set(value) {
            field = value
            invalidate()
        }

    // 注意：不能叫 selected —— View 自带 setSelected()，会产生 JVM 签名冲突
    var highlighted = false
        set(value) {
            field = value
            invalidate()
        }

    var onDown: ((ZoneView, MotionEvent) -> Unit)? = null
    var onMove: ((ZoneView, MotionEvent) -> Unit)? = null
    var onUp: ((ZoneView) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF58A6FF.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val alpha = (zone.opacity.coerceIn(0f, 1f) * 255f).toInt()

        if (editing) {
            fill.color = if (highlighted) Color.argb(90, 255, 200, 0) else Color.argb(60, 88, 166, 255)
            stroke.color = if (highlighted) 0xFFFFC000.toInt() else 0xFF58A6FF.toInt()
            stroke.strokeWidth = (if (highlighted) 3.5f else 2f) * density
        } else {
            fill.color = Color.argb(alpha, 255, 255, 255)
        }

        if (zone.circle) {
            val r = minOf(cx, cy) - stroke.strokeWidth
            if (r <= 0f) return
            canvas.drawCircle(cx, cy, r, fill)
            if (editing) canvas.drawCircle(cx, cy, r, stroke)
        } else {
            val p = stroke.strokeWidth
            canvas.drawRect(p, p, w - p, h - p, fill)
            if (editing) canvas.drawRect(p, p, w - p, h - p, stroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown?.invoke(this, event)
            MotionEvent.ACTION_MOVE -> onMove?.invoke(this, event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUp?.invoke(this)
        }
        return true
    }
}
