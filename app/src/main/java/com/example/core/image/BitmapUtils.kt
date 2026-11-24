package com.example.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object BitmapUtils {
    private const val TAG = "BitmapUtils"

    /**
     * Computes a 64-bit difference hash (dHash) on a 9x8 grayscale grid.
     * Useful for detecting whether two consecutive captured screens are virtually identical
     * without storing or hashing raw full-resolution screenshots.
     */
    fun computeDHash(bitmap: Bitmap): Long {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0L
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        return try {
            var hash = 0L
            var bitIndex = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val leftLum = luminance(scaled.getPixel(x, y))
                    val rightLum = luminance(scaled.getPixel(x + 1, y))
                    if (leftLum > rightLum) {
                        hash = hash or (1L shl bitIndex)
                    }
                    bitIndex++
                }
            }
            hash
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    fun hammingDistance(a: Long, b: Long): Int {
        return java.lang.Long.bitCount(a xor b)
    }

    fun isScreenUnchanged(previousHash: Long?, currentHash: Long, maxBitDiff: Int = 2): Boolean {
        if (previousHash == null || previousHash == 0L || currentHash == 0L) return false
        return hammingDistance(previousHash, currentHash) <= maxBitDiff
    }

    /**
     * Scales down ultra-high-resolution captures (e.g., RedMagic 11S Pro) so OCR remains fast
     * and avoids OOM while preserving crisp text edges.
     */
    fun downscaleForOcr(source: Bitmap, maxLongEdge: Int = 1600): Bitmap {
        val width = source.width
        val height = source.height
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) {
            return source
        }
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    /**
     * Saves a lightweight compressed thumbnail ONLY when the user explicitly opts in
     * to optional history image retention in Settings.
     */
    fun saveOptionalThumbnail(context: Context, source: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, "history_thumbs").apply { mkdirs() }
            val thumb = downscaleForOcr(source, maxLongEdge = 540)
            val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 60, out)
            }
            if (thumb !== source && !thumb.isRecycled) {
                thumb.recycle()
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save optional history thumbnail", e)
            null
        }
    }

    fun safeRecycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun luminance(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}
