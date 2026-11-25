package com.example.core.overlay

import com.example.domain.model.OcrBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCoordinateMapperTest {

    @Test
    fun `same size capture and overlay maps 1 to 1`() {
        val mapper = OverlayCoordinateMapper(
            sourceWidth = 1080,
            sourceHeight = 2400,
            overlayWidth = 1080,
            overlayHeight = 2400
        )

        assertTrue(mapper.isValid())
        val mapped = mapper.mapBox(OcrBoundingBox(100, 200, 350, 420))
        assertNotNull(mapped)
        assertEquals(100f, mapped!!.left, 0.01f)
        assertEquals(200f, mapped.top, 0.01f)
        assertEquals(350f, mapped.right, 0.01f)
        assertEquals(420f, mapped.bottom, 0.01f)
    }

    @Test
    fun `downscaled OCR coordinates scale cleanly back to physical overlay coordinates`() {
        val mapper = OverlayCoordinateMapper(
            sourceWidth = 720,
            sourceHeight = 1600,
            overlayWidth = 1080,
            overlayHeight = 2400
        )

        assertEquals(1.5f, mapper.scaleX, 0.001f)
        assertEquals(1.5f, mapper.scaleY, 0.001f)

        val mapped = mapper.mapBox(OcrBoundingBox(100, 200, 300, 400))
        assertNotNull(mapped)
        assertEquals(150f, mapped!!.left, 0.01f)
        assertEquals(300f, mapped.top, 0.01f)
        assertEquals(450f, mapped.right, 0.01f)
        assertEquals(600f, mapped.bottom, 0.01f)
    }

    @Test
    fun `portrait and landscape orientations map when matching and invalidate on mismatch`() {
        val portraitMapper = OverlayCoordinateMapper(
            sourceWidth = 720,
            sourceHeight = 1280,
            overlayWidth = 1080,
            overlayHeight = 1920
        )
        assertTrue(portraitMapper.isValid())
        assertNotNull(portraitMapper.mapBox(OcrBoundingBox(50, 50, 200, 150)))

        val landscapeMapper = OverlayCoordinateMapper(
            sourceWidth = 1280,
            sourceHeight = 720,
            overlayWidth = 2400,
            overlayHeight = 1080
        )
        assertTrue(landscapeMapper.isValid())
        assertNotNull(landscapeMapper.mapBox(OcrBoundingBox(100, 80, 400, 260)))

        val rotatedMismatch = OverlayCoordinateMapper(
            sourceWidth = 720,
            sourceHeight = 1600,
            overlayWidth = 2400,
            overlayHeight = 1080
        )
        assertFalse(rotatedMismatch.isValid())
        assertNull(rotatedMismatch.mapBox(OcrBoundingBox(50, 50, 200, 150)))
    }

    @Test
    fun `display resize updates target scaling accurately`() {
        val beforeResize = OverlayCoordinateMapper(
            sourceWidth = 720,
            sourceHeight = 1600,
            overlayWidth = 1080,
            overlayHeight = 2400
        )
        val afterResize = OverlayCoordinateMapper(
            sourceWidth = 720,
            sourceHeight = 1600,
            overlayWidth = 540,
            overlayHeight = 1200
        )

        val box = OcrBoundingBox(100, 200, 300, 400)
        val rectBefore = beforeResize.mapBox(box)!!
        val rectAfter = afterResize.mapBox(box)!!

        assertEquals(rectBefore.left * 0.5f, rectAfter.left, 0.01f)
        assertEquals(rectBefore.top * 0.5f, rectAfter.top, 0.01f)
        assertEquals(rectBefore.width * 0.5f, rectAfter.width, 0.01f)
    }

    @Test
    fun `clips mapped rectangles against all four screen edges`() {
        val mapper = OverlayCoordinateMapper(
            sourceWidth = 1000,
            sourceHeight = 2000,
            overlayWidth = 1000,
            overlayHeight = 2000,
            insets = OverlayInsets(left = 20, top = 80, right = 20, bottom = 60)
        )

        // Left & Top edge overflow
        val topLeft = mapper.mapBox(OcrBoundingBox(-50, 10, 220, 180))!!
        assertEquals(20f, topLeft.left, 0.01f)
        assertEquals(80f, topLeft.top, 0.01f)
        assertEquals(220f, topLeft.right, 0.01f)
        assertEquals(180f, topLeft.bottom, 0.01f)

        // Right & Bottom edge overflow
        val bottomRight = mapper.mapBox(OcrBoundingBox(800, 1800, 1100, 2100))!!
        assertEquals(800f, bottomRight.left, 0.01f)
        assertEquals(1800f, bottomRight.top, 0.01f)
        assertEquals(980f, bottomRight.right, 0.01f)
        assertEquals(1940f, bottomRight.bottom, 0.01f)

        // Fully outside safe area -> null
        assertNull(mapper.mapBox(OcrBoundingBox(985, 1950, 1050, 2020)))
    }

    @Test
    fun `applies windowOffsetX and windowOffsetY relative to full display coordinates`() {
        val mapper = OverlayCoordinateMapper(
            sourceWidth = 720,
            sourceHeight = 1600,
            overlayWidth = 1080,
            overlayHeight = 2280,
            displayWidth = 1080,
            displayHeight = 2400,
            windowOffsetX = 0f,
            windowOffsetY = 96f,
            insets = OverlayInsets(left = 0, top = 0, right = 0, bottom = 0)
        )

        val mapped = mapper.mapBox(OcrBoundingBox(100, 200, 300, 400))!!
        // Y=200 * 1.5 - 96 = 204
        assertEquals(150f, mapped.left, 0.01f)
        assertEquals(204f, mapped.top, 0.01f)
        assertEquals(450f, mapped.right, 0.01f)
        assertEquals(504f, mapped.bottom, 0.01f)
    }
}
