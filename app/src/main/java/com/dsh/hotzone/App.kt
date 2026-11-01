package com.dsh.hotzone

import android.app.Application
import android.content.Context
import android.os.SystemClock

/** 全局单例：音效播放器 + 音高跟踪 */
object Player {
    private var player: SoundPlayer? = null
    private val pitch = PitchTracker()

    fun ensure(ctx: Context) {
        if (player == null) {
            player = SoundPlayer(ctx.applicationContext)
            player?.preload("sound.mp3")
        }
    }

    fun tap() {
        val rate = pitch.next(SystemClock.uptimeMillis())
        player?.play("sound.mp3", rate, 1f)
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Player.ensure(this)
    }
}
