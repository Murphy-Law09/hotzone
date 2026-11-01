package com.dsh.hotzone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 热区可视化编辑器：把屏幕按比例缩成一块画布，
 * 你拖动红框移动位置，拖四角改大小。
 */
@SuppressLint("ViewConstructor")
class ZoneEditorView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var screenW = 1080f
    var screenH = 2400f

    val rect = RectF(0f, 1000f, 1080f, 1400f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55FF5555
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = 0xFFFF5555.toInt()
    }
    private val bg = Paint().apply { color = 0xFF141A26.toInt() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x18FFFFFF
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 12f), 0f)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF5555.toInt()
    }

    private val density = resources.displayMetrics.density
    private val handleR = 13f * density

    private var mode = 0
    private var downSX = 0f
    private var downSY = 0f
    private val startRect = RectF()

    fun setZone(x1: Float, y1: Float, x2: Float, y2: Float) {
        rect.set(x1.coerceIn(0f, screenW), y1.coerceIn(0f, screenH),
                 x2.coerceIn(0f, screenW), y2.coerceIn(0f, screenH))
        if (rect.right - rect.left < 20f) rect.right = rect.left + 20f
        if (rect.bottom - rect.top < 20f) rect.bottom = rect.top + 20f
        invalidate()
    }

    private fun tx(v: Float) = v / screenW * width
    private fun ty(v: Float) = v / screenH * height
    private fun fx(v: Float) = v / width * screenW
    private fun fy(v: Float) = v / height * screenH

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

        for (i in 1..3) {
            val x = width * i / 4f
            canvas.drawLine(x, 0f, x, height.toFloat(), grid)
            val y = height * i / 4f
            canvas.drawLine(0f, y, width.toFloat(), y, grid)
        }

        val r = RectF(tx(rect.left), ty(rect.top), tx(rect.right), ty(rect.bottom))
        canvas.drawRect(r, fill)
        canvas.drawRect(r, stroke)

        val corners = listOf(
            floatArrayOf(r.left, r.top), floatArrayOf(r.right, r.top),
            floatArrayOf(r.left, r.bottom), floatArrayOf(r.right, r.bottom)
        )
        for (c in corners) {
            canvas.drawCircle(c[0], c[1], handleR * 0.75f, handle)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hitMode(x, y)
                if (mode == 0) return false
                downSX = x
                downSY = y
                startRect.set(rect)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) return false
                val dx = fx(x) - fx(downSX)
                val dy = fy(y) - fy(downSY)
                when (mode) {
                    1 -> {
                        var nl = startRect.left + dx
                        var nt = startRect.top + dy
                        nl = nl.coerceIn(0f, screenW - startRect.width())
                        nt = nt.coerceIn(0f, screenH - startRect.height())
                        rect.set(nl, nt, nl + startRect.width(), nt + startRect.height())
                    }
                    2 -> {
                        rect.left = (startRect.left + dx).coerceIn(0f, rect.right - 20f)
                        rect.top = (startRect.top + dy).coerceIn(0f, rect.bottom - 20f)
                    }
                    3 -> {
                        rect.right = (startRect.right + dx).coerceIn(rect.left + 20f, screenW)
                        rect.top = (startRect.top + dy).coerceIn(0f, rect.bottom - 20f)
                    }
                    4 -> {
                        rect.left = (startRect.left + dx).coerceIn(0f, rect.right - 20f)
                        rect.bottom = (startRect.bottom + dy).coerceIn(rect.top + 20f, screenH)
                    }
                    5 -> {
                        rect.right = (startRect.right + dx).coerceIn(rect.left + 20f, screenW)
                        rect.bottom = (startRect.bottom + dy).coerceIn(rect.top + 20f, screenH)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    private fun hitMode(x: Float, y: Float): Int {
        val r = RectF(tx(rect.left), ty(rect.top), tx(rect.right), ty(rect.bottom))
        val t = handleR * 1.8f
        if (abs(x - r.left) < t && abs(y - r.top) < t) return 2
        if (abs(x - r.right) < t && abs(y - r.top) < t) return 3
        if (abs(x - r.left) < t && abs(y - r.bottom) < t) return 4
        if (abs(x - r.right) < t && abs(y - r.bottom) < t) return 5
        if (r.contains(x, y)) return 1
        return 0
    }
}
