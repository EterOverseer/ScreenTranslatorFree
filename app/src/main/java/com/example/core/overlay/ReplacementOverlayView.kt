package com.example.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Single transparent full-screen drawing surface for Replace + Bubble mode.
 * Pre-allocates Paint and RectF instances to avoid allocations during draw passes.
 */
class ReplacementOverlayView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density.coerceAtLeast(1f) * 1.1f
    }
    private val drawRect = RectF()

    private var patches: List<PlannedReplacementPatch> = emptyList()

    var onGeometryChanged: ((width: Int, height: Int) -> Unit)? = null

    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun submitPatches(newPatches: List<PlannedReplacementPatch>) {
        if (patches === newPatches && patches.isEmpty() && newPatches.isEmpty()) return
        patches = newPatches
        invalidate()
    }

    fun clearPatches() {
        if (patches.isEmpty()) return
        patches = emptyList()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            onGeometryChanged?.invoke(w, h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = patches
        if (current.isEmpty()) return

        for (i in current.indices) {
            val patch = current[i]
            drawRect.set(
                patch.patchRect.left,
                patch.patchRect.top,
                patch.patchRect.right,
                patch.patchRect.bottom
            )

            bgPaint.color = patch.backgroundArgb
            canvas.drawRoundRect(drawRect, patch.cornerRadiusPx, patch.cornerRadiusPx, bgPaint)

            borderPaint.color = patch.borderArgb
            canvas.drawRoundRect(drawRect, patch.cornerRadiusPx, patch.cornerRadiusPx, borderPaint)

            val saveCount = canvas.save()
            canvas.clipRect(drawRect)
            canvas.translate(patch.textLeft, patch.textTop)
            patch.staticLayout.paint.color = patch.textArgb
            patch.staticLayout.draw(canvas)
            canvas.restoreToCount(saveCount)
        }
    }
}
