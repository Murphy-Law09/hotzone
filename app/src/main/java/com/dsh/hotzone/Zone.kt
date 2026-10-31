package com.dsh.hotzone

import org.json.JSONObject

/**
 * 一个热区。坐标和尺寸都用屏幕比例（0..1），换机型也不会错位。
 */
data class Zone(
    var cx: Float = 0.5f,       // 中心 X / 屏宽
    var cy: Float = 0.5f,       // 中心 Y / 屏高
    var w: Float = 0.18f,       // 宽 / 屏宽
    var h: Float = 0.12f,       // 高 / 屏高
    var sound: String = "sound.mp3",
    var opacity: Float = 0.06f, // 运行模式下的不透明度，0.06 几乎看不见
    var circle: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("cx", cx.toDouble())
        put("cy", cy.toDouble())
        put("w", w.toDouble())
        put("h", h.toDouble())
        put("sound", sound)
        put("opacity", opacity.toDouble())
        put("circle", circle)
    }

    companion object {
        fun fromJson(o: JSONObject): Zone = Zone(
            cx = o.optDouble("cx", 0.5).toFloat(),
            cy = o.optDouble("cy", 0.5).toFloat(),
            w = o.optDouble("w", 0.18).toFloat(),
            h = o.optDouble("h", 0.12).toFloat(),
            sound = o.optString("sound", "sound.mp3"),
            opacity = o.optDouble("opacity", 0.06).toFloat(),
            circle = o.optBoolean("circle", true)
        )
    }
}
