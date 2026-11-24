package com.example.capture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AutoCaptureScheduler(
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val onTriggerCapture: suspend () -> Unit
) {
    private var debounceJob: Job? = null
    private var lastAutoTriggerMillis: Long = 0L

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var delayMillis: Long = 1500L

    @Volatile
    var cooldownMillis: Long = 2500L

    fun onScrollActivity() {
        if (!isEnabled) return
        debounceJob?.cancel()
        val waitMs = delayMillis.coerceIn(1000L, 5000L)
        debounceJob = scope.launch {
            delay(waitMs)
            val now = clockMillis()
            if (isEnabled && now - lastAutoTriggerMillis >= cooldownMillis) {
                lastAutoTriggerMillis = now
                onTriggerCapture()
            }
        }
    }

    fun cancelPending() {
        debounceJob?.cancel()
        debounceJob = null
    }

    fun resetCooldown() {
        lastAutoTriggerMillis = 0L
    }
}
