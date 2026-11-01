package com.dsh.hotzone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收来自触摸检测脚本的广播。
 * 脚本读 /dev/input 判断是否命中热区，命中就发这个广播。
 */
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION) return
        val ctx = context ?: return
        Player.ensure(ctx)
        Player.tap()
    }

    companion object {
        const val ACTION = "com.dsh.hotzone.TAP"
    }
}
