package com.example.core.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.app.EterScreenApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ScrollEventFilter(
    private val ownPackage: String,
    private val duplicateWindowMs: Long = 500L
) {
    private var lastSignature: String? = null
    private var lastEventTimeMs: Long = -1L

    fun shouldTrigger(
        eventType: Int,
        eventPackage: String?,
        scrollX: Int,
        scrollY: Int,
        fromIndex: Int,
        toIndex: Int,
        timestampMs: Long
    ): Boolean {
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return false
        if (eventPackage.isNullOrBlank()) return false
        if (eventPackage == ownPackage || eventPackage == SYSTEM_UI_PACKAGE) return false

        val hasCoords = scrollX >= 0 || scrollY >= 0 || fromIndex >= 0 || toIndex >= 0
        if (hasCoords) {
            val signature = "$eventPackage|$scrollX|$scrollY|$fromIndex|$toIndex"
            if (signature == lastSignature &&
                lastEventTimeMs >= 0L &&
                timestampMs - lastEventTimeMs < duplicateWindowMs
            ) {
                return false
            }
            lastSignature = signature
            lastEventTimeMs = timestampMs
        }
        return true
    }

    fun reset() {
        lastSignature = null
        lastEventTimeMs = -1L
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

/**
 * Optional, minimal AccessibilityService used strictly to detect real scroll activity
 * (TYPE_VIEW_SCROLLED) and trigger debounced screen capture after scrolling stops.
 * Never reads or stores window content (canRetrieveWindowContent="false").
 */
class AutoScrollAccessibilityService : AccessibilityService() {

    private val eventFilter by lazy { ScrollEventFilter(ownPackage = packageName) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true
        eventFilter.reset()
        (application as? EterScreenApplication)?.container?.let { container ->
            container.translateScreenUseCase.updateAccessibilityStatus(true)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val shouldTrigger = eventFilter.shouldTrigger(
            eventType = event.eventType,
            eventPackage = event.packageName?.toString(),
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            timestampMs = System.currentTimeMillis()
        )
        if (!shouldTrigger) return

        (application as? EterScreenApplication)?.container?.autoCaptureScheduler?.onScrollActivity()
    }

    override fun onInterrupt() {
        eventFilter.reset()
        (application as? EterScreenApplication)?.container?.autoCaptureScheduler?.cancelPending()
    }

    override fun onDestroy() {
        _isConnected.value = false
        eventFilter.reset()
        (application as? EterScreenApplication)?.container?.let { container ->
            container.autoCaptureScheduler.cancelPending()
            container.translateScreenUseCase.updateAccessibilityStatus(false)
        }
        super.onDestroy()
    }

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
