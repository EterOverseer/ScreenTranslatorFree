package com.example.core.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.app.EterScreenApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional, minimal AccessibilityService used strictly to detect scroll activity
 * and trigger debounced screen capture after scrolling stops.
 * Never reads or stores window content (canRetrieveWindowContent="false").
 */
class AutoScrollAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true
        (application as? EterScreenApplication)?.container?.let { container ->
            container.translateScreenUseCase.updateAccessibilityStatus(true)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        // Ignore events originating from our own app/overlay
        if (event.packageName == packageName) return

        (application as? EterScreenApplication)?.container?.autoCaptureScheduler?.onScrollActivity()
    }

    override fun onInterrupt() {
        (application as? EterScreenApplication)?.container?.autoCaptureScheduler?.cancelPending()
    }

    override fun onDestroy() {
        _isConnected.value = false
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
