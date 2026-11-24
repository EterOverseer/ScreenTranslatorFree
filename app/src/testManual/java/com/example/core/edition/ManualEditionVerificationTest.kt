package com.example.core.edition

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.core.service.AutoScrollBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManualEditionVerificationTest {

    @Test
    fun `manual edition has autoscroll disabled and declares no accessibility service in merged manifest`() {
        assertEquals("manual", BuildConfig.FLAVOR)
        assertFalse(BuildConfig.AUTO_SCROLL_ENABLED)
        assertFalse(EditionConfig.isAutoScrollSupported)
        assertFalse(AutoScrollBridge.isSupported)
        assertNull(AutoScrollBridge.createSettingsIntent())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val accessibilityServices = context.packageManager.queryIntentServices(
            Intent("android.accessibilityservice.AccessibilityService").setPackage(context.packageName),
            PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
        )
        assertTrue(
            "Manual edition must declare zero AccessibilityService components, found: $accessibilityServices",
            accessibilityServices.isEmpty()
        )
    }
}
