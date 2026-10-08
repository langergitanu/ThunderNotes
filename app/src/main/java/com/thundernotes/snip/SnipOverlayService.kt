package com.thundernotes.snip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.thundernotes.R

/**
 * The floating AI-snip overlay (spec §7.3.1: "a button for AI snipping tasks
 * — a floating button that can be dragged anywhere. It remains active as long
 * as ThunderNotes is open and can stay on top of other apps if ThunderNotes is
 * minimized but running in the background... overlay-on-other-apps permission
 * is granted").
 *
 * A foreground Service that hosts a `WindowManager` TYPE_APPLICATION_OVERLAY
 * draggable button. On tap, it launches [SnipCaptureActivity] (the
 * MediaProjection capture host). The button captures area from both inside
 * ThunderNotes and from other apps, but the **final output is only saved to
 * ThunderNotes memory** (the [com.thundernotes.canvas.inject.ThunderClipboard]),
 * per spec §7.3.3.
 *
 * **Lifecycle:** started by [com.thundernotes.ui.canvas.CanvasActivity] when
 * the canvas opens (if the overlay permission is granted) + stopped when the
 * canvas closes. If the permission isn't granted, the service shows a Toast
 * prompting the user to grant it + the in-canvas `btnAiSnip` remains the only
 * capture path (in-app only).
 *
 * The button is draggable via `onTouchEvent` — ACTION_DOWN captures the start,
 * ACTION_MOVE updates the WindowManager.LayoutParams (x, y), ACTION_UP + a
 * small move threshold triggers the tap → launch SnipCaptureActivity.
 */
class SnipOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "thunder_snip_overlay"
        const val NOTIFICATION_ID = 42
        const val TAP_THRESHOLD = 12f  // px — a drag < this counts as a tap

        /** True iff the overlay permission (SYSTEM_ALERT_WINDOW) is granted. */
        fun canDrawOverlays(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.canDrawOverlays(ctx) else true

        /** Start the floating overlay (idempotent — no-op if already running). */
        fun start(ctx: Context) {
            if (!canDrawOverlays(ctx)) {
                Toast.makeText(ctx, "Grant the 'display over other apps' " +
                    "permission to enable the floating snip button",
                    Toast.LENGTH_LONG).show()
                return
            }
            val intent = Intent(ctx, SnipOverlayService::class.java)
            ctx.startForegroundService(intent)
        }

        /** Stop the floating overlay. */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SnipOverlayService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
        // FGS type rules (Android 14+ / targetSdk 34+): a foreground service must
        // pick a concrete type. This one is an always-available overlay button —
        // NOT a capture session — so it uses `specialUse` (the manifest declares
        // specialUse + the subtype property). The old code called the 2-arg
        // startForeground while the manifest said `mediaProjection`, which
        // Android 14+ rejects (that type is only legal AFTER capture consent) →
        // ForegroundServiceTypeNotAllowedException → crash at app start.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 3-arg overload: pin the runtime type to specialUse explicitly.
            startForeground(
                NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            // Pre-34: foreground-service types aren't enforced; the 2-arg call
            // is the classic form (the manifest type is simply unused).
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        showFloatingButton()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Snip Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "The floating AI-snip button (drag to move, tap to snip)."
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ThunderNotes Snip")
            .setContentText("Floating snip button active — tap to snip, drag to move")
            .setSmallIcon(R.drawable.ic_editor_ai_snip)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun showFloatingButton() {
        val wm = windowManager ?: return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Resources.getSystem().displayMetrics.widthPixels - dp(72)
            y = dp(120)
        }
        val btn = ImageView(this).apply {
            setImageResource(R.drawable.ic_editor_ai_snip)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xE6FF5252.toInt())  // semi-transparent red
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            // Drag + tap handling: DOWN records the start, MOVE updates the
            // WindowManager position by the raw-delta, UP + no-move → tap.
            var startParamX = 0; var startParamY = 0
            var startRawX = 0f; var startRawY = 0f
            var moved = false
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startParamX = params.x; startParamY = params.y
                        startRawX = ev.rawX; startRawY = ev.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - startRawX
                        val dy = ev.rawY - startRawY
                        if (Math.abs(dx) > TAP_THRESHOLD ||
                            Math.abs(dy) > TAP_THRESHOLD) moved = true
                        params.x = startParamX + dx.toInt()
                        params.y = startParamY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            // Tap → launch the MediaProjection capture activity.
                            val intent = Intent(this@SnipOverlayService,
                                SnipCaptureActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        }
                    }
                }
                true
            }
        }
        overlayView = btn
        runCatching { wm.addView(btn, params) }.onFailure {
            // The overlay permission may have been revoked; fall back silently.
            Toast.makeText(this, "Overlay unavailable — grant the permission",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { v -> runCatching { windowManager?.removeView(v) } }
        overlayView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null  // not bound
}
