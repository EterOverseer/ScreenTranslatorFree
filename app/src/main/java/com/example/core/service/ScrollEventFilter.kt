package com.example.core.service

import android.view.accessibility.AccessibilityEvent

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
