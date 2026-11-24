package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.example.core.permissions.PermissionStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.TranslationHistoryItem
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslatorState
import com.example.feature.history.HistoryScreen
import com.example.feature.history.HistoryUiState
import com.example.feature.home.HomeScreen
import com.example.feature.home.HomeUiState
import com.example.feature.translator.TranslatorScreen
import com.example.feature.translator.TranslatorUiState
import com.example.ui.theme.EterScreenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeUiScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `home screen renders Thai branding and start button`() {
        composeTestRule.setContent {
            EterScreenTheme {
                HomeScreen(
                    uiState = HomeUiState(),
                    onRequestStartCapture = {},
                    onStopCapture = {},
                    onAcknowledgeAiConsent = {},
                    onRequestOverlayPermission = {},
                    onRequestAccessibilityPermission = {},
                    onRequestNotificationPermission = {},
                    onNavigateToTranslator = {},
                    onNavigateToHistory = {},
                    onNavigateToSettings = {},
                    onNavigateToPrivacy = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("EterScreen Translator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_translation_button").assertIsDisplayed()
    }

    @Test
    fun `translator screen renders editable OCR field and Thai output`() {
        composeTestRule.setContent {
            EterScreenTheme {
                TranslatorScreen(
                    uiState = TranslatorUiState(
                        settings = AppSettings(),
                        translatorState = TranslatorState(
                            editableSourceText = "行くぞ！",
                            lastTranslatedText = "ลุยกันเลย!",
                            activeProvider = TranslationProviderType.GEMINI_AI
                        ),
                        permissions = PermissionStatus(isMediaProjectionActive = true)
                    ),
                    onRequestStartCapture = {},
                    onManualCapture = {},
                    onPauseCapture = {},
                    onResumeCapture = {},
                    onStopCapture = {},
                    onCancelJob = {},
                    onSelectCaptureMode = {},
                    onSelectReadingDirection = {},
                    onEditSourceText = {},
                    onRetranslateEdited = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manual_capture_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("ลุยกันเลย!")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("editable_ocr_input")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `history screen displays saved translation item`() {
        composeTestRule.setContent {
            EterScreenTheme {
                HistoryScreen(
                    uiState = HistoryUiState(
                        items = listOf(
                            TranslationHistoryItem(
                                id = 1L,
                                sourceText = "괜찮아?",
                                translatedText = "ไม่เป็นไรใช่ไหม?",
                                sourceLanguageCode = "ko",
                                targetLanguageCode = "th",
                                provider = TranslationProviderType.OFFLINE_MLKIT
                            )
                        )
                    ),
                    onSearchChange = {},
                    onFilterChange = {},
                    onDeleteItem = {},
                    onClearAll = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("history_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithText("ไม่เป็นไรใช่ไหม?").assertIsDisplayed()
    }
}
