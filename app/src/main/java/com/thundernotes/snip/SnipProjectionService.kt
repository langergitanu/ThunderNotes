package com.thundernotes.snip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.thundernotes.R

/**
 * A tiny foreground service whose only job is to hold the **mediaProjection**
 * foreground-service type while [SnipCaptureActivity] reads a frame off the
 * screen.
 *
 * Why it exists (Android 14+ rule): apps targeting API 34+ must have a
 * foreground service running with `foregroundServiceType="mediaProjection"`
 * **before** they call `MediaProjection.createVirtualDisplay()`, or the call
 * throws a SecurityException. The same rule says that FGS type may only be
 * *started* **after** the user has approved the system screen-capture consent
 * dialog — which is exactly when [SnipCaptureActivity] starts this service
 * (from its `onActivityResult`, straight after the user taps "Start now").
 *
 * The capture sequence (all on the app's single main thread, in order):
 *
 *  1. [SnipCaptureActivity.onActivityResult] — consent granted.
 *  2. It calls [start] here → [startForegroundService] → this service's
 *     [onStartCommand] runs on the main thread → calls `startForeground`
 *     with the manifest's `mediaProjection` type (legal now).
 *  3. The activity then posts its `captureScreen()` to the main-thread
 *     handler — because the service's `onStartCommand` was already queued
 *     ahead of it on the same looper, `captureScreen()` is guaranteed to run
 *     only after the service is in the foreground → `createVirtualDisplay`
 *     sees a live mediaProjection FGS and works.
 *  4. When the frame is captured (or anything fails), the activity calls
 *     [stop] — the service is short-lived by design.
 *
 * No capture logic lives here — just the FGS-type bookkeeping, so the overlay
 * button service ([SnipOverlayService]) can use the unrelated `specialUse`
 * type (it never captures anything itself).
 */
class SnipProjectionService : Service() {

    companion object {
        const val CHANNEL_ID = "thunder_snip_projection"
        const val NOTIFICATION_ID = 43

        /** Start this service (idempotent). Safe only after capture consent. */
        fun start(context: Context) {
            val intent = Intent(context, SnipProjectionService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // Swallow: worst case the platform is < 14 and the FGS isn't
                // required at all; the capture proceeds without it.
            }
        }

        /** Stop this service (called when the capture ends or fails). */
        fun stop(context: Context) {
            context.stopService(Intent(context, SnipProjectionService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 2-arg startForeground: the type comes from the manifest declaration
        // (mediaProjection) — legal here because consent was JUST granted by
        // the user, which is the only moment this service ever runs.
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Snip Capture",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Short-lived channel shown while a screen snip is captured."
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ThunderNotes Snip")
            .setContentText("Capturing the selected screen area…")
            .setSmallIcon(R.drawable.ic_editor_ai_snip)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY  // never restart on its own — only live for one capture

    override fun onBind(intent: Intent?): IBinder? = null  // not bound
}
