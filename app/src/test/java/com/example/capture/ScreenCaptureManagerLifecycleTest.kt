package com.example.capture

import android.graphics.Bitmap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenCaptureManagerLifecycleTest {

    private class FakeImageHandle(
        override val width: Int,
        override val height: Int,
        override val pixelStride: Int = 4,
        override val rowStride: Int = width * 4,
        override val buffer: ByteBuffer? = ByteBuffer.allocate(rowStride * height)
    ) : CapturedImageHandle {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `acquireValidBitmapWithRetry stops immediately after first valid frame and closes every handle`() = runTest {
        val handles = mutableListOf<FakeImageHandle>()
        var acquireCalls = 0

        val bitmap = ScreenCaptureManager.acquireValidBitmapWithRetry(
            maxAttempts = 6,
            retryDelayMs = 1L,
            acquireNextImage = {
                acquireCalls++
                when (acquireCalls) {
                    1 -> null
                    2 -> FakeImageHandle(width = 16, height = 16, buffer = null).also { handles += it }
                    3 -> FakeImageHandle(width = 16, height = 16).also { handles += it }
                    else -> {
                        fail("Frame loop must stop immediately after obtaining the first valid frame")
                        null
                    }
                }
            }
        )

        assertNotNull(bitmap)
        assertEquals(3, acquireCalls)
        assertEquals(2, handles.size)
        assertTrue(handles.all { it.closed })
        assertFalse(bitmap!!.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun `convertImageToBitmap recycles raw padded bitmap after cropping`() {
        val createdBitmaps = mutableListOf<Bitmap>()
        val handle = FakeImageHandle(
            width = 16,
            height = 16,
            pixelStride = 4,
            rowStride = 20 * 4
        )

        val result = ScreenCaptureManager.convertImageToBitmap(
            handle = handle,
            bitmapFactory = { w, h ->
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { createdBitmaps += it }
            },
            bitmapCropper = { src, w, h ->
                Bitmap.createBitmap(src, 0, 0, w, h).also { createdBitmaps += it }
            }
        )

        assertNotNull(result)
        assertEquals(2, createdBitmaps.size)
        val rawBitmap = createdBitmaps[0]
        val croppedBitmap = createdBitmaps[1]
        assertTrue("Intermediate padded raw bitmap must be recycled", rawBitmap.isRecycled)
        assertFalse("Returned cropped bitmap must remain alive", croppedBitmap.isRecycled)
        assertEquals(16, croppedBitmap.width)
        croppedBitmap.recycle()
    }

    @Test
    fun `acquireValidBitmapWithRetry closes handle and recycles temporary bitmap when cropper throws`() = runTest {
        val createdBitmaps = mutableListOf<Bitmap>()
        val handle = FakeImageHandle(
            width = 16,
            height = 16,
            pixelStride = 4,
            rowStride = 20 * 4
        )

        try {
            ScreenCaptureManager.acquireValidBitmapWithRetry(
                maxAttempts = 3,
                retryDelayMs = 1L,
                acquireNextImage = { handle },
                bitmapFactory = { w, h ->
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { createdBitmaps += it }
                },
                bitmapCropper = { _, _, _ ->
                    throw IllegalStateException("Simulated OOM during crop")
                }
            )
            fail("Expected exception from cropper")
        } catch (_: IllegalStateException) {
            // Expected
        }

        assertTrue("Handle must always be closed even on exception", handle.closed)
        assertEquals(1, createdBitmaps.size)
        assertTrue("Temporary raw bitmap must be recycled on exception", createdBitmaps.first().isRecycled)
    }

    @Test
    fun `acquireValidBitmapWithRetry aborts cleanly when session becomes inactive`() = runTest {
        var active = false
        var calls = 0
        val result = ScreenCaptureManager.acquireValidBitmapWithRetry(
            maxAttempts = 6,
            retryDelayMs = 1L,
            acquireNextImage = {
                calls++
                FakeImageHandle(16, 16)
            },
            isSessionActive = { active }
        )

        assertNull(result)
        assertEquals(0, calls)
    }
}
