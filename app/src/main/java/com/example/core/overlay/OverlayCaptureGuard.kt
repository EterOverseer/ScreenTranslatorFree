package com.example.core.overlay

interface OverlayCaptureGuard {
    /**
     * Temporarily hides all app overlay windows (replacement layer, floating bubble, panel)
     * before MediaProjection acquires a frame so the app never captures its own Thai text.
     */
    suspend fun suspendForCapture()

    /**
     * Restores overlay visibility in a guaranteed finally path after frame acquisition finishes.
     */
    suspend fun restoreAfterCapture()

    companion object {
        val NO_OP = object : OverlayCaptureGuard {
            override suspend fun suspendForCapture() {}
            override suspend fun restoreAfterCapture() {}
        }
    }
}
