package com.example.core.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.flow.StateFlow

/**
 * Full Edition bridge connecting to AutoScrollAccessibilityService.
 */
object AutoScrollBridge {
    val isConnected: StateFlow<Boolean>
        get() = AutoScrollAccessibilityService.isConnected

    val isSupported: Boolean = true

    fun isServiceEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, AutoScrollAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next())
            if (componentName == expectedComponent) {
                return true
            }
        }
        return false
    }

    fun createSettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
