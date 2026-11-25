package com.example.core.overlay

import com.example.domain.model.OcrBoundingBox
import kotlin.math.max
import kotlin.math.min

data class OverlayInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

data class OverlayRectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height

    fun intersects(other: OverlayRectF, paddingPx: Float = 0f): Boolean {
        return left - paddingPx < other.right &&
            right + paddingPx > other.left &&
            top - paddingPx < other.bottom &&
            bottom + paddingPx > other.top
    }
}

class OverlayCoordinateMapper(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val overlayWidth: Int,
    val overlayHeight: Int,
    val displayWidth: Int = overlayWidth,
    val displayHeight: Int = overlayHeight,
    val windowOffsetX: Float = 0f,
    val windowOffsetY: Float = 0f,
    val insets: OverlayInsets = OverlayInsets()
) {
    val safeLeft: Float = insets.left.coerceAtLeast(0).toFloat()
    val safeTop: Float = insets.top.coerceAtLeast(0).toFloat()
    val safeRight: Float = (overlayWidth - insets.right.coerceAtLeast(0)).toFloat().coerceAtLeast(safeLeft)
    val safeBottom: Float = (overlayHeight - insets.bottom.coerceAtLeast(0)).toFloat().coerceAtLeast(safeTop)

    val scaleX: Float = if (sourceWidth > 0 && displayWidth > 0) {
        displayWidth.toFloat() / sourceWidth.toFloat()
    } else 0f

    val scaleY: Float = if (sourceHeight > 0 && displayHeight > 0) {
        displayHeight.toFloat() / sourceHeight.toFloat()
    } else 0f

    fun isValid(): Boolean {
        return sourceWidth > 0 &&
            sourceHeight > 0 &&
            overlayWidth > 0 &&
            overlayHeight > 0 &&
            displayWidth > 0 &&
            displayHeight > 0 &&
            safeRight > safeLeft &&
            safeBottom > safeTop &&
            isOrientationCompatible()
    }

    fun isOrientationCompatible(): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return false
        val srcSquare = kotlin.math.abs(sourceWidth - sourceHeight) <= min(sourceWidth, sourceHeight) * 0.1f
        val dstSquare = kotlin.math.abs(displayWidth - displayHeight) <= min(displayWidth, displayHeight) * 0.1f
        if (srcSquare || dstSquare) return true
        val srcPortrait = sourceHeight >= sourceWidth
        val dstPortrait = displayHeight >= displayWidth
        return srcPortrait == dstPortrait
    }

    fun mapBox(box: OcrBoundingBox): OverlayRectF? {
        if (!isValid()) return null
        if (box.width <= 0 || box.height <= 0) return null

        val rawLeft = box.left * scaleX - windowOffsetX
        val rawTop = box.top * scaleY - windowOffsetY
        val rawRight = box.right * scaleX - windowOffsetX
        val rawBottom = box.bottom * scaleY - windowOffsetY

        val clippedLeft = max(rawLeft, safeLeft)
        val clippedTop = max(rawTop, safeTop)
        val clippedRight = min(rawRight, safeRight)
        val clippedBottom = min(rawBottom, safeBottom)

        if (clippedRight - clippedLeft < 2f || clippedBottom - clippedTop < 2f) {
            return null
        }

        return OverlayRectF(
            left = clippedLeft,
            top = clippedTop,
            right = clippedRight,
            bottom = clippedBottom
        )
    }
}
