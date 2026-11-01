package com.dsh.hotzone

import android.app.Activity
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var editor: ZoneEditorView
    private lateinit var coordLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        editor = findViewById(R.id.editor)
        coordLabel = findViewById(R.id.coordLabel)

        Player.ensure(this)

        // 按真实屏幕尺寸设置画布比例
        val dm: DisplayMetrics = resources.displayMetrics
        editor.screenW = dm.widthPixels.toFloat()
        editor.screenH = dm.heightPixels.toFloat()
        loadZone()
        updateLabel()

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            Player.tapOnce(this)
            status.text = getString(R.string.status_played)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveZone()
            updateLabel()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun zonesFile(): File {
        val dir = getExternalFilesDir(null) ?: filesDir
        return File(dir, "zones.txt")
    }

    private fun loadZone() {
        try {
            val f = zonesFile()
            if (!f.exists()) return
            for (line in f.readLines()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val p = t.split(Regex("\\s+"))
                if (p.size >= 4) {
                    editor.setZone(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                    break
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun saveZone() {
        try {
            val r = editor.rect
            zonesFile().writeText(
                "# 热区响 · 由 App 保存\n" +
                "${r.left.toInt()} ${r.top.toInt()} ${r.right.toInt()} ${r.bottom.toInt()}\n"
            )
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：" + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateLabel() {
        val r = editor.rect
        coordLabel.text = getString(R.string.coord_label) + " " +
                "${r.left.toInt()}, ${r.top.toInt()}  →  ${r.right.toInt()}, ${r.bottom.toInt()}"
    }
}
