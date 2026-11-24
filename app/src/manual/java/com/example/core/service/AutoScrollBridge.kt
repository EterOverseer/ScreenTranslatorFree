package com.example.core.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manual Edition bridge: Accessibility Service does not exist in this flavor,
 * so Auto-scroll detection and Accessibility settings launch are permanently disabled.
 */
object AutoScrollBridge {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    val isSupported: Boolean = false

    fun isServiceEnabled(context: Context): Boolean = false

    fun createSettingsIntent(): Intent? = null
}
