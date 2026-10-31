package com.dsh.hotzone

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/**
 * SoundPool 封装。它天然支持「同一个音效同时播放多份」和「每个实例独立变速」，
 * 正好对应连点升调 + 长按叠层。
 */
class SoundPlayer(private val ctx: Context) {

    private val pool: SoundPool
    private val ids = HashMap<String, Int>()
    private val loading = HashSet<String>()

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder()
            .setMaxStreams(24)
            .setAudioAttributes(attrs)
            .build()
    }

    fun preload(name: String) {
        synchronized(ids) {
            if (ids.containsKey(name) || loading.contains(name)) return
            loading.add(name)
        }
        try {
            ctx.assets.openFd(name).use { afd ->
                val id = pool.load(afd, 1)
                synchronized(ids) { ids[name] = id }
            }
        } catch (e: Exception) {
            Log.e(TAG, "preload $name failed", e)
        } finally {
            synchronized(ids) { loading.remove(name) }
        }
    }

    fun play(name: String, rate: Float, volume: Float = 1f): Int {
        val id = synchronized(ids) { ids[name] } ?: return 0
        return try {
            pool.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2.0f))
        } catch (e: Exception) {
            0
        }
    }

    fun stop(streamId: Int) {
        if (streamId == 0) return
        try {
            pool.stop(streamId)
        } catch (e: Exception) {
            // 已结束
        }
    }

    fun release() {
        try {
            pool.release()
        } catch (e: Exception) {
        }
    }

    private companion object {
        const val TAG = "SoundPlayer"
    }
}
