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
import com.thundernotes.R

/**
 * The MediaProjection capture host (spec §7.3.1: "It captures area from both
 * inside ThunderNotes and from other apps... the FINAL RESULT (OUTPUT) IS
 * ONLY SAVED TO THUNDERNOTES MEMORY").
 *
 * Launched from [SnipOverlayService]'s floating button tap. Requests the
 * MediaProjection permission (the system capture dialog) → on grant, captures
 * the current screen to a [Bitmap] → passes it to [SnipBottomSheet] via the
 * static bitmap holder → shows the snip-type selector → the pipeline runs
 * (preprocess → OCR engine → ClipboardItem → ThunderClipboard, the glowing
 * paste path). The captured bitmap never touches the system clipboard (only
 * ThunderNotes memory, §7.3.3).
 *
 * Transparent (no UI) — the system's MediaProjection consent dialog is the
 * only thing the user sees.
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
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            captureScreen()
        } catch (e: SecurityException) {
            // Foreground-service type check can fail if the service isn't
            // in the foreground — fall back to a Toast.
            Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun captureScreen() {
        val mp = mediaProjection ?: run { finish(); return }
        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels; val height = metrics.heightPixels
        val density = metrics.densityDpi
        // ImageReader with one-buffer queue; we read the single frame on
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
                // Hand the bitmap to SnipBottomSheet + show the type selector.
                SnipBottomSheet.bitmap = cropped
                runOnUiThread {
                    SnipBottomSheet().show(supportFragmentManager, "snip_overlay")
                }
                stopCapture()
            }
        }, Handler(Looper.getMainLooper()))
        virtualDisplay = mp.createVirtualDisplay(
            "ThunderSnipCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null,
        )
    }

    private fun stopCapture() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}
