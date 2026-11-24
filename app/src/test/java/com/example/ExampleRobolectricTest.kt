package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name and package id match active flavor`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        if (BuildConfig.FLAVOR == "full") {
            assertEquals("EterScreen Translator Full", appName)
            assertEquals("com.eteroverseer.screentranslator.full", context.packageName)
        } else {
            assertEquals("EterScreen Translator", appName)
            assertEquals("com.eteroverseer.screentranslator", context.packageName)
        }
    }
}
