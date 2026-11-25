package com.example.core.image

import android.graphics.Bitmap
import android.graphics.Color
import com.example.domain.model.OcrBoundingBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface PixelGrid {
    val width: Int
    val height: Int
    fun getPixel(x: Int, y: Int): Int
}

class BitmapPixelGrid(private val bitmap: Bitmap) : PixelGrid {
    override val width: Int get() = if (bitmap.isRecycled) 0 else bitmap.width
    override val height: Int get() = if (bitmap.isRecycled) 0 else bitmap.height
    override fun getPixel(x: Int, y: Int): Int = bitmap.getPixel(x, y)
}

data class BubbleBackgroundEstimate(
    val colorArgb: Int,
    val textColorArgb: Int,
    val confidence: Float,
    val isLightBackground: Boolean,
    val sampleCount: Int
)

object BubbleBackgroundEstimator {

    const val SAFE_RENDER_CONFIDENCE_THRESHOLD = 0.25f

    private const val COLOR_LIGHT_TEXT = 0xFFF8FAFC.toInt()
    private const val COLOR_DARK_TEXT = 0xFF111827.toInt()
    private const val FALLBACK_LIGHT_NEUTRAL = 0xFFEAECEF.toInt()
    private const val FALLBACK_MID_NEUTRAL = 0xFF3E4552.toInt()
    private const val FALLBACK_DARK_NEUTRAL = 0xFF1A1E29.toInt()

    fun estimate(bitmap: Bitmap?, box: OcrBoundingBox): BubbleBackgroundEstimate {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return lowConfidenceFallback(medianLum = 128, sampleCount = 0)
        }
        return estimate(BitmapPixelGrid(bitmap), box)
    }

    fun estimate(grid: PixelGrid, box: OcrBoundingBox): BubbleBackgroundEstimate {
        val imgW = grid.width
        val imgH = grid.height
        if (imgW <= 0 || imgH <= 0) {
            return lowConfidenceFallback(medianLum = 128, sampleCount = 0)
        }

        // Reject completely out-of-bounds or zero-area boxes safely
        if (box.right <= 0 || box.bottom <= 0 || box.left >= imgW || box.top >= imgH) {
            return lowConfidenceFallback(medianLum = 128, sampleCount = 0)
        }

        val clampedLeft = box.left.coerceIn(0, imgW - 1)
        val clampedTop = box.top.coerceIn(0, imgH - 1)
        val clampedRight = box.right.coerceIn(clampedLeft + 1, imgW)
        val clampedBottom = box.bottom.coerceIn(clampedTop + 1, imgH)
        val boxW = clampedRight - clampedLeft
        val boxH = clampedBottom - clampedTop

        if (boxW <= 0 || boxH <= 0) {
            return lowConfidenceFallback(medianLum = 128, sampleCount = 0)
        }

        val ringSamples = samplePerimeterRing(
            grid = grid,
            left = clampedLeft,
            top = clampedTop,
            right = clampedRight,
            bottom = clampedBottom,
            imgW = imgW,
            imgH = imgH
        )

        val interiorBorderSamples = if (ringSamples.size < 12) {
            sampleInteriorMargin(grid, clampedLeft, clampedTop, clampedRight, clampedBottom)
        } else {
            emptyList()
        }

        val rawSamples = if (ringSamples.isNotEmpty()) {
            ringSamples + interiorBorderSamples
        } else {
            interiorBorderSamples
        }

        if (rawSamples.isEmpty()) {
            return lowConfidenceFallback(medianLum = 128, sampleCount = 0)
        }

        val filtered = filterStrokeAndOutliers(rawSamples)
        val effectiveSamples = if (filtered.size >= 4) filtered else rawSamples

        val cluster = findDominantColorCluster(effectiveSamples)
        val estR = medianChannel(cluster) { Color.red(it) }
        val estG = medianChannel(cluster) { Color.green(it) }
        val estB = medianChannel(cluster) { Color.blue(it) }
        val estimatedArgb = Color.argb(255, estR, estG, estB)

        val allLums = effectiveSamples.map { perceivedLuminance(it) }.sorted()
        val medianLum = allLums[allLums.size / 2]

        val mad = computeMeanAbsoluteDeviation(effectiveSamples, estR, estG, estB)
        val clusterRatio = cluster.size.toFloat() / effectiveSamples.size.toFloat()
        val coverageFactor = (ringSamples.size / 20f).coerceIn(0f, 1f)
        val sizeFactor = (min(boxW, boxH) / 14f).coerceIn(0.20f, 1f)
        val spreadPenalty = (mad / 52f).coerceIn(0f, 0.85f)
        val consistencyFactor = (clusterRatio * (1f - spreadPenalty)).coerceIn(0f, 1f)
        val uniformityGate = (consistencyFactor / 0.32f).coerceIn(0.10f, 1f)

        val confidence = ((coverageFactor * 0.25f + sizeFactor * 0.15f + consistencyFactor * 0.60f) * uniformityGate)
            .coerceIn(0f, 1f)

        // When ring coverage or color homogeneity is too weak (e.g. complex artwork / tiny OOB region),
        // use a conservative brightness-based fallback with low confidence instead of pure white.
        if (confidence < SAFE_RENDER_CONFIDENCE_THRESHOLD || ringSamples.size < 4 || consistencyFactor < 0.24f) {
            return lowConfidenceFallback(
                medianLum = medianLum,
                sampleCount = rawSamples.size,
                confidenceOverride = min(confidence, SAFE_RENDER_CONFIDENCE_THRESHOLD - 0.03f)
            )
        }

        val isLight = relativeLuminance(estimatedArgb) >= 0.42f
        val textArgb = if (isLight) COLOR_DARK_TEXT else COLOR_LIGHT_TEXT

        return BubbleBackgroundEstimate(
            colorArgb = estimatedArgb,
            textColorArgb = textArgb,
            confidence = confidence,
            isLightBackground = isLight,
            sampleCount = rawSamples.size
        )
    }

    private fun samplePerimeterRing(
        grid: PixelGrid,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imgW: Int,
        imgH: Int
    ): List<Int> {
        val boxW = right - left
        val boxH = bottom - top
        val ringThickness = (min(boxW, boxH) * 0.18f).roundToInt().coerceIn(2, 14)
        val offsets = if (ringThickness >= 6) {
            listOf(2, ringThickness / 2, ringThickness)
        } else {
            listOf(1, ringThickness)
        }

        val samples = ArrayList<Int>(96)
        val xSteps = (boxW / 8).coerceIn(4, 14)
        val ySteps = (boxH / 8).coerceIn(4, 14)

        for (offset in offsets) {
            val topY = top - offset
            val bottomY = (bottom - 1) + offset
            for (i in 0 until xSteps) {
                val x = (left + ((i + 0.5f) / xSteps) * boxW).toInt().coerceIn(0, imgW - 1)
                if (topY in 0 until imgH) samples.add(grid.getPixel(x, topY))
                if (bottomY in 0 until imgH) samples.add(grid.getPixel(x, bottomY))
            }

            val leftX = left - offset
            val rightX = (right - 1) + offset
            for (j in 0 until ySteps) {
                val y = (top + ((j + 0.5f) / ySteps) * boxH).toInt().coerceIn(0, imgH - 1)
                if (leftX in 0 until imgW) samples.add(grid.getPixel(leftX, y))
                if (rightX in 0 until imgW) samples.add(grid.getPixel(rightX, y))
            }
        }
        return samples
    }

    private fun sampleInteriorMargin(
        grid: PixelGrid,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): List<Int> {
        val boxW = right - left
        val boxH = bottom - top
        val samples = ArrayList<Int>(20)
        val cols = min(4, boxW).coerceAtLeast(1)
        val rows = min(4, boxH).coerceAtLeast(1)
        for (c in 0 until cols) {
            val x = (left + (c * boxW) / max(1, cols - 1)).coerceIn(left, right - 1)
            samples.add(grid.getPixel(x, top))
            samples.add(grid.getPixel(x, bottom - 1))
        }
        for (r in 0 until rows) {
            val y = (top + (r * boxH) / max(1, rows - 1)).coerceIn(top, bottom - 1)
            samples.add(grid.getPixel(left, y))
            samples.add(grid.getPixel(right - 1, y))
        }
        return samples
    }

    private fun filterStrokeAndOutliers(samples: List<Int>): List<Int> {
        if (samples.size < 6) return samples

        val sortedByLum = samples.sortedBy { perceivedLuminance(it) }
        val p75Lum = perceivedLuminance(sortedByLum[(sortedByLum.size * 3) / 4])
        val p25Lum = perceivedLuminance(sortedByLum[sortedByLum.size / 4])

        // Trim dark ink outlines on predominantly light bubbles, or bright text glyphs on dark bubbles
        val strokeFiltered = when {
            p75Lum >= 135 -> {
                val nonInk = samples.filter { perceivedLuminance(it) >= 70 }
                if (nonInk.size >= 4) nonInk else samples
            }
            p25Lum <= 105 -> {
                val nonHighlight = samples.filter { perceivedLuminance(it) <= 190 }
                if (nonHighlight.size >= 4) nonHighlight else samples
            }
            else -> samples
        }

        if (strokeFiltered.size < 10) return strokeFiltered
        val lumSorted = strokeFiltered.sortedBy { perceivedLuminance(it) }
        val trim = (lumSorted.size * 0.10f).toInt().coerceAtLeast(1)
        return if (lumSorted.size - trim * 2 >= 4) {
            lumSorted.subList(trim, lumSorted.size - trim)
        } else {
            lumSorted
        }
    }

    private fun findDominantColorCluster(samples: List<Int>): List<Int> {
        if (samples.size <= 3) return samples

        // Quantize to 16-unit RGB buckets so slightly shaded bubble tones group together
        val buckets = HashMap<Int, MutableList<Int>>()
        for (pixel in samples) {
            val rBin = (Color.red(pixel) shr 4)
            val gBin = (Color.green(pixel) shr 4)
            val bBin = (Color.blue(pixel) shr 4)
            val key = (rBin shl 8) or (gBin shl 4) or bBin
            buckets.getOrPut(key) { mutableListOf() }.add(pixel)
        }

        val bestBucket = buckets.maxByOrNull { it.value.size }?.value ?: return samples
        val seedR = medianChannel(bestBucket) { Color.red(it) }
        val seedG = medianChannel(bestBucket) { Color.green(it) }
        val seedB = medianChannel(bestBucket) { Color.blue(it) }

        val cluster = samples.filter { pixel ->
            abs(Color.red(pixel) - seedR) <= 28 &&
                abs(Color.green(pixel) - seedG) <= 28 &&
                abs(Color.blue(pixel) - seedB) <= 28
        }
        return if (cluster.isNotEmpty()) cluster else bestBucket
    }

    private fun computeMeanAbsoluteDeviation(
        samples: List<Int>,
        refR: Int,
        refG: Int,
        refB: Int
    ): Float {
        if (samples.isEmpty()) return 255f
        var sum = 0L
        for (pixel in samples) {
            sum += abs(Color.red(pixel) - refR)
            sum += abs(Color.green(pixel) - refG)
            sum += abs(Color.blue(pixel) - refB)
        }
        return sum.toFloat() / (samples.size * 3f)
    }

    private inline fun medianChannel(pixels: List<Int>, selector: (Int) -> Int): Int {
        val values = IntArray(pixels.size) { selector(pixels[it]) }
        values.sort()
        return values[values.size / 2]
    }

    private fun lowConfidenceFallback(
        medianLum: Int,
        sampleCount: Int,
        confidenceOverride: Float = 0.12f
    ): BubbleBackgroundEstimate {
        val bg = when {
            medianLum >= 165 -> FALLBACK_LIGHT_NEUTRAL
            medianLum >= 95 -> FALLBACK_MID_NEUTRAL
            else -> FALLBACK_DARK_NEUTRAL
        }
        val isLight = relativeLuminance(bg) >= 0.42f
        return BubbleBackgroundEstimate(
            colorArgb = bg,
            textColorArgb = if (isLight) COLOR_DARK_TEXT else COLOR_LIGHT_TEXT,
            confidence = confidenceOverride.coerceIn(0.05f, SAFE_RENDER_CONFIDENCE_THRESHOLD - 0.01f),
            isLightBackground = isLight,
            sampleCount = sampleCount
        )
    }

    fun perceivedLuminance(argb: Int): Int {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    fun relativeLuminance(argb: Int): Float {
        val r = Color.red(argb) / 255f
        val g = Color.green(argb) / 255f
        val b = Color.blue(argb) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
