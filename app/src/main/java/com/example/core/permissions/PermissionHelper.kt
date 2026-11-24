package com.example.core.permissions

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.example.core.service.AutoScrollAccessibilityService

data class PermissionStatus(
    val hasOverlayPermission: Boolean = false,
    val hasAccessibilityPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isMediaProjectionActive: Boolean = false
)

object PermissionHelper {

    fun checkStatus(context: Context, isMediaProjectionActive: Boolean): PermissionStatus {
        return PermissionStatus(
            hasOverlayPermission = hasOverlayPermission(context),
            hasAccessibilityPermission = isAccessibilityServiceEnabled(context),
            hasNotificationPermission = hasNotificationPermission(context),
            isMediaProjectionActive = isMediaProjectionActive
        )
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
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

    fun createOverlaySettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
