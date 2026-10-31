package com.dsh.hotzone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var btnGrant: Button
    private lateinit var btnStart: Button
    private lateinit var btnEdit: Button
    private lateinit var btnDone: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        btnGrant = findViewById(R.id.btnGrant)
        btnStart = findViewById(R.id.btnStart)
        btnEdit = findViewById(R.id.btnEdit)
        btnDone = findViewById(R.id.btnDone)
        btnStop = findViewById(R.id.btnStop)

        btnGrant.setOnClickListener { openOverlaySettings() }
        btnStart.setOnClickListener { startOverlay() }
        btnStop.setOnClickListener { stopOverlay() }
        btnEdit.setOnClickListener { sendAction(OverlayService.ACTION_EDIT) }
        btnDone.setOnClickListener { sendAction(OverlayService.ACTION_RUN) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val hasPerm = Settings.canDrawOverlays(this)
        val running = OverlayService.running
        val editing = OverlayService.editing

        btnGrant.visibility = if (hasPerm) View.GONE else View.VISIBLE
        btnStart.visibility = if (running) View.GONE else View.VISIBLE
        btnStop.visibility = if (running) View.VISIBLE else View.GONE
        btnEdit.visibility = if (running && !editing) View.VISIBLE else View.GONE
        btnDone.visibility = if (running && editing) View.VISIBLE else View.GONE

        status.text = when {
            !hasPerm -> getString(R.string.need_overlay)
            editing -> getString(R.string.status_edit)
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
                Log.e("MainActivity", "cannot open overlay settings", e2)
            }
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "start service failed", e)
            android.widget.Toast.makeText(
                this,
                "启动失败：" + (e.message ?: e.javaClass.simpleName),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        btnStart.postDelayed({ refresh() }, 400)
    }

    private fun stopOverlay() {
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
        btnEdit.postDelayed({ refresh() }, 200)
    }
}
