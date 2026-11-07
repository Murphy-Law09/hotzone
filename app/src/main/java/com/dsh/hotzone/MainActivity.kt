package com.dsh.hotzone

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File
import rikka.shizuku.Shizuku

class MainActivity : Activity(), Shizuku.OnRequestPermissionResultListener {

    companion object {
        private const val REQ_SHIZUKU = 100
    }

    private lateinit var status: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var editor: ZoneEditorView
    private lateinit var coordLabel: TextView
    private lateinit var btnFrame: Button
    private lateinit var btnDaemon: Button

    private var frameView: View? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        shizukuStatus = findViewById(R.id.shizukuStatus)
        editor = findViewById(R.id.editor)
        coordLabel = findViewById(R.id.coordLabel)
        btnFrame = findViewById(R.id.btnFrame)
        btnDaemon = findViewById(R.id.btnDaemon)

        Player.ensure(this)
        KeepAliveService.ensure(this)

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
        btnFrame.setOnClickListener { toggleZoneFrame() }
        btnDaemon.setOnClickListener { toggleDaemon() }

        try {
            Shizuku.addRequestPermissionResultListener(this)
        } catch (e: Throwable) {
        }

        // 打开 App 就自动把检测脚本拉起来
        autoStartDaemon()
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(this)
        } catch (e: Throwable) {
        }
        super.onDestroy()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == REQ_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
            Thread {
                Shell.startDaemon()
                main.post { refreshShizuku() }
            }.start()
        } else {
            refreshShizuku()
        }
    }

    private fun autoStartDaemon() {
        Thread {
            if (!Shell.available()) {
                main.post { refreshShizuku() }
                return@Thread
            }
            if (!Shell.hasPermission()) {
                main.post { Shell.requestPermission(REQ_SHIZUKU) }
                refreshShizuku()
                return@Thread
            }
            Shell.startDaemon()
            main.post { refreshShizuku() }
        }.start()
    }

    private fun toggleDaemon() {
        Thread {
            if (Shell.daemonRunning()) {
                Shell.stopDaemon()
            } else {
                if (!Shell.available()) {
                    main.post {
                        Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                if (!Shell.hasPermission()) {
                    main.post { Shell.requestPermission(REQ_SHIZUKU) }
                    return@Thread
                }
                val ok = Shell.startDaemon()
                if (!ok) {
                    main.post {
                        Toast.makeText(this, R.string.daemon_start_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            main.post { refreshShizuku() }
        }.start()
    }

    private fun refreshShizuku() {
        Thread {
            val avail = Shell.available()
            val perm = Shell.hasPermission()
            val running = if (perm) Shell.daemonRunning() else false
            main.post {
                shizukuStatus.text = when {
                    !avail -> getString(R.string.shizuku_not_running)
                    !perm -> getString(R.string.shizuku_no_permission)
                    running -> getString(R.string.daemon_running)
                    else -> getString(R.string.daemon_stopped)
                }
                btnDaemon.text =
                    if (running) getString(R.string.btn_daemon_stop)
                    else getString(R.string.btn_daemon_start)
            }
        }.start()
    }

    // ---------------- 热区配置 ----------------

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

    // ---------------- 热区边框 ----------------

    private fun toggleZoneFrame() {
        if (frameView != null) {
            hideZoneFrame()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
            }
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            return
        }

        val r = editor.rect
        val v = object : View(this) {
            private val dp = resources.displayMetrics.density
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * dp
                color = 0xFFFF3B30.toInt()
            }
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0x22FF3B30
            }

            override fun onDraw(c: Canvas) {
                val x1 = r.left / editor.screenW * width
                val y1 = r.top / editor.screenH * height
                val x2 = r.right / editor.screenW * width
                val y2 = r.bottom / editor.screenH * height
                c.drawRect(x1, y1, x2, y2, fillPaint)
                c.drawRect(x1, y1, x2, y2, strokePaint)
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            frameView = v
            btnFrame.text = getString(R.string.btn_frame_hide)
            v.postDelayed({ hideZoneFrame() }, 8000)
        } catch (e: Exception) {
            Toast.makeText(this, "显示失败：" + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun hideZoneFrame() {
        frameView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (e: Exception) {
            }
        }
        frameView = null
        btnFrame.text = getString(R.string.btn_frame)
    }

    override fun onPause() {
        super.onPause()
        hideZoneFrame()
    }
}
