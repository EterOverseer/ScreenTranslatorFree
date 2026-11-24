package com.example.core.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.app.EterScreenApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Full-edition-only AccessibilityService used strictly to detect real scroll activity
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
