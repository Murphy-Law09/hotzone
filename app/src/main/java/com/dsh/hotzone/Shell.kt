package com.dsh.hotzone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku 的 UserService 拿到 shell 身份，用来管理检测脚本。
 */
object Shell {

    const val SCRIPT = "/data/local/tmp/tapd.sh"
    private const val ERR = "/data/local/tmp/tapd.err"

    private var svc: IStarter? = null
    private var bound = false
    private var pending: Runnable? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                svc = IStarter.Stub.asInterface(service)
                bound = true
                pending?.run()
            } catch (e: Throwable) {
            }
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
            bound = false
        }
    }

    fun available(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

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

    /** 绑定 UserService，成功后执行 action */
    fun withShell(ctx: Context, action: (IStarter) -> Unit) {
        val current = svc
        if (bound && current != null) {
            action(current)
            return
        }
        pending = Runnable {
            svc?.let { action(it) }
        }
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(ctx.packageName, StarterService::class.java.name)
            )
                .processNameSuffix("starter")
                .debuggable(false)
                .version(1)
                .daemon(false)
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
        }
    }

    fun exec(ctx: Context, cmd: String, cb: (String) -> Unit) {
        withShell(ctx) { s ->
            Thread {
                val out = try {
                    s.execSh(cmd)
                } catch (e: Throwable) {
                    ""
                }
                cb(out)
            }.start()
        }
    }

    fun daemonRunning(ctx: Context, cb: (Boolean) -> Unit) {
        if (!hasPermission()) {
            cb(false)
            return
        }
        exec(ctx, "pgrep -f '[t]apd.sh' 2>/dev/null | head -1") { out ->
            val t = out.trim()
            cb(t.isNotEmpty() && t.toIntOrNull() != null)
        }
    }

    fun startDaemon(ctx: Context, cb: (Boolean) -> Unit) {
        if (!hasPermission()) {
            cb(false)
            return
        }
        exec(ctx, "test -f $SCRIPT && echo yes || echo no") { exists ->
            if (!exists.trim().contains("yes")) {
                cb(false)
                return@exec
            }
            daemonRunning(ctx) { running ->
                if (running) {
                    cb(true)
                    return@daemonRunning
                }
                withShell(ctx) { s ->
                    try {
                        s.startSh("nohup sh $SCRIPT > $ERR 2>&1 &")
                    } catch (e: Throwable) {
                    }
                    Thread {
                        Thread.sleep(800)
                        daemonRunning(ctx) { ok -> cb(ok) }
                    }.start()
                }
            }
        }
    }

    fun stopDaemon(ctx: Context, cb: () -> Unit = {}) {
        if (!hasPermission()) {
            cb()
            return
        }
        exec(ctx, "pkill -f '[t]apd.sh' 2>/dev/null") { cb() }
    }
}
