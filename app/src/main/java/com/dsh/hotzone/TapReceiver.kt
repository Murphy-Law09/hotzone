package com.dsh.hotzone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 接收触摸检测脚本发来的按下 / 抬起事件 */
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        // 顺便确保保活服务在跑，避免下次冷启动
        KeepAliveService.ensure(ctx)
        when (intent?.action) {
            ACTION_DOWN -> Player.down(ctx)
            ACTION_UP -> Player.up()
        }
    }

    companion object {
        const val ACTION_DOWN = "com.dsh.hotzone.DOWN"
        const val ACTION_UP = "com.dsh.hotzone.UP"
    }
}
