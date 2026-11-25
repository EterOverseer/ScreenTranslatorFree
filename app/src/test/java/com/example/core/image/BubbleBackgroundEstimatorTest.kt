package com.example.core.image

import android.graphics.Color
import com.example.domain.model.OcrBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubbleBackgroundEstimatorTest {

    private class ArrayPixelGrid(
        override val width: Int,
        override val height: Int,
        fillColor: Int = Color.WHITE
    ) : PixelGrid {
        private val pixels = IntArray(width * height) { fillColor }

        override fun getPixel(x: Int, y: Int): Int {
            return pixels[y * width + x]
        }

        fun fillRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top.coerceIn(0, height) until bottom.coerceIn(0, height)) {
                for (x in left.coerceIn(0, width) until right.coerceIn(0, width)) {
                    pixels[y * width + x] = color
                }
            }
        }

        fun setPixel(x: Int, y: Int, color: Int) {
            if (x in 0 until width && y in 0 until height) {
                pixels[y * width + x] = color
            }
        }
    }

    private fun colorDistance(a: Int, b: Int): Int {
        return abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))
    }

    @Test
    fun `white speech bubble produces white patch with dark text and high confidence`() {
        val grid = ArrayPixelGrid(200, 200, fillColor = Color.WHITE)
        val estimate = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(40, 40, 140, 110))

        assertTrue(colorDistance(estimate.colorArgb, Color.WHITE) <= 10)
        assertTrue(estimate.isLightBackground)
        assertTrue(estimate.confidence >= 0.7f)
        assertEquals(0xFF111827.toInt(), estimate.textColorArgb)
    }

    @Test
    fun `cream speech bubble estimates warm cream tone`() {
        val cream = Color.rgb(250, 240, 220)
        val grid = ArrayPixelGrid(200, 200, fillColor = cream)
        val estimate = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(40, 40, 140, 110))

        assertTrue(colorDistance(estimate.colorArgb, cream) <= 12)
        assertTrue(estimate.isLightBackground)
        assertTrue(estimate.confidence >= 0.7f)
    }

    @Test
    fun `dark speech bubble produces dark patch with light contrasting text`() {
        val darkBubble = Color.rgb(26, 30, 40)
        val grid = ArrayPixelGrid(200, 200, fillColor = darkBubble)
        val estimate = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(40, 40, 140, 110))

        assertTrue(colorDistance(estimate.colorArgb, darkBubble) <= 12)
        assertFalse(estimate.isLightBackground)
        assertEquals(0xFFF8FAFC.toInt(), estimate.textColorArgb)
        assertTrue(estimate.confidence >= 0.7f)
    }

    @Test
    fun `gray speech bubble estimates neutral gray accurately`() {
        val gray = Color.rgb(156, 160, 166)
        val grid = ArrayPixelGrid(200, 200, fillColor = gray)
        val estimate = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(40, 40, 140, 110))

        assertTrue(colorDistance(estimate.colorArgb, gray) <= 15)
        assertTrue(estimate.confidence >= 0.7f)
    }

    @Test
    fun `colored pastel bubble preserves hue accurately`() {
        val pastelPink = Color.rgb(255, 214, 224)
        val grid = ArrayPixelGrid(200, 200, fillColor = pastelPink)
        val estimate = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(40, 40, 140, 110))

        assertTrue(colorDistance(estimate.colorArgb, pastelPink) <= 15)
        assertTrue(estimate.isLightBackground)
        assertTrue(estimate.confidence >= 0.7f)
    }

    @Test
    fun `black text and dark outline contamination does not darken white bubble`() {
        val grid = ArrayPixelGrid(220, 220, fillColor = Color.WHITE)
        val box = OcrBoundingBox(50, 50, 150, 130)

        // Simulate black ink glyphs inside the box and a partial dark ring outline touching the top edge
        grid.fillRect(65, 65, 135, 115, Color.BLACK)
        grid.fillRect(48, 46, 152, 49, Color.BLACK)

        val estimate = BubbleBackgroundEstimator.estimate(grid, box)

        assertTrue("Expected white bubble preservation, got ${Color.red(estimate.colorArgb)}", Color.red(estimate.colorArgb) >= 235)
        assertTrue(Color.green(estimate.colorArgb) >= 235)
        assertTrue(Color.blue(estimate.colorArgb) >= 235)
        assertTrue(estimate.isLightBackground)
    }

    @Test
    fun `chaotic multi-color artwork produces low confidence conservative fallback`() {
        val grid = ArrayPixelGrid(200, 200, fillColor = Color.BLACK)
        val palette = intArrayOf(
            Color.rgb(240, 30, 30),
            Color.rgb(30, 220, 40),
            Color.rgb(20, 50, 245),
            Color.rgb(245, 220, 20),
            Color.rgb(180, 30, 220),
            Color.rgb(15, 15, 15)
        )
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                grid.setPixel(x, y, palette[(x * 7 + y * 13) % palette.size])
            }
        }

        val estimate = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(40, 40, 150, 130))

        assertTrue(estimate.confidence < BubbleBackgroundEstimator.SAFE_RENDER_CONFIDENCE_THRESHOLD)
        // Must not blindly default to pure white (#FFFFFF)
        assertTrue(estimate.colorArgb != Color.WHITE)
    }

    @Test
    fun `out-of-bounds OCR region returns safe low-confidence fallback without crashing`() {
        val grid = ArrayPixelGrid(120, 120, fillColor = Color.WHITE)
        val completelyOutside = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(200, 200, 260, 240))
        assertTrue(completelyOutside.confidence < BubbleBackgroundEstimator.SAFE_RENDER_CONFIDENCE_THRESHOLD)
        assertEquals(0, completelyOutside.sampleCount)

        val partiallyOutside = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(-20, -10, 70, 50))
        assertTrue(partiallyOutside.sampleCount > 0)
        assertTrue(colorDistance(partiallyOutside.colorArgb, Color.WHITE) <= 30)
    }

    @Test
    fun `very small OCR region lowers confidence safely`() {
        val grid = ArrayPixelGrid(120, 120, fillColor = Color.rgb(245, 245, 245))
        val tinyEstimate = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(50, 50, 52, 52))
        val normalEstimate = BubbleBackgroundEstimator.estimate(grid, OcrBoundingBox(20, 20, 100, 80))

        assertTrue(tinyEstimate.confidence < normalEstimate.confidence)
    }
}
