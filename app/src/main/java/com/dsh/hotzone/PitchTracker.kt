package com.dsh.hotzone

import kotlin.math.exp

/**
 * 点击速度 → 音高。与网页版同一套手感参数。
 */
class PitchTracker {
    var minRate = 1.0f
    var maxRate = 1.85f
    var refGap = 420f
    var minGap = 85f
    var smoothing = 0.3f
    var resetGap = 900L

    private var last = -1L
    private var level = 0f

    fun next(now: Long): Float {
        if (last < 0 || now - last > resetGap) {
            last = now
            level = 0f
            return minRate
        }
        val gap = (now - last).toFloat()
        last = now
        if (gap <= 0f) return current()

        val span = refGap - minGap
        var t = if (span <= 0f) 1f else (refGap - gap) / span
        t = t.coerceIn(0f, 1f)
        level = (level * (1f - smoothing) + t * smoothing).coerceIn(0f, 1f)
        return current()
    }

    fun current(): Float = minRate + (maxRate - minRate) * level

    /** 长按连发时的音高：从 start 指数爬向上限 */
    fun holdRate(start: Float, elapsedMs: Long, maxHoldRate: Float, timeConstantSec: Float): Float {
        if (start >= maxHoldRate - 0.02f) return start
        if (timeConstantSec <= 0f) return maxHoldRate
        val el = elapsedMs / 1000f
        return maxHoldRate + (start - maxHoldRate) * exp(-el / timeConstantSec)
    }
}
