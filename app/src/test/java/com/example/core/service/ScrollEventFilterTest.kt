package com.example.core.service

import android.view.accessibility.AccessibilityEvent
import com.example.capture.AutoCaptureScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollEventFilterTest {

    @Test
    fun `filter accepts only TYPE_VIEW_SCROLLED from external non-systemui packages`() {
        val filter = ScrollEventFilter(ownPackage = "com.eteroverseer.screentranslator")

        // Reject TYPE_WINDOW_CONTENT_CHANGED noise (clocks, banners, animations)
        assertFalse(
            filter.shouldTrigger(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                eventPackage = "jp.naver.line.android",
                scrollX = 0,
                scrollY = 100,
                fromIndex = 0,
                toIndex = 1,
                timestampMs = 1_000L
            )
        )

        // Reject own package
        assertFalse(
            filter.shouldTrigger(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                eventPackage = "com.eteroverseer.screentranslator",
                scrollX = 0,
                scrollY = 100,
                fromIndex = 0,
                toIndex = 1,
                timestampMs = 1_100L
            )
        )

        // Reject SystemUI
        assertFalse(
            filter.shouldTrigger(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                eventPackage = "com.android.systemui",
                scrollX = 0,
                scrollY = 100,
                fromIndex = 0,
                toIndex = 1,
                timestampMs = 1_200L
            )
        )

        // Accept genuine external manga reader scroll
        assertTrue(
            filter.shouldTrigger(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                eventPackage = "com.manga.reader",
                scrollX = 0,
                scrollY = 480,
                fromIndex = 0,
                toIndex = 5,
                timestampMs = 2_000L
            )
        )
    }

    @Test
    fun `filter deduplicates identical scroll coordinates inside duplicate window`() {
        val filter = ScrollEventFilter(
            ownPackage = "com.eteroverseer.screentranslator",
            duplicateWindowMs = 500L
        )

        assertTrue(
            filter.shouldTrigger(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                eventPackage = "com.manga.reader",
                scrollX = 0,
                scrollY = 300,
                fromIndex = 1,
                toIndex = 4,
                timestampMs = 1_000L
            )
        )

        // Identical coordinates 100ms later -> suppressed
        assertFalse(
            filter.shouldTrigger(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                eventPackage = "com.manga.reader",
                scrollX = 0,
                scrollY = 300,
                fromIndex = 1,
                toIndex = 4,
                timestampMs = 1_100L
            )
        )

        // New scroll position immediately -> allowed
        assertTrue(
            filter.shouldTrigger(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                eventPackage = "com.manga.reader",
                scrollX = 0,
                scrollY = 620,
                fromIndex = 2,
                toIndex = 5,
                timestampMs = 1_150L
            )
        )

        // After reset -> same coordinates allowed again
        filter.reset()
        assertTrue(
            filter.shouldTrigger(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                eventPackage = "com.manga.reader",
                scrollX = 0,
                scrollY = 620,
                fromIndex = 2,
                toIndex = 5,
                timestampMs = 1_200L
            )
        )
    }

    @Test
    fun `scheduler ignores scroll activity while paused or stopped`() = runTest {
        var triggerCount = 0
        val scheduler = AutoCaptureScheduler(
            scope = this,
            clockMillis = { 50_000L },
            onTriggerCapture = { triggerCount++ }
        ).apply {
            isEnabled = false
            delayMillis = 1200L
            cooldownMillis = 1000L
        }

        scheduler.onScrollActivity()
        advanceTimeBy(2000L)
        runCurrent()
        assertEquals(0, triggerCount)

        // Pause mid-debounce cancels trigger
        scheduler.isEnabled = true
        scheduler.onScrollActivity()
        advanceTimeBy(600L)
        scheduler.isEnabled = false
        scheduler.cancelPending()
        advanceTimeBy(1500L)
        runCurrent()
        assertEquals(0, triggerCount)
    }
}
