package com.dsh.hotzone

import android.content.Context
import org.json.JSONArray

object ZoneStore {
    private const val PREFS = "hotzone_prefs"
    private const val KEY = "zones"

    fun load(ctx: Context): MutableList<Zone> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return defaultList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Zone>()
            for (i in 0 until arr.length()) {
                out.add(Zone.fromJson(arr.getJSONObject(i)))
            }
            if (out.isEmpty()) defaultList() else out
        } catch (e: Exception) {
            defaultList()
        }
    }

    fun save(ctx: Context, zones: List<Zone>) {
        val arr = JSONArray()
        zones.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    /** 第一次启动：给一个右侧的热区，用户进编辑模式后可以拖动/增删 */
    private fun defaultList(): MutableList<Zone> = mutableListOf(
        Zone(cx = 0.84f, cy = 0.5f, w = 0.24f, h = 0.14f)
    )
}
