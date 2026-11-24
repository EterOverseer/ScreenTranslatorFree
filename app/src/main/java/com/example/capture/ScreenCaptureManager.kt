package com.example.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.example.domain.repository.DiagnosticsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class ScreenCaptureManager(
    private val context: Context,
    private val diagnosticsRepository: DiagnosticsRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureWidth: Int = 1080
    private var captureHeight: Int = 2400
    private var densityDpi: Int = 420

    private val isCapturingFrame = AtomicBoolean(false)

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    var onExternalStopListener: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system or user chip")
            release()
            onExternalStopListener?.invoke()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0 && (width != captureWidth || height != captureHeight)) {
                Log.d(TAG, "Captured content resized: ${width}x$height")
                rebuildVirtualDisplay(width, height)
            }
        }
    }

    fun startProjection(resultCode: Int, data: Intent): Boolean {
        return try {
            release()
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data) ?: return false

            updateDisplayMetrics()
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection
            createImageReaderAndVirtualDisplay(captureWidth, captureHeight)
            _isSessionActive.value = true
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "MediaProjection SecurityException", e)
            diagnosticsRepository.recordError("ScreenCaptureManager", "SecurityException: MediaProjection permission rejected")
            release()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaProjection", e)
            diagnosticsRepository.recordError("ScreenCaptureManager", "${e.javaClass.simpleName}: startProjection failed")
            release()
            false
        }
    }

    fun handleConfigurationChange() {
        if (!_isSessionActive.value || mediaProjection == null) return
        val oldW = captureWidth
        val oldH = captureHeight
        updateDisplayMetrics()
        if (captureWidth != oldW || captureHeight != oldH) {
            rebuildVirtualDisplay(captureWidth, captureHeight)
        }
    }

    suspend fun captureSingleFrame(): Result<Bitmap> = withContext(Dispatchers.Default) {
        if (!_isSessionActive.value || imageReader == null) {
            return@withContext Result.failure(IllegalStateException("ยังไม่ได้เปิดสิทธิ์จับภาพหน้าจอ"))
        }
        if (!isCapturingFrame.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("กำลังจับภาพหน้าจอรอบก่อนหน้าอยู่"))
        }

        try {
            val reader = imageReader ?: return@withContext Result.failure(
                IllegalStateException("ImageReader ไม่พร้อมใช้งาน")
            )

            // Wait briefly for a fresh frame from VirtualDisplay
            var acquiredBitmap: Bitmap? = null
            repeat(6) { attempt ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val planes = image.planes
                        if (planes.isNotEmpty()) {
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * image.width
                            val paddedWidth = image.width + rowPadding / pixelStride.coerceAtLeast(1)

                            val rawBitmap = Bitmap.createBitmap(
                                paddedWidth.coerceAtLeast(image.width),
                                image.height,
                                Bitmap.Config.ARGB_8888
                            )
                            rawBitmap.copyPixelsFromBuffer(buffer)

                            acquiredBitmap = if (rawBitmap.width != image.width) {
                                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
                                rawBitmap.recycle()
                                cropped
                            } else {
                                rawBitmap
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                if (acquiredBitmap != null) return@repeat
                if (attempt < 5) delay(75L)
            }

            val resultBitmap = acquiredBitmap
            if (resultBitmap == null) {
                Result.failure(
                    IllegalStateException("ไม่สามารถอ่านภาพหน้าจอได้ (แอปปลายทางอาจเปิดการป้องกัน FLAG_SECURE)")
                )
            } else if (isLikelyProtectedBlackFrame(resultBitmap)) {
                resultBitmap.recycle()
                Result.failure(
                    IllegalStateException("หน้าจอนี้ถูกป้องกันการจับภาพ (FLAG_SECURE)")
                )
            } else {
                Result.success(resultBitmap)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticsRepository.recordError("ScreenCaptureManager", "Frame capture failed: ${e.javaClass.simpleName}")
            Result.failure(e)
        } finally {
            isCapturingFrame.set(false)
        }
    }

    fun release() {
        _isSessionActive.value = false
        isCapturingFrame.set(false)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    private fun updateDisplayMetrics() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val resourcesMetrics = context.resources.displayMetrics
        densityDpi = resourcesMetrics.densityDpi

        val rawW: Int
        val rawH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            rawW = bounds.width()
            rawH = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            rawW = size.x
            rawH = size.y
        }

        // Cap capture resolution to avoid allocating oversized full-resolution buffers (e.g. 2K/1440p)
        val maxEdge = 1600f
        val longEdge = maxOf(rawW, rawH).toFloat().coerceAtLeast(1f)
        val scale = if (longEdge > maxEdge) maxEdge / longEdge else 1f
        captureWidth = (rawW * scale).toInt().coerceAtLeast(320)
        captureHeight = (rawH * scale).toInt().coerceAtLeast(320)
    }

    private fun createImageReaderAndVirtualDisplay(width: Int, height: Int) {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "EterScreenVirtualDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
    }

    private fun rebuildVirtualDisplay(width: Int, height: Int) {
        captureWidth = width
        captureHeight = height
        runCatching {
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val oldReader = imageReader
            imageReader = newReader
            virtualDisplay?.resize(width, height, densityDpi)
            virtualDisplay?.surface = newReader.surface
            oldReader?.close()
        }.onFailure {
            runCatching { virtualDisplay?.release() }
            runCatching { imageReader?.close() }
            createImageReaderAndVirtualDisplay(width, height)
        }
    }

    private fun isLikelyProtectedBlackFrame(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 10 || h < 10) return true
        val samplePoints = listOf(
            w / 4 to h / 4,
            w / 2 to h / 2,
            (w * 3) / 4 to (h * 3) / 4,
            w / 3 to (h * 2) / 3
        )
        return samplePoints.all { (x, y) ->
            val p = bitmap.getPixel(x, y)
            android.graphics.Color.red(p) == 0 &&
                android.graphics.Color.green(p) == 0 &&
                android.graphics.Color.blue(p) == 0
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}
