package com.dsh.hotzone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * 只做一件事：把一次点击注入到屏幕上。
 *
 * 热区本身会接住触摸（所以能响），但如果热区压在别的应用的按钮上，
 * 那个按钮就收不到点击。这里的做法是：热区收到点击后，把自己临时改成
 * 「穿透」，再通过无障碍服务在同一个坐标注入一次点击 —— 这次点击就会
 * 落到下面的应用上。
 */
class TapForwardService : AccessibilityService() {

    companion object {
        @Volatile
        private var inst: TapForwardService? = null

        fun isReady(): Boolean = inst != null

        /** 在屏幕坐标 (x, y) 注入一次点击。返回是否成功提交 */
        fun tapAt(x: Float, y: Float, onDone: () -> Unit): Boolean {
            val svc = inst ?: run { onDone(); return false }
            return assertTap(svc, x, y, onDone)
        }

        private fun assertTap(svc: TapForwardService, x: Float, y: Float, onDone: () -> Unit): Boolean {
            return try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { onDone() }
                    override fun onCancelled(g: GestureDescription?) { onDone() }
                }, null)
            } catch (e: Exception) {
                onDone()
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (inst === this) inst = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要监听任何事件，只借用「注入手势」的能力
    }

    override fun onInterrupt() {
    }
}
