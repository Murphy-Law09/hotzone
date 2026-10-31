package com.dsh.hotzone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 常驻前台服务：把所有热区作为悬浮窗铺在系统最上层。
 * 热区本身就是一个窗口，触摸直接落在它上面 —— 不需要无障碍，也不偷看别的应用。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.dsh.hotzone.action.START"
        const val ACTION_STOP = "com.dsh.hotzone.action.STOP"
        const val ACTION_EDIT = "com.dsh.hotzone.action.EDIT"
        const val ACTION_RUN = "com.dsh.hotzone.action.RUN"

        private const val CHANNEL_ID = "hotzone_running"
        private const val NOTI_ID = 1001
        private const val TAG = "OverlayService"

        // 与网页版一致的手感
        private const val HOLD_DELAY_MS = 200L      // 按住多久进入连发
        private const val BURST_INTERVAL_MS = 140L  // 连发叠层间隔
        private const val BURST_VOLUME = 0.5f
        private const val HOLD_MAX_RATE = 2.0f
        private const val HOLD_TC_SEC = 1.1f

        @Volatile
        var running = false
            private set

        @Volatile
        var editing = false
            private set
    }

    private lateinit var wm: WindowManager
    private var player: SoundPlayer? = null
    private lateinit var zones: MutableList<Zone>
    private val views = ArrayList<ZoneView>()
    private var editLayer: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val pitch = PitchTracker()
    private val holds = HashMap<ZoneView, Hold>()

    private var dragStartX = 0
    private var dragStartY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f

    private class Hold {
        var startRate = 1f
        var startAt = 0L
        var ticker: Runnable? = null
        var bursting = false
        val streams = ArrayList<Int>()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        player = SoundPlayer(this)
        zones = ZoneStore.load(this)

        createChannel()
        startAsForeground(buildNotification())

        running = true
        zones.forEach { player?.preload(it.sound) }
        rebuild()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EDIT -> setEditing(true)
            ACTION_RUN -> setEditing(false)
            else -> Unit
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        holds.keys.toList().forEach { playUp(it) }
        holds.clear()
        removeAllViews()
        player?.release()
        player = null
        running = false
        editing = false
        super.onDestroy()
    }

    // ---------------- 窗口构建 ----------------

    private fun screenW(): Int = resources.displayMetrics.widthPixels
    private fun screenH(): Int = resources.displayMetrics.heightPixels

    private fun rebuild() {
        removeAllViews()
        if (editing) addEditLayer()
        zones.forEach { addZoneView(it) }
    }

    private fun addZoneView(zone: Zone) {
        val v = ZoneView(this, zone)
        v.editing = editing
        v.onDown = { zv, e -> if (editing) dragDown(zv, e) else playDown(zv) }
        v.onMove = { zv, e -> if (editing) dragMove(zv, e) }
        v.onUp = { zv -> if (editing) dragUp(zv) else playUp(zv) }

        try {
            wm.addView(v, makeLp(zone))
            views.add(v)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
        }
    }

    private fun makeLp(zone: Zone): WindowManager.LayoutParams {
        val sw = screenW()
        val sh = screenH()
        val w = (zone.w * sw).roundToInt().coerceAtLeast(36)
        val h = (zone.h * sh).roundToInt().coerceAtLeast(36)

        val lp = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (zone.cx * sw).roundToInt() - w / 2
        lp.y = (zone.cy * sh).roundToInt() - h / 2
        return lp
    }

    private fun addEditLayer() {
        val layer = View(this)
        layer.setBackgroundColor(0x15000000)
        layer.isClickable = true
        layer.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                addZoneAt(e.rawX, e.rawY)
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START

        try {
            wm.addView(layer, lp)
            editLayer = layer
        } catch (e: Exception) {
            Log.e(TAG, "add edit layer failed", e)
        }
    }

    private fun removeAllViews() {
        views.forEach { v ->
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
        views.clear()
        editLayer?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        editLayer = null
    }

    // ---------------- 编辑模式 ----------------

    private fun setEditing(value: Boolean) {
        if (editing == value) return
        holds.keys.toList().forEach { playUp(it) }
        editing = value
        rebuild()
        updateNotification()
    }

    private fun addZoneAt(x: Float, y: Float) {
        val sw = screenW().toFloat()
        val sh = screenH().toFloat()
        val z = Zone(
            cx = (x / sw).coerceIn(0f, 1f),
            cy = (y / sh).coerceIn(0f, 1f),
            w = 0.22f,
            h = 0.14f
        )
        zones.add(z)
        ZoneStore.save(this, zones)
        addZoneView(z)
    }

    private fun dragDown(v: ZoneView, e: MotionEvent) {
        val lp = v.layoutParams as WindowManager.LayoutParams
        dragStartX = lp.x
        dragStartY = lp.y
        dragTouchX = e.rawX
        dragTouchY = e.rawY
    }

    private fun dragMove(v: ZoneView, e: MotionEvent) {
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.x = dragStartX + (e.rawX - dragTouchX).toInt()
        lp.y = dragStartY + (e.rawY - dragTouchY).toInt()
        try {
            wm.updateViewLayout(v, lp)
        } catch (ex: Exception) {
        }
    }

    private fun dragUp(v: ZoneView) {
        val lp = v.layoutParams as WindowManager.LayoutParams
        val sw = screenW().toFloat()
        val sh = screenH().toFloat()
        v.zone.cx = ((lp.x + lp.width / 2f) / sw).coerceIn(0f, 1f)
        v.zone.cy = ((lp.y + lp.height / 2f) / sh).coerceIn(0f, 1f)
        ZoneStore.save(this, zones)
    }

    // ---------------- 播放 ----------------

    private fun playDown(v: ZoneView) {
        val zone = v.zone
        val now = SystemClock.uptimeMillis()
        val rate = pitch.next(now)

        val hold = Hold()
        hold.startRate = rate
        hold.startAt = now
        hold.streams.add(player?.play(zone.sound, rate, 1f) ?: 0)

        val ticker = object : Runnable {
            override fun run() {
                val h = holds[v] ?: return
                if (!h.bursting) {
                    h.bursting = true
                    h.startAt = SystemClock.uptimeMillis()
                }
                val el = SystemClock.uptimeMillis() - h.startAt
                val r = pitch.holdRate(h.startRate, el, HOLD_MAX_RATE, HOLD_TC_SEC)
                h.streams.add(player?.play(zone.sound, r, BURST_VOLUME) ?: 0)
                main.postDelayed(this, BURST_INTERVAL_MS)
            }
        }
        hold.ticker = ticker
        holds[v] = hold
        main.postDelayed(ticker, HOLD_DELAY_MS)
    }

    private fun playUp(v: ZoneView) {
        val hold = holds.remove(v) ?: return
        hold.ticker?.let { main.removeCallbacks(it) }
        if (hold.bursting) {
            hold.streams.forEach { player?.stop(it) }
        }
        hold.streams.clear()
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleAction = if (editing) ACTION_RUN else ACTION_EDIT
        val toggleLabel = if (editing) getString(R.string.noti_done) else getString(R.string.noti_edit)

        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val editIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_edit)
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)

        return builder
            .setContentTitle(getString(R.string.noti_title))
            .setContentText(if (editing) getString(R.string.status_edit) else getString(R.string.noti_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(editIcon, toggleLabel, toggle).build())
            .addAction(Notification.Action.Builder(stopIcon, getString(R.string.noti_stop), stop).build())
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
        }
    }

    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTI_ID, n)
        }
    }
}
