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
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 常驻前台服务，在屏幕上放一个小圆球。
 * 单击发声，拖动可移动位置。不覆盖任何区域，所以不会挡到别的应用。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.dsh.hotzone.action.START"
        const val ACTION_STOP = "com.dsh.hotzone.action.STOP"
        const val ACTION_REFRESH = "com.dsh.hotzone.action.REFRESH"

        private const val CHANNEL_ID = "hotzone_running"
        private const val NOTI_ID = 1001
        private const val TAG = "OverlayService"

        private const val PREFS = "hotzone_prefs"
        private const val K_X = "ball_x"
        private const val K_Y = "ball_y"
        private const val K_OPACITY = "ball_opacity"
        private const val K_SIZE = "ball_size"

        private const val DRAG_SLOP_DP = 8f

        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private var player: SoundPlayer? = null
    private var ball: BallView? = null
    private val pitch = PitchTracker()
    private val main = Handler(Looper.getMainLooper())

    private var downRawX = 0f
    private var downRawY = 0f
    private var winStartX = 0
    private var winStartY = 0
    private var dragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        player = SoundPlayer(this)
        player?.preload("sound.mp3")

        createChannel()
        try {
            startAsForeground(buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            throw e
        }
        running = true
        addBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> applySettings()
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ball?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        ball = null
        player?.release()
        player = null
        running = false
        super.onDestroy()
    }

    // ---------------- 球 ----------------

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun addBall() {
        val size = dp(prefs().getInt(K_SIZE, 56).toFloat())
        val v = BallView(this)
        v.opacity = prefs().getFloat(K_OPACITY, 0.55f)

        v.onDown = { b, e -> onBallDown(b, e) }
        v.onMove = { b, e -> onBallMove(b, e) }
        v.onUp = { b, e -> onBallUp(b, e) }

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START

        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val px = prefs().getFloat(K_X, 0.88f)
        val py = prefs().getFloat(K_Y, 0.55f)
        lp.x = (px * sw).roundToInt() - size / 2
        lp.y = (py * sh).roundToInt() - size / 2

        try {
            wm.addView(v, lp)
            ball = v
        } catch (e: Exception) {
            Log.e(TAG, "add ball failed", e)
        }
    }

    private fun onBallDown(b: BallView, e: MotionEvent) {
        downRawX = e.rawX
        downRawY = e.rawY
        val lp = b.layoutParams as WindowManager.LayoutParams
        winStartX = lp.x
        winStartY = lp.y
        dragging = false
    }

    private fun onBallMove(b: BallView, e: MotionEvent) {
        val dx = e.rawX - downRawX
        val dy = e.rawY - downRawY
        if (!dragging && (abs(dx) > dp(DRAG_SLOP_DP) || abs(dy) > dp(DRAG_SLOP_DP))) {
            dragging = true
        }
        if (!dragging) return

        val lp = b.layoutParams as WindowManager.LayoutParams
        lp.x = winStartX + dx.toInt()
        lp.y = winStartY + dy.toInt()
        try {
            wm.updateViewLayout(b, lp)
        } catch (ex: Exception) {
        }
    }

    private fun onBallUp(b: BallView, e: MotionEvent) {
        if (dragging) {
            dragging = false
            savePosition(b)
            return
        }
        playSound()
    }

    private fun savePosition(b: BallView) {
        val lp = b.layoutParams as WindowManager.LayoutParams
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        val cx = ((lp.x + lp.width / 2f) / sw).coerceIn(0f, 1f)
        val cy = ((lp.y + lp.height / 2f) / sh).coerceIn(0f, 1f)
        prefs().edit().putFloat(K_X, cx).putFloat(K_Y, cy).apply()
    }

    private fun playSound() {
        val p = player ?: return
        val rate = pitch.next(SystemClock.uptimeMillis())
        p.play("sound.mp3", rate, 1f)
    }

    private fun applySettings() {
        val v = ball ?: return
        v.opacity = prefs().getFloat(K_OPACITY, 0.55f)
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
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
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

        return builder
            .setContentTitle(getString(R.string.noti_title))
            .setContentText(getString(R.string.noti_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.noti_stop),
                    stop
                ).build()
            )
            .build()
    }

    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTI_ID, n)
        }
    }
}
