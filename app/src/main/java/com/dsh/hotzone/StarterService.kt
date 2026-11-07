package com.dsh.hotzone

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 这个 Service 不是跑在 App 进程里，而是由 Shizuku 以 shell 身份启动。
 * 所以这里 Runtime.exec 出来的进程天然拥有 shell 权限。
 */
class StarterService : Service() {

    private val binder = object : IStarter.Stub() {
        override fun startSh(cmd: String) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            } catch (e: Exception) {
            }
        }

        override fun execSh(cmd: String): String {
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val out = p.inputStream.bufferedReader().readText()
                p.errorStream.close()
                p.waitFor()
                out
            } catch (e: Exception) {
                ""
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
