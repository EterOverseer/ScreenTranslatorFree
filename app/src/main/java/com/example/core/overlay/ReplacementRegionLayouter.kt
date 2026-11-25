package com.example.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.core.image.BubbleBackgroundEstimator
import com.example.domain.model.TranslatedRegion
import kotlin.math.max
import kotlin.math.min

data class ReplacementLayouterConfig(
    val minFontSizeSp: Float = 10f,
    val maxFontSizeSp: Float = 22f,
    val horizontalPaddingDp: Float = 6f,
    val verticalPaddingDp: Float = 5f,
    val cornerRadiusDp: Float = 12f,
    val regionGapDp: Float = 4f,
    val maxExpandWidthRatio: Float = 1.45f,
    val maxExpandHeightRatio: Float = 1.65f,
    val minSafeConfidence: Float = BubbleBackgroundEstimator.SAFE_RENDER_CONFIDENCE_THRESHOLD
)

data class PlannedReplacementPatch(
    val region: TranslatedRegion,
    val patchRect: OverlayRectF,
    val textLeft: Float,
    val textTop: Float,
    val cornerRadiusPx: Float,
    val backgroundArgb: Int,
    val borderArgb: Int,
    val textArgb: Int,
    val textSizePx: Float,
    val staticLayout: StaticLayout
)

data class ReplacementRenderPlan(
    val patches: List<PlannedReplacementPatch>,
    val fallbackRegions: List<TranslatedRegion>
) {
    val isEmpty: Boolean get() = patches.isEmpty()
}

class ReplacementRegionLayouter(
    private val density: Float,
    private val scaledDensity: Float,
    private val typeface: Typeface = Typeface.DEFAULT,
    private val config: ReplacementLayouterConfig = ReplacementLayouterConfig()
) {
    constructor(
        context: Context,
        config: ReplacementLayouterConfig = ReplacementLayouterConfig()
    ) : this(
        density = context.resources.displayMetrics.density.coerceAtLeast(1f),
        scaledDensity = context.resources.displayMetrics.scaledDensity.coerceAtLeast(1f),
        typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.noto_sans_thai)
        }.getOrNull() ?: Typeface.DEFAULT,
        config = config
    )

    private val measurePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        this.typeface = this@ReplacementRegionLayouter.typeface
    }

    fun buildPlan(
        regions: List<TranslatedRegion>,
        mapper: OverlayCoordinateMapper
    ): ReplacementRenderPlan {
        if (regions.isEmpty() || !mapper.isValid()) {
            return ReplacementRenderPlan(emptyList(), regions)
        }

        // Pre-map all core OCR boxes so expansion/collision checks know about neighbors
        val mappedCores = regions.map { region ->
            region to mapper.mapBox(region.box)
        }

        val placedPatches = ArrayList<PlannedReplacementPatch>(regions.size)
        val fallbackRegions = ArrayList<TranslatedRegion>()

        val padX = dpToPx(config.horizontalPaddingDp)
        val padY = dpToPx(config.verticalPaddingDp)
        val cornerPx = dpToPx(config.cornerRadiusDp)
        val minGapPx = dpToPx(config.regionGapDp)
        val minFontPx = spToPx(config.minFontSizeSp)
        val maxFontPx = spToPx(config.maxFontSizeSp)
        val minPatchWidthPx = dpToPx(24f)
        val minPatchHeightPx = dpToPx(18f)

        for ((index, pair) in mappedCores.withIndex()) {
            val (region, coreRect) = pair
            if (coreRect == null || region.translatedText.isBlank()) {
                fallbackRegions.add(region)
                continue
            }

            // Skip unsafe low-confidence patches over chaotic artwork instead of painting big blobs
            if (region.backgroundConfidence < 0.16f ||
                (region.backgroundConfidence < config.minSafeConfidence &&
                    (coreRect.width < minPatchWidthPx || coreRect.height < minPatchHeightPx))
            ) {
                fallbackRegions.add(region)
                continue
            }

            val neighborRects = buildNeighborObstacles(index, mappedCores, placedPatches)

            val initialPatch = clipAndDeconflictRect(
                candidate = OverlayRectF(
                    left = coreRect.left - padX,
                    top = coreRect.top - padY,
                    right = coreRect.right + padX,
                    bottom = coreRect.bottom + padY
                ),
                core = coreRect,
                mapper = mapper,
                obstacles = neighborRects,
                gapPx = minGapPx
            )

            if (initialPatch.width < minPatchWidthPx || initialPatch.height < minPatchHeightPx) {
                fallbackRegions.add(region)
                continue
            }

            // Estimate starting font size proportionally to core height while clamping to safe min/max
            val idealStartFontPx = (coreRect.height * 0.56f)
                .coerceIn(minFontPx, maxFontPx)

            var fitted = tryFitTextInRect(
                text = region.translatedText,
                rect = initialPatch,
                padX = padX,
                padY = padY,
                startFontPx = idealStartFontPx,
                minFontPx = minFontPx
            )

            var finalPatch = initialPatch
            if (fitted == null) {
                // Attempt conservative expansion within safe limits while avoiding neighbors
                val expandedCandidate = buildConservativeExpansion(
                    core = coreRect,
                    current = initialPatch,
                    padX = padX,
                    padY = padY,
                    mapper = mapper
                )
                val safeExpanded = clipAndDeconflictRect(
                    candidate = expandedCandidate,
                    core = coreRect,
                    mapper = mapper,
                    obstacles = neighborRects,
                    gapPx = minGapPx
                )
                if (safeExpanded.width > initialPatch.width + 2f || safeExpanded.height > initialPatch.height + 2f) {
                    val expandedAttempt = tryFitTextInRect(
                        text = region.translatedText,
                        rect = safeExpanded,
                        padX = padX,
                        padY = padY,
                        startFontPx = min(idealStartFontPx, spToPx(15f)).coerceAtLeast(minFontPx),
                        minFontPx = minFontPx
                    )
                    if (expandedAttempt != null) {
                        fitted = expandedAttempt
                        finalPatch = safeExpanded
                    }
                }
            }

            if (fitted == null) {
                // Cannot fit safely without truncating Thai diacritics or obscuring adjacent regions
                fallbackRegions.add(region)
                continue
            }

            // Final collision guard against already placed patches
            val collides = placedPatches.any { existing ->
                existing.patchRect.intersects(finalPatch, paddingPx = -1f)
            }
            if (collides) {
                fallbackRegions.add(region)
                continue
            }

            val availHeight = (finalPatch.height - padY * 2f).coerceAtLeast(1f)
            val verticalOffset = ((availHeight - fitted.layout.height) / 2f).coerceAtLeast(0f)
            val textLeft = finalPatch.left + padX
            val textTop = finalPatch.top + padY + verticalOffset

            placedPatches.add(
                PlannedReplacementPatch(
                    region = region,
                    patchRect = finalPatch,
                    textLeft = textLeft,
                    textTop = textTop,
                    cornerRadiusPx = min(cornerPx, min(finalPatch.width, finalPatch.height) * 0.35f),
                    backgroundArgb = region.estimatedBackgroundArgb,
                    borderArgb = computeSubtleBorderColor(region.estimatedBackgroundArgb),
                    textArgb = region.estimatedTextArgb,
                    textSizePx = fitted.textSizePx,
                    staticLayout = fitted.layout
                )
            )
        }

        return ReplacementRenderPlan(
            patches = placedPatches,
            fallbackRegions = fallbackRegions
        )
    }

    private data class FittedLayout(
        val textSizePx: Float,
        val layout: StaticLayout
    )

    private fun tryFitTextInRect(
        text: String,
        rect: OverlayRectF,
        padX: Float,
        padY: Float,
        startFontPx: Float,
        minFontPx: Float
    ): FittedLayout? {
        val availWidth = (rect.width - padX * 2f).toInt()
        val availHeight = (rect.height - padY * 2f)
        if (availWidth < dpToPx(14f).toInt() || availHeight < minFontPx * 1.1f) {
            return null
        }

        val stepPx = spToPx(1f).coerceAtLeast(1f)
        var sizePx = max(startFontPx, minFontPx)
        while (sizePx >= minFontPx - 0.01f) {
            val clampedSize = max(sizePx, minFontPx)
            val paint = TextPaint(measurePaint).apply {
                textSize = clampedSize
                color = Color.BLACK
            }
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, availWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(true)
                .setLineSpacing(0f, 1.08f)
                .build()

            val maxLineWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineMax(it) } ?: 0f
            val fitsHeight = layout.height <= availHeight + 1.5f
            val fitsWidth = maxLineWidth <= availWidth + 2f
            // Guard against unreadable forced single-character-per-line wrapping on super narrow boxes
            val unreasonableWrapping = layout.lineCount > 6 && availWidth < clampedSize * 2.2f

            if (fitsHeight && fitsWidth && !unreasonableWrapping) {
                return FittedLayout(clampedSize, layout)
            }
            if (clampedSize <= minFontPx + 0.01f) break
            sizePx -= stepPx
        }
        return null
    }

    private fun buildConservativeExpansion(
        core: OverlayRectF,
        current: OverlayRectF,
        padX: Float,
        padY: Float,
        mapper: OverlayCoordinateMapper
    ): OverlayRectF {
        val extraW = ((core.width * (config.maxExpandWidthRatio - 1f)) + padX)
            .coerceIn(dpToPx(8f), dpToPx(56f))
        val extraH = ((core.height * (config.maxExpandHeightRatio - 1f)) + padY)
            .coerceIn(dpToPx(8f), dpToPx(48f))

        return OverlayRectF(
            left = max(mapper.safeLeft, current.left - extraW * 0.5f),
            top = max(mapper.safeTop, current.top - extraH * 0.45f),
            right = min(mapper.safeRight, current.right + extraW * 0.5f),
            bottom = min(mapper.safeBottom, current.bottom + extraH * 0.55f)
        )
    }

    private fun buildNeighborObstacles(
        currentIndex: Int,
        mappedCores: List<Pair<TranslatedRegion, OverlayRectF?>>,
        placedPatches: List<PlannedReplacementPatch>
    ): List<OverlayRectF> {
        val obstacles = ArrayList<OverlayRectF>(mappedCores.size + placedPatches.size)
        for (p in placedPatches) {
            obstacles.add(p.patchRect)
        }
        for (i in mappedCores.indices) {
            if (i != currentIndex) {
                mappedCores[i].second?.let { obstacles.add(it) }
            }
        }
        return obstacles
    }

    private fun clipAndDeconflictRect(
        candidate: OverlayRectF,
        core: OverlayRectF,
        mapper: OverlayCoordinateMapper,
        obstacles: List<OverlayRectF>,
        gapPx: Float
    ): OverlayRectF {
        var left = max(candidate.left, mapper.safeLeft)
        var top = max(candidate.top, mapper.safeTop)
        var right = min(candidate.right, mapper.safeRight)
        var bottom = min(candidate.bottom, mapper.safeBottom)

        for (obs in obstacles) {
            if (!OverlayRectF(left, top, right, bottom).intersects(obs, paddingPx = gapPx)) continue

            // Clamp whichever edge intrudes into the obstacle while keeping core center intact
            val overlapX = min(right, obs.right) - max(left, obs.left)
            val overlapY = min(bottom, obs.bottom) - max(top, obs.top)

            if (overlapX > 0f || overlapY > 0f) {
                if (core.centerX < obs.centerX && right > obs.left - gapPx && core.right <= obs.left + gapPx) {
                    right = min(right, obs.left - gapPx)
                } else if (core.centerX > obs.centerX && left < obs.right + gapPx && core.left >= obs.right - gapPx) {
                    left = max(left, obs.right + gapPx)
                } else if (core.centerY < obs.centerY && bottom > obs.top - gapPx && core.bottom <= obs.top + gapPx) {
                    bottom = min(bottom, obs.top - gapPx)
                } else if (core.centerY > obs.centerY && top < obs.bottom + gapPx && core.top >= obs.bottom - gapPx) {
                    top = max(top, obs.bottom + gapPx)
                }
            }
        }

        return OverlayRectF(
            left = left,
            top = top,
            right = max(left, right),
            bottom = max(top, bottom)
        )
    }

    private fun computeSubtleBorderColor(bgArgb: Int): Int {
        val lum = BubbleBackgroundEstimator.relativeLuminance(bgArgb)
        return if (lum >= 0.42f) {
            Color.argb(48, 15, 23, 42)
        } else {
            Color.argb(72, 226, 232, 240)
        }
    }

    private fun dpToPx(dp: Float): Float = dp * density
    private fun spToPx(sp: Float): Float = sp * scaledDensity
}
