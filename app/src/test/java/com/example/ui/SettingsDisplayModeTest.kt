package com.example.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.example.core.permissions.PermissionStatus
import com.example.core.security.KeystoreCryptoManager
import com.example.data.preferences.SettingsDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.TranslationBackgroundTreatment
import com.example.domain.model.TranslationDisplayMode
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.SettingsUiState
import com.example.ui.theme.EterScreenTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsDisplayModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `defaults resolve to REPLACE and BUBBLE and reset restores them`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val rawStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tempFolder.newFolder(), "display_mode_test.preferences_pb") }
        )
        try {
            val dataStore = SettingsDataStore(context, KeystoreCryptoManager(), rawStore)

            val initial = dataStore.settingsFlow.first()
            assertEquals(TranslationDisplayMode.REPLACE, initial.translationDisplayMode)
            assertEquals(TranslationBackgroundTreatment.BUBBLE, initial.translationBackgroundTreatment)

            dataStore.update { it.copy(translationDisplayMode = TranslationDisplayMode.PANEL) }
            assertEquals(TranslationDisplayMode.PANEL, dataStore.settingsFlow.first().translationDisplayMode)

            dataStore.update { it.copy(translationDisplayMode = TranslationDisplayMode.REPLACE) }
            assertEquals(TranslationDisplayMode.REPLACE, dataStore.settingsFlow.first().translationDisplayMode)

            dataStore.update { it.copy(translationDisplayMode = TranslationDisplayMode.PANEL) }
            dataStore.reset()
            val afterReset = dataStore.settingsFlow.first()
            assertEquals(TranslationDisplayMode.REPLACE, afterReset.translationDisplayMode)
            assertEquals(TranslationBackgroundTreatment.BUBBLE, afterReset.translationBackgroundTreatment)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `existing legacy stored preferences without display mode keys remain readable`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val rawStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tempFolder.newFolder(), "legacy_prefs_test.preferences_pb") }
        )
        try {
            rawStore.edit { prefs ->
                prefs[stringPreferencesKey("source_language")] = "ko"
                prefs[stringPreferencesKey("translation_mode")] = "OFFLINE"
            }
            val dataStore = SettingsDataStore(context, KeystoreCryptoManager(), rawStore)
            val loaded = dataStore.settingsFlow.first()

            assertEquals("ko", loaded.sourceLanguage.code)
            assertEquals(TranslationDisplayMode.REPLACE, loaded.translationDisplayMode)
            assertEquals(TranslationBackgroundTreatment.BUBBLE, loaded.translationBackgroundTreatment)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `settings UI toggles between Replace and Panel and shows overlay permission warning without accessibility`() {
        composeTestRule.setContent {
            var settings by remember { mutableStateOf(AppSettings()) }
            EterScreenTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        settings = settings,
                        permissions = PermissionStatus(hasOverlayPermission = false)
                    ),
                    onSetSourceLanguage = {},
                    onSetTranslationMode = {},
                    onSetHybridPriority = {},
                    onSetCaptureMode = {},
                    onSetReadingDirection = {},
                    onSetAutoDelayMs = {},
                    onSetCooldownMs = {},
                    onSetAiEnabled = {},
                    onSetCustomNote = {},
                    onSetOverlayEnabled = {},
                    onSetBubbleSize = {},
                    onSetBubbleOpacity = {},
                    onResetBubblePosition = {},
                    onSetSaveHistoryImages = {},
                    onDownloadModel = {},
                    onDeleteModel = {},
                    onClearCache = {},
                    onResetSettings = {},
                    onSetTranslationDisplayMode = { settings = settings.copy(translationDisplayMode = it) },
                    onSetTranslationBackgroundTreatment = {
                        settings = settings.copy(translationBackgroundTreatment = it)
                    },
                    onRequestOverlayPermission = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        // Default REPLACE shows Background Treatment + Bubble + Overlay permission warning
        composeTestRule.onNodeWithTag("display_mode_replace_chip").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("background_treatment_section").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("background_treatment_bubble_chip").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_overlay_permission_warning").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_grant_overlay_button").performScrollTo().assertIsDisplayed()

        // Switch to Panel -> hides Background Treatment section
        composeTestRule.onNodeWithTag("display_mode_panel_chip").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, composeTestRule.onAllNodesWithTag("background_treatment_section").fetchSemanticsNodes().size)

        // Switch back to Replace -> restores Background Treatment section
        composeTestRule.onNodeWithTag("display_mode_replace_chip").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("background_treatment_section").performScrollTo().assertIsDisplayed()
    }
}
