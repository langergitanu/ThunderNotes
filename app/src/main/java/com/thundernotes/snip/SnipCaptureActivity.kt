package com.thundernotes.snip

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The MediaProjection capture host (spec §7.3.1: "It captures area from both
 * inside ThunderNotes and from other apps... the FINAL RESULT (OUTPUT) IS
 * ONLY SAVED TO THUNDERNOTES MEMORY").
 *
 * Launched from [SnipOverlayService]'s floating button tap. The full flow:
 *
 *  1. `onCreate` → requests MediaProjection permission (the system dialog).
 *  2. `onActivityResult` (user consented) → starts [SnipProjectionService]
 *     — Android 14+ requires a foreground service holding the
 *     `mediaProjection` type BEFORE `createVirtualDisplay()` is legal, and
 *     that type may only be started after consent, which is this exact moment.
 *  3. A capture task is posted to the main-thread handler — it runs only
 *     after the service's `onStartCommand` (queued first on the same
 *     looper), so the FGS is live by then.
 *  4. One frame is read via [ImageReader] into a [Bitmap], the projection is
 *     torn down, and the bitmap goes to [SnipRegionDialogFragment] (the
 *     "Area captured → Confirmation" step of spec §7.3.4).
 *  5. The region dialog → [SnipBottomSheet] → pipeline →
 *     [com.thundernotes.canvas.inject.ThunderClipboard] (the glowing paste
 *     path). The bitmap never touches the system clipboard (§7.3.3).
 *
 * **Activity lifetime:** this activity stays alive (transparent, no UI)
 * while the region dialog + snip sheet are up, and finishes itself when the
 * sheet is dismissed via [SnipBottomSheet.onDismissed] — the OLD code called
 * `finish()` immediately after showing the sheet, which raced the fragment
 * transaction and made the sheet never appear.
 */
class SnipCaptureActivity : AppCompatActivity() {

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    companion object {
        private const val REQUEST_CAPTURE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immediately request the MediaProjection permission (system dialog).
        startActivityForResult(projectionManager.createScreenCaptureIntent(),
            REQUEST_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) { finish(); return }
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "Capture cancelled", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        try {
            // Android 14+ (targetSdk 34+): a mediaProjection-type foreground
            // service must be running BEFORE createVirtualDisplay(). Consent
            // was just granted, so starting it is now legal. The service's
            // onStartCommand is queued ahead of the posted capture task on
            // the main looper → the FGS is live by the time we capture.
            SnipProjectionService.start(this)
            Handler(Looper.getMainLooper()).post { captureScreen(resultCode, data) }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            SnipProjectionService.stop(this)
            finish()
        }
    }

    private fun captureScreen(resultCode: Int, data: Intent) {
        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            SnipProjectionService.stop(this)
            finish(); return
        }
        val mp = mediaProjection ?: run { SnipProjectionService.stop(this); finish(); return }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels; val height = metrics.heightPixels
        val density = metrics.densityDpi
        // ImageReader with a one-buffer queue; we read the single frame on
        // OnImageAvailable + immediately stop the projection.
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image: Image? = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                // Create a bitmap with the row padding baked in.
                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888,
                )
                buffer.rewind()
                bmp.copyPixelsFromBuffer(buffer)
                // Crop to the actual width (drop the row-padding column).
                val cropped = if (rowPadding > 0)
                    Bitmap.createBitmap(bmp, 0, 0, width, height) else bmp
                image.close()
                // Hand the bitmap to the region-selection confirmation step.
                // This activity stays alive until the snip sheet dismisses.
                SnipRegionDialogFragment.bitmap = cropped
                SnipBottomSheet.bitmap = cropped
                runOnUiThread {
                    SnipBottomSheet.onDismissed = { finish() }
                    SnipRegionDialogFragment().show(supportFragmentManager, "snip_region")
                }
                stopProjectionOnly()
            }
        }, Handler(Looper.getMainLooper()))
        virtualDisplay = mp.createVirtualDisplay(
            "ThunderSnipCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null,
        )
        // Safety: if no frame arrives (projection died), give up after 3s.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && virtualDisplay != null) {
                stopProjectionOnly()
                SnipProjectionService.stop(this)
                finish()
            }
        }, 3000L)
    }

    /**
     * Release the projection + display but KEEP the activity alive — the
     * region dialog + snip sheet still need it as a host. The projection-type
     * FGS is stopped too (its purpose ends with the capture).
     */
    private fun stopProjectionOnly() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        SnipProjectionService.stop(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}
