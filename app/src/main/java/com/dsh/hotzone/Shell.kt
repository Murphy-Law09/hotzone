package com.dsh.hotzone

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku 以 shell 身份执行命令。
 * 只做三件事：检查状态、启动检测脚本、停止检测脚本。
 */
object Shell {

    const val SCRIPT = "/data/local/tmp/tapd.sh"
    private const val LOG = "/data/local/tmp/tapd.err"

    /** Shizuku 服务在不在 */
    fun available(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** 本应用是否已获得 Shizuku 授权 */
    fun hasPermission(): Boolean = try {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission(code: Int) {
        try {
            Shizuku.requestPermission(code)
        } catch (e: Throwable) {
        }
    }

    /** 执行一条 shell 命令，返回 stdout */
    fun exec(cmd: String): String = try {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val out = p.inputStream.bufferedReader().readText()
        p.errorStream.close()
        p.waitFor()
        out
    } catch (e: Throwable) {
        ""
    }

    /** 检测脚本是否在跑 */
    fun daemonRunning(): Boolean {
        if (!hasPermission()) return false
        val out = exec("pgrep -f '[t]apd.sh' 2>/dev/null | head -1").trim()
        return out.isNotEmpty() && out.toIntOrNull() != null
    }

    /** 脚本文件在不在 */
    fun scriptExists(): Boolean {
        if (!hasPermission()) return false
        val out = exec("test -f $SCRIPT && echo yes || echo no").trim()
        return out == "yes"
    }

    /** 启动检测脚本（已在跑就什么都不做） */
    fun startDaemon(): Boolean {
        if (!hasPermission()) return false
        if (!scriptExists()) return false
        if (daemonRunning()) return true
        exec("nohup sh $SCRIPT > $LOG 2>&1 &")
        Thread.sleep(600)
        return daemonRunning()
    }

    fun stopDaemon() {
        if (!hasPermission()) return
        exec("pkill -f '[t]apd.sh' 2>/dev/null")
    }
}
