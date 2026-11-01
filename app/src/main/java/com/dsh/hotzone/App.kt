package com.dsh.hotzone

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** 全局播放器：单击发声、按住连发并升调 */
object Player {
    private const val SOUND = "sound.mp3"
    private const val HOLD_DELAY_MS = 200L
    private const val BURST_MS = 140L
    private const val BURST_VOL = 0.5f
    private const val HOLD_MAX = 2.0f
    private const val HOLD_TC = 1.1f
    private const val WATCHDOG_MS = 8000L
    private const val MAX_LAYERS = 3   // 连发同时最多叠 3 层，压低音频负载

    private var player: SoundPlayer? = null
    private val pitch = PitchTracker()
    private val main = Handler(Looper.getMainLooper())

    private val layers = ArrayList<Int>()   // 仍在响的 stream id（用于限层）
    private var burst: Runnable? = null
    private var watchdog: Runnable? = null
    private var startRate = 1f
    private var startAt = 0L

    fun ensure(ctx: Context) {
        if (player == null) {
            player = SoundPlayer(ctx.applicationContext)
            player?.preload(SOUND)
        }
    }

    /** 按下：立即响一次，并准备连发 */
    fun down(ctx: Context) {
        ensure(ctx)
        val now = SystemClock.uptimeMillis()
        val rate = pitch.next(now)
        startRate = rate
        startAt = now
        player?.play(SOUND, rate, 1f)?.let { if (it != 0) layers.add(it) }
        trimLayers()
        startBurst()
    }

    /** 抬起：停止连发（已经响完的不截断） */
    fun up() {
        burst?.let { main.removeCallbacks(it) }
        watchdog?.let { main.removeCallbacks(it) }
        burst = null
        watchdog = null
    }

    private fun startBurst() {
        up()
        val r = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - startAt
                val rate = pitch.holdRate(startRate, elapsed, HOLD_MAX, HOLD_TC)
                player?.play(SOUND, rate, BURST_VOL)?.let { if (it != 0) layers.add(it) }
                trimLayers()
                main.postDelayed(this, BURST_MS)
            }
        }
        burst = r
        main.postDelayed(r, HOLD_DELAY_MS)

        // 兜底：万一抬起事件没到，8 秒后强制停
        val w = Runnable { up() }
        watchdog = w
        main.postDelayed(w, WATCHDOG_MS)
    }

    /** 超过 MAX_LAYERS 就停掉最老的层（可能已自然播完，stop 无害） */
    private fun trimLayers() {
        while (layers.size > MAX_LAYERS) {
            player?.stop(layers.removeAt(0))
        }
    }

    /** 主界面「测试音效」用 */
    fun tapOnce(ctx: Context) {
        ensure(ctx)
        player?.play(SOUND, pitch.next(SystemClock.uptimeMillis()), 1f)
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Player.ensure(this)
    }
}
