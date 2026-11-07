package com.dsh.hotzone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后尝试拉起保活服务和检测脚本（Shizuku 若没起来会失败，下次打开 App 会再试） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val ctx = context ?: return
        KeepAliveService.ensure(ctx)
        if (Shell.available() && Shell.hasPermission()) {
            Shell.startDaemon(ctx) { }
        }
    }
}
