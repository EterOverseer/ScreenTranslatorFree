package com.example.capture

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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.example.core.image.BitmapUtils
import com.example.domain.repository.DiagnosticsRepository
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Lightweight frame abstraction enabling deterministic unit testing of the frame
 * acquisition loop, Image closing, and Bitmap recycling lifecycle.
 */
internal interface CapturedImageHandle : AutoCloseable {
    val width: Int
    val height: Int
    val pixelStride: Int
    val rowStride: Int
    val buffer: ByteBuffer?
}

private class AndroidImageHandle(private val image: Image) : CapturedImageHandle {
    private val firstPlane: Image.Plane? = image.planes.firstOrNull()
    override val width: Int get() = image.width
    override val height: Int get() = image.height
    override val pixelStride: Int get() = firstPlane?.pixelStride ?: 0
    override val rowStride: Int get() = firstPlane?.rowStride ?: 0
    override val buffer: ByteBuffer? get() = firstPlane?.buffer
    override fun close() {
        image.close()
    }
}

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

        var acquiredBitmap: Bitmap? = null
        try {
            val reader = imageReader ?: return@withContext Result.failure(
                IllegalStateException("ImageReader ไม่พร้อมใช้งาน")
            )

            acquiredBitmap = acquireValidBitmapWithRetry(
                maxAttempts = MAX_FRAME_ATTEMPTS,
                retryDelayMs = FRAME_RETRY_DELAY_MS,
                acquireNextImage = {
                    if (!_isSessionActive.value) null
                    else reader.acquireLatestImage()?.let { AndroidImageHandle(it) }
                },
                isSessionActive = { _isSessionActive.value }
            )

            val resultBitmap = acquiredBitmap
            if (resultBitmap == null) {
                Result.failure(
                    IllegalStateException("ไม่สามารถอ่านภาพหน้าจอได้ (แอปปลายทางอาจเปิดการป้องกัน FLAG_SECURE)")
                )
            } else if (!_isSessionActive.value) {
                BitmapUtils.safeRecycle(resultBitmap)
                acquiredBitmap = null
                Result.failure(IllegalStateException("เซสชันการจับภาพหน้าจอถูกปิดแล้ว"))
            } else if (isLikelyProtectedBlackFrame(resultBitmap)) {
                BitmapUtils.safeRecycle(resultBitmap)
                acquiredBitmap = null
                Result.failure(
                    IllegalStateException("หน้าจอนี้ถูกป้องกันการจับภาพ (FLAG_SECURE)")
                )
            } else {
                // Transfer ownership to caller; prevent finally from recycling returned bitmap
                acquiredBitmap = null
                Result.success(resultBitmap)
            }
        } catch (e: CancellationException) {
            BitmapUtils.safeRecycle(acquiredBitmap)
            throw e
        } catch (e: Throwable) {
            BitmapUtils.safeRecycle(acquiredBitmap)
            diagnosticsRepository.recordError("ScreenCaptureManager", "Frame capture failed: ${e.javaClass.simpleName}")
            Result.failure(if (e is Exception) e else RuntimeException(e))
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
        val projection = mediaProjection
        mediaProjection = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
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
        internal const val MAX_FRAME_ATTEMPTS = 6
        internal const val FRAME_RETRY_DELAY_MS = 75L

        /**
         * Acquires a single valid Bitmap frame, stopping immediately on the first valid frame,
         * recycling any replaced/temporary Bitmap, and closing every Image handle.
         */
        internal suspend fun acquireValidBitmapWithRetry(
            maxAttempts: Int = MAX_FRAME_ATTEMPTS,
            retryDelayMs: Long = FRAME_RETRY_DELAY_MS,
            acquireNextImage: () -> CapturedImageHandle?,
            isSessionActive: () -> Boolean = { true },
            bitmapFactory: (Int, Int) -> Bitmap = { w, h ->
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            },
            bitmapCropper: (Bitmap, Int, Int) -> Bitmap = { src, w, h ->
                Bitmap.createBitmap(src, 0, 0, w, h)
            }
        ): Bitmap? {
            var currentBitmap: Bitmap? = null
            var attempt = 0
            try {
                while (attempt < maxAttempts && currentBitmap == null && isSessionActive()) {
                    val handle = acquireNextImage()
                    if (handle != null) {
                        var candidate: Bitmap? = null
                        try {
                            candidate = convertImageToBitmap(handle, bitmapFactory, bitmapCropper)
                        } finally {
                            runCatching { handle.close() }
                        }
                        if (candidate != null) {
                            BitmapUtils.safeRecycle(currentBitmap)
                            currentBitmap = candidate
                            break
                        }
                    }
                    attempt++
                    if (attempt < maxAttempts && isSessionActive()) {
                        delay(retryDelayMs)
                    }
                }
                val result = currentBitmap
                currentBitmap = null
                return result
            } catch (t: Throwable) {
                BitmapUtils.safeRecycle(currentBitmap)
                throw t
            }
        }

        internal fun convertImageToBitmap(
            handle: CapturedImageHandle,
            bitmapFactory: (Int, Int) -> Bitmap,
            bitmapCropper: (Bitmap, Int, Int) -> Bitmap
        ): Bitmap? {
            val buffer = handle.buffer ?: return null
            val width = handle.width
            val height = handle.height
            val pixelStride = handle.pixelStride
            if (width <= 0 || height <= 0 || pixelStride <= 0) return null

            val rowPadding = (handle.rowStride - pixelStride * width).coerceAtLeast(0)
            val paddedWidth = width + rowPadding / pixelStride
            var rawBitmap: Bitmap? = null
            var croppedBitmap: Bitmap? = null
            return try {
                buffer.rewind()
                rawBitmap = bitmapFactory(paddedWidth.coerceAtLeast(width), height)
                rawBitmap.copyPixelsFromBuffer(buffer)
                if (rawBitmap.width != width) {
                    croppedBitmap = bitmapCropper(rawBitmap, width, height)
                    BitmapUtils.safeRecycle(rawBitmap)
                    rawBitmap = null
                    val finalBmp = croppedBitmap
                    croppedBitmap = null
                    finalBmp
                } else {
                    val finalBmp = rawBitmap
                    rawBitmap = null
                    finalBmp
                }
            } catch (t: Throwable) {
                BitmapUtils.safeRecycle(croppedBitmap)
                BitmapUtils.safeRecycle(rawBitmap)
                throw t
            }
        }
    }
}
