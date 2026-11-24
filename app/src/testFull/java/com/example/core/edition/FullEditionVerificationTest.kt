package com.example.core.edition

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.core.service.AutoScrollAccessibilityService
import com.example.core.service.AutoScrollBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullEditionVerificationTest {

    @Test
    fun `full edition enables autoscroll and declares AutoScrollAccessibilityService in merged manifest`() {
        assertEquals("full", BuildConfig.FLAVOR)
        assertTrue(BuildConfig.AUTO_SCROLL_ENABLED)
        assertTrue(EditionConfig.isAutoScrollSupported)
        assertTrue(AutoScrollBridge.isSupported)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, AutoScrollBridge.createSettingsIntent().action)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val accessibilityServices = context.packageManager.queryIntentServices(
            Intent("android.accessibilityservice.AccessibilityService").setPackage(context.packageName),
            PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
        )
        val match = accessibilityServices.firstOrNull {
            it.serviceInfo.name == AutoScrollAccessibilityService::class.java.name
        }
        assertNotNull("Full edition must declare AutoScrollAccessibilityService", match)
        assertEquals(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            match?.serviceInfo?.permission
        )
    }
}
