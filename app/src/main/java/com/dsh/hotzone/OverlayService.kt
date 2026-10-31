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
        private const val HOLD_WATCHDOG_MS = 8000L   // 按住超时兜底，防止定时器泄漏

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
    private var toolbar: View? = null
    private var selectedZone: Zone? = null
    private var toolbarLabel: android.widget.TextView? = null
    private var selectedView: ZoneView? = null
    private val startAt = System.currentTimeMillis()
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
        var watchdog: Runnable? = null
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
        try {
            startAsForeground(buildNotification())
            log("foreground ok")
        } catch (e: Exception) {
            log("START_FOREGROUND FAILED: " + e.javaClass.simpleName + " " + e.message)
            Log.e(TAG, "startForeground failed", e)
            throw e
        }

        running = true
        log("service created, zones=" + zones.size)
        zones.forEach { player?.preload(it.sound) }
        rebuild()
        log("rebuild done, editing=" + editing)
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
        log("service destroyed")
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
        selectedView = null
        if (editing) {
            addEditLayer()
            zones.forEach { addZoneView(it) }
            addEditToolbar()     // 最后加 → 在最上层，保证永远能点到
            val keep = selectedZone
            if (keep != null) {
                views.find { it.zone === keep }?.let { selectZone(it) }
            }
        } else {
            zones.forEach { addZoneView(it) }
        }
    }

    private fun addZoneView(zone: Zone) {
        val v = ZoneView(this, zone)
        v.editing = editing
        v.onDown = { zv, e ->
            if (editing) {
                selectZone(zv)
                dragDown(zv, e)
            } else {
                playDown(zv)
            }
        }
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

    /** 编辑模式工具栏：浮在最上层，既是退出通道，也是调节面板 */
    private fun addEditToolbar() {
        val d = resources.displayMetrics.density
        val col = android.widget.LinearLayout(this)
        col.orientation = android.widget.LinearLayout.VERTICAL
        col.setBackgroundColor(0xF00F1622.toInt())
        val pad = (10 * d).toInt()
        col.setPadding(pad, pad, pad, pad)

        val label = android.widget.TextView(this)
        label.setTextColor(0xFFB9C6D6.toInt())
        label.textSize = 11f
        col.addView(label, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        col.addView(row, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        fun btn(text: String, action: () -> Unit) {
            val b = android.widget.Button(this)
            b.text = text
            b.isAllCaps = false
            b.textSize = 12f
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(0, 0, 0, 0)
            b.setOnClickListener { action() }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            row.addView(b, lp)
        }

        btn("小") { resizeSelected(-0.02f) }
        btn("大") { resizeSelected(0.02f) }
        btn("淡") { fadeSelected(-0.02f) }
        btn("浓") { fadeSelected(0.02f) }
        btn("删") { deleteSelected() }

        val spacer = View(this)
        row.addView(spacer, android.widget.LinearLayout.LayoutParams(
            0, 1, 1f
        ))

        btn("完成") { setEditing(false) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = (36 * d).toInt()

        try {
            wm.addView(col, params)
            toolbar = col
            log("toolbar added")
        } catch (e: Exception) {
            Log.e(TAG, "add toolbar failed", e)
            log("add toolbar failed: " + e.message)
        }

        toolbarLabel = label
        updateLabel()
    }

    private fun updateLabel() {
        val z = selectedZone
        toolbarLabel?.text = if (z == null) {
            "编辑模式 · 拖圆圈移动 · 点空白处新增"
        } else {
            "已选中 · 大小 " + (z.w * 100).toInt() + "% · 透明度 " + (z.opacity * 100).toInt() + "%"
        }
    }

    private fun selectZone(v: ZoneView) {
        if (selectedView === v) return
        selectedView?.selected = false
        selectedView = v
        selectedZone = v.zone
        v.selected = true
        updateLabel()
    }

    private fun resizeSelected(delta: Float) {
        val v = selectedView ?: return
        val zone = v.zone
        zone.w = (zone.w + delta).coerceIn(0.05f, 1.0f)
        zone.h = (zone.h + delta).coerceIn(0.05f, 1.0f)
        try {
            wm.updateViewLayout(v, makeLp(zone))
        } catch (e: Exception) {
        }
        v.invalidate()
        ZoneStore.save(this, zones)
        updateLabel()
    }

    private fun fadeSelected(delta: Float) {
        val v = selectedView ?: return
        val zone = v.zone
        zone.opacity = (zone.opacity + delta).coerceIn(0f, 1f)
        v.invalidate()
        ZoneStore.save(this, zones)
        updateLabel()
    }

    private fun deleteSelected() {
        val v = selectedView ?: return
        val zone = v.zone
        views.remove(v)
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
        zones.remove(zone)
        selectedView = null
        selectedZone = null
        ZoneStore.save(this, zones)
        updateLabel()
    }

    /** 把关键事件写进 App 私有目录，出问题时可以用文件管理器取出来 */
    private fun log(msg: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            java.io.File(dir, "debug.log").appendText(
                "[" + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "] " + msg + "\n"
            )
        } catch (e: Exception) {
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
        toolbar?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        toolbar = null
        toolbarLabel = null
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
        // 关键修复：如果这个热区还残留着上一次的 hold（UP 没送到），先彻底清掉
        // 否则那个 140ms 的连发定时器会一直跑，几秒钟就把 SoundPool 声道占满
        holds.remove(v)?.let { old ->
            old.ticker?.let { main.removeCallbacks(it) }
            old.watchdog?.let { main.removeCallbacks(it) }
            old.streams.forEach { player?.stop(it) }
            old.streams.clear()
        }

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

        // 8 秒兜底：万一 UP 事件丢了，强制把这次按住结束掉，绝不无限连发
        val dog = Runnable {
            if (holds[v] === hold) {
                log("watchdog stopped stuck hold")
                playUp(v)
            }
        }
        hold.watchdog = dog
        main.postDelayed(dog, HOLD_WATCHDOG_MS)

        holds[v] = hold
        main.postDelayed(ticker, HOLD_DELAY_MS)
    }

    private fun playUp(v: ZoneView) {
        val hold = holds.remove(v) ?: return
        hold.ticker?.let { main.removeCallbacks(it) }
        hold.watchdog?.let { main.removeCallbacks(it) }
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
