package com.example.core.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.example.core.permissions.PermissionHelper
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureStatus
import com.example.domain.model.OverlayRenderStatus
import com.example.domain.model.TranslationDisplayMode
import com.example.domain.model.TranslatorState

class ReplacementOverlayManager(
    private val context: Context,
    private val onRenderOutcome: (OverlayRenderStatus, String?) -> Unit = { _, _ -> }
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layouter by lazy { ReplacementRegionLayouter(context) }

    private var overlayView: ReplacementOverlayView? = null
    private var windowAdded = false
    private var suspendedForCapture = false

    private var lastAppliedGenerationId: Long = -1L
    private var lastDisplayMode: TranslationDisplayMode? = null
    private var lastViewW: Int = 0
    private var lastViewH: Int = 0
    private var staleOrientationCleared: Boolean = false

    private var latestSettings = AppSettings()
    private var latestState = TranslatorState()
    private var latestSessionActive = false

    private val screenLocation = IntArray(2)

    fun update(
        settings: AppSettings,
        state: TranslatorState,
        isSessionActive: Boolean
    ) {
        latestSettings = settings
        latestState = state
        latestSessionActive = isSessionActive

        if (settings.translationDisplayMode != TranslationDisplayMode.REPLACE) {
            lastAppliedGenerationId = -1L
            lastDisplayMode = settings.translationDisplayMode
            hide()
            onRenderOutcome(OverlayRenderStatus.PANEL_ONLY, null)
            return
        }

        if (!isSessionActive || state.captureStatus == CaptureStatus.IDLE) {
            lastAppliedGenerationId = -1L
            hide()
            onRenderOutcome(OverlayRenderStatus.IDLE, null)
            return
        }

        if (!settings.overlayEnabled) {
            clearPatches()
            setOverlayVisibility(View.GONE)
            onRenderOutcome(
                OverlayRenderStatus.IDLE,
                "หน้าต่างลอยถูกปิดอยู่ — เปิด 'แสดงปุ่มลอยบนหน้าจอ' ในตั้งค่าเพื่อวางคำแปลทับ"
            )
            return
        }

        if (!PermissionHelper.hasOverlayPermission(context)) {
            hide()
            onRenderOutcome(
                OverlayRenderStatus.PERMISSION_REQUIRED,
                "ยังไม่ได้อนุญาตสิทธิ์แสดงหน้าต่างทับแอปอื่น (Overlay) จึงแสดงคำแปลในแผงควบคุมแทน"
            )
            return
        }

        if (state.captureStatus == CaptureStatus.PAUSED) {
            clearPatches()
            setOverlayVisibility(View.INVISIBLE)
            onRenderOutcome(OverlayRenderStatus.PAUSED, null)
            return
        }

        ensureWindowAttached()
        val view = overlayView ?: run {
            onRenderOutcome(
                OverlayRenderStatus.FAILED,
                "ไม่สามารถสร้างเลเยอร์แสดงคำแปลทับหน้าจอได้ (ดูคำแปลในแผงลอยแทน)"
            )
            return
        }

        if (suspendedForCapture) {
            setOverlayVisibility(View.INVISIBLE)
            onRenderOutcome(OverlayRenderStatus.SUSPENDED_FOR_CAPTURE, null)
            return
        }

        val regions = state.translatedRegions
        if (regions.isEmpty() || state.sourceImageWidth <= 0 || state.sourceImageHeight <= 0) {
            clearPatches()
            setOverlayVisibility(View.INVISIBLE)
            if (lastDisplayMode != settings.translationDisplayMode) {
                lastDisplayMode = settings.translationDisplayMode
            }
            return
        }

        val (targetW, targetH, displayW, displayH) = resolveOverlayDimensions(view)
        val mapper = createMapper(
            sourceW = state.sourceImageWidth,
            sourceH = state.sourceImageHeight,
            view = view,
            viewW = targetW,
            viewH = targetH,
            displayW = displayW,
            displayH = displayH
        )

        if (!mapper.isValid()) {
            // Orientation flipped or window metrics changed relative to captured frame; wait for valid new frame
            staleOrientationCleared = true
            clearPatches()
            setOverlayVisibility(View.INVISIBLE)
            onRenderOutcome(
                OverlayRenderStatus.IDLE,
                "หน้าจอมีการหมุนหรือเปลี่ยนขนาด กรุณากดแปลใหม่อีกครั้ง"
            )
            return
        }

        val needsRebuild = staleOrientationCleared ||
            lastAppliedGenerationId != state.captureGenerationId ||
            lastDisplayMode != settings.translationDisplayMode ||
            lastViewW != targetW ||
            lastViewH != targetH

        if (needsRebuild) {
            staleOrientationCleared = false
            lastAppliedGenerationId = state.captureGenerationId
            lastDisplayMode = settings.translationDisplayMode
            lastViewW = targetW
            lastViewH = targetH

            val plan = layouter.buildPlan(regions, mapper)
            if (plan.patches.isEmpty()) {
                view.clearPatches()
                setOverlayVisibility(View.INVISIBLE)
                onRenderOutcome(
                    OverlayRenderStatus.PANEL_ONLY,
                    "พื้นที่ข้อความไม่เหมาะกับการวางทับโดยตรง จึงแสดงคำแปลในแผงควบคุมแทน"
                )
            } else {
                view.submitPatches(plan.patches)
                setOverlayVisibility(View.VISIBLE)
                if (plan.fallbackRegions.isNotEmpty()) {
                    onRenderOutcome(
                        OverlayRenderStatus.PARTIAL_FALLBACK,
                        "วางทับ ${plan.patches.size}/${regions.size} ตำแหน่ง (ส่วนที่เหลืออ่านได้ที่แผงควบคุม)"
                    )
                } else {
                    onRenderOutcome(OverlayRenderStatus.ACTIVE_REPLACE, null)
                }
            }
        } else {
            setOverlayVisibility(if (view.visibility == View.VISIBLE || latestState.translatedRegions.isNotEmpty()) View.VISIBLE else View.INVISIBLE)
        }
    }

    fun clearPatches() {
        overlayView?.clearPatches()
        lastAppliedGenerationId = -1L
    }

    fun handleScreenMetricsChange() {
        staleOrientationCleared = true
        clearPatches()
        setOverlayVisibility(View.INVISIBLE)
        lastViewW = 0
        lastViewH = 0
        if (windowAdded) {
            overlayView?.let { view ->
                val params = createLayoutParams()
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
    }

    fun suspendForCapture(): Boolean {
        val view = overlayView ?: return false
        val wasVisible = windowAdded && view.visibility == View.VISIBLE
        suspendedForCapture = true
        view.visibility = View.INVISIBLE
        return wasVisible
    }

    fun restoreAfterCapture() {
        suspendedForCapture = false
        update(latestSettings, latestState, latestSessionActive)
    }

    fun hide() {
        suspendedForCapture = false
        clearPatches()
        overlayView?.let { view ->
            if (windowAdded) {
                runCatching { windowManager.removeView(view) }
            }
            view.onGeometryChanged = null
        }
        overlayView = null
        windowAdded = false
        lastViewW = 0
        lastViewH = 0
    }

    private fun ensureWindowAttached() {
        if (windowAdded && overlayView != null) return
        val view = ReplacementOverlayView(context).apply {
            visibility = View.INVISIBLE
            onGeometryChanged = { newW, newH ->
                if (newW != lastViewW || newH != lastViewH) {
                    val srcW = latestState.sourceImageWidth
                    val srcH = latestState.sourceImageHeight
                    if (srcW > 0 && srcH > 0) {
                        val tempMapper = OverlayCoordinateMapper(
                            sourceWidth = srcW,
                            sourceHeight = srcH,
                            overlayWidth = newW,
                            overlayHeight = newH
                        )
                        if (!tempMapper.isOrientationCompatible()) {
                            staleOrientationCleared = true
                            clearPatches()
                        } else {
                            update(latestSettings, latestState, latestSessionActive)
                        }
                    }
                }
            }
        }
        val params = createLayoutParams()
        val added = runCatching {
            windowManager.addView(view, params)
            true
        }.getOrElse { false }

        if (added) {
            overlayView = view
            windowAdded = true
        }
    }

    private fun setOverlayVisibility(visibility: Int) {
        val view = overlayView ?: return
        if (view.visibility != visibility) {
            view.visibility = visibility
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private data class OverlayDims(
        val viewW: Int,
        val viewH: Int,
        val displayW: Int,
        val displayH: Int
    )

    private fun resolveOverlayDimensions(view: ReplacementOverlayView): OverlayDims {
        val displayW: Int
        val displayH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            displayW = bounds.width()
            displayH = bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            displayW = size.x
            displayH = size.y
        }
        val viewW = if (view.width > 0) view.width else displayW
        val viewH = if (view.height > 0) view.height else displayH
        return OverlayDims(viewW, viewH, displayW, displayH)
    }

    private fun createMapper(
        sourceW: Int,
        sourceH: Int,
        view: ReplacementOverlayView,
        viewW: Int,
        viewH: Int,
        displayW: Int,
        displayH: Int
    ): OverlayCoordinateMapper {
        var offsetX = 0f
        var offsetY = 0f
        if (view.isAttachedToWindow) {
            view.getLocationOnScreen(screenLocation)
            offsetX = screenLocation[0].toFloat()
            offsetY = screenLocation[1].toFloat()
        }

        val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && view.rootWindowInsets != null) {
            val sysInsets = view.rootWindowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            OverlayInsets(
                left = (sysInsets.left - offsetX.toInt()).coerceAtLeast(0),
                top = (sysInsets.top - offsetY.toInt()).coerceAtLeast(0),
                right = sysInsets.right.coerceAtLeast(0),
                bottom = sysInsets.bottom.coerceAtLeast(0)
            )
        } else {
            OverlayInsets()
        }

        return OverlayCoordinateMapper(
            sourceWidth = sourceW,
            sourceHeight = sourceH,
            overlayWidth = viewW,
            overlayHeight = viewH,
            displayWidth = displayW,
            displayHeight = displayH,
            windowOffsetX = offsetX,
            windowOffsetY = offsetY,
            insets = insets
        )
    }
}
