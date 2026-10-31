package com.dsh.hotzone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var btnGrant: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var opacityBar: SeekBar
    private lateinit var opacityLabel: TextView

    private val prefs by lazy { getSharedPreferences("hotzone_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        btnGrant = findViewById(R.id.btnGrant)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        opacityBar = findViewById(R.id.opacityBar)
        opacityLabel = findViewById(R.id.opacityLabel)

        btnGrant.setOnClickListener { openOverlaySettings() }
        btnStart.setOnClickListener { startBall() }
        btnStop.setOnClickListener { stopBall() }

        val saved = prefs.getFloat("ball_opacity", 0.55f)
        opacityBar.progress = (((saved - 0.1f) / 0.9f) * 100f).roundToInt().coerceIn(0, 100)
        updateOpacityLabel(saved)

        opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 0.1f + progress / 100f * 0.9f
                updateOpacityLabel(v)
                prefs.edit().putFloat("ball_opacity", v).apply()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {
                sendAction(OverlayService.ACTION_REFRESH)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun updateOpacityLabel(v: Float) {
        opacityLabel.text = getString(R.string.opacity_label) + " " + (v * 100).roundToInt() + "%"
    }

    private fun refresh() {
        val hasPerm = Settings.canDrawOverlays(this)
        val running = OverlayService.running

        btnGrant.visibility = if (hasPerm) View.GONE else View.VISIBLE
        btnStart.visibility = if (running) View.GONE else View.VISIBLE
        btnStop.visibility = if (running) View.VISIBLE else View.GONE

        status.text = when {
            !hasPerm -> getString(R.string.need_overlay)
            running -> getString(R.string.status_on)
            else -> getString(R.string.status_off)
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                Log.e("MainActivity", "open overlay settings failed", e2)
            }
        }
    }

    private fun startBall() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "start failed", e)
            Toast.makeText(this, "启动失败：" + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
        btnStart.postDelayed({ refresh() }, 400)
    }

    private fun stopBall() {
        try {
            stopService(Intent(this, OverlayService::class.java))
        } catch (e: Exception) {
        }
        btnStop.postDelayed({ refresh() }, 200)
    }

    private fun sendAction(action: String) {
        try {
            startService(Intent(this, OverlayService::class.java).setAction(action))
        } catch (e: Exception) {
            Log.e("MainActivity", "send action failed", e)
        }
    }
}
