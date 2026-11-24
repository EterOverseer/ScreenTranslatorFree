package com.example.capture

import com.example.core.image.BitmapUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoCaptureSchedulerTest {

    @Test
    fun `scroll debounce waits for configurable delay and respects cooldown`() = runTest {
        var fakeClock = 10_000L
        var triggerCount = 0
        val scheduler = AutoCaptureScheduler(
            scope = this,
            clockMillis = { fakeClock },
            onTriggerCapture = { triggerCount++ }
        ).apply {
            isEnabled = true
            delayMillis = 1500L
            cooldownMillis = 3000L
        }

        // Rapid scrolling resets debounce timer
        scheduler.onScrollActivity()
        advanceTimeBy(1000L)
        fakeClock += 1000L
        scheduler.onScrollActivity()
        advanceTimeBy(1000L)
        fakeClock += 1000L
        assertEquals(0, triggerCount)

        // Finish remaining 500ms after last scroll
        advanceTimeBy(550L)
        fakeClock += 550L
        runCurrent()
        assertEquals(1, triggerCount)

        // Immediate next scroll stops before cooldown expires -> should not trigger
        scheduler.onScrollActivity()
        advanceTimeBy(1550L)
        fakeClock += 1550L
        runCurrent()
        assertEquals(1, triggerCount)

        // After cooldown passes -> triggers again
        fakeClock += 3000L
        scheduler.onScrollActivity()
        advanceTimeBy(1550L)
        runCurrent()
        assertEquals(2, triggerCount)
    }

    @Test
    fun `cancelPending stops queued capture when paused or stopped`() = runTest {
        var triggerCount = 0
        val scheduler = AutoCaptureScheduler(
            scope = this,
            clockMillis = { 100_000L },
            onTriggerCapture = { triggerCount++ }
        ).apply {
            isEnabled = true
            delayMillis = 2000L
            cooldownMillis = 1000L
        }

        scheduler.onScrollActivity()
        advanceTimeBy(1000L)
        scheduler.cancelPending()
        advanceTimeBy(2000L)
        runCurrent()

        assertEquals(0, triggerCount)
    }

    @Test
    fun `screen hash deduplication detects identical vs changed frames`() {
        val hashA = 0x0F0F0F0F0F0F0F0FL
        val hashAlmostSame = hashA xor 0x3L // 2 bits different
        val hashDifferent = hashA xor 0xFF00L

        assertTrue(BitmapUtils.isScreenUnchanged(hashA, hashA))
        assertTrue(BitmapUtils.isScreenUnchanged(hashA, hashAlmostSame))
        assertFalse(BitmapUtils.isScreenUnchanged(hashA, hashDifferent))
        assertFalse(BitmapUtils.isScreenUnchanged(null, hashA))
    }
}
