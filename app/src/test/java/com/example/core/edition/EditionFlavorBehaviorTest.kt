package com.example.core.edition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.core.permissions.PermissionHelper
import com.example.core.permissions.PermissionStatus
import com.example.data.repository.DiagnosticsRepositoryImpl
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureMode
import com.example.domain.model.LanguageOption
import com.example.domain.model.OcrBlock
import com.example.domain.model.OcrBoundingBox
import com.example.domain.model.OcrResult
import com.example.domain.model.TranslationHistoryItem
import com.example.domain.model.TranslationMode
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import com.example.domain.repository.HistoryRepository
import com.example.domain.usecase.TranslateScreenUseCase
import com.example.feature.home.HomeScreen
import com.example.feature.home.HomeUiState
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.SettingsUiState
import com.example.feature.translator.TranslatorScreen
import com.example.feature.translator.TranslatorUiState
import com.example.manga.MangaContextProcessor
import com.example.ocr.OcrEngine
import com.example.translation.TranslationEngine
import com.example.translation.TranslationRouter
import com.example.ui.theme.EterScreenTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditionFlavorBehaviorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `flavor flags and accessibility intent match active edition`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (BuildConfig.FLAVOR == "manual") {
            assertFalse(EditionConfig.isAutoScrollSupported)
            assertFalse(PermissionHelper.isAccessibilityServiceEnabled(context))
            assertNull(PermissionHelper.createAccessibilitySettingsIntent())
            assertEquals(CaptureMode.MANUAL, EditionConfig.normalizeCaptureMode(CaptureMode.AUTO))
            assertEquals(CaptureMode.MANUAL, EditionConfig.normalizeCaptureMode(CaptureMode.BOTH))
        } else {
            assertEquals("full", BuildConfig.FLAVOR)
            assertTrue(EditionConfig.isAutoScrollSupported)
            val intent = PermissionHelper.createAccessibilitySettingsIntent()
            assertNotNull(intent)
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent?.action)
            assertEquals(CaptureMode.AUTO, EditionConfig.normalizeCaptureMode(CaptureMode.AUTO))
            assertEquals(CaptureMode.BOTH, EditionConfig.normalizeCaptureMode(CaptureMode.BOTH))
        }
    }

    @Test
    fun `manual capture translates without Accessibility permission`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

        val fakeHistory = object : HistoryRepository {
            private val items = MutableStateFlow<List<TranslationHistoryItem>>(emptyList())
            override fun observeHistory(query: String, languageFilter: String?): Flow<List<TranslationHistoryItem>> = items
            override suspend fun saveRecord(item: TranslationHistoryItem): Long = 1L
            override suspend fun deleteRecord(id: Long) {}
            override suspend fun clearAll() {}
        }

        val useCase = TranslateScreenUseCase(
            appContext = context,
            scope = scope,
            ocrEngine = object : OcrEngine {
                override suspend fun recognize(bitmap: Bitmap, preferredSource: LanguageOption) =
                    Result.success(
                        OcrResult(
                            blocks = listOf(
                                OcrBlock(
                                    text = "ありがとう",
                                    lines = listOf("ありがとう"),
                                    box = OcrBoundingBox(10, 10, 90, 40),
                                    detectedLanguage = "ja"
                                )
                            ),
                            rawText = "ありがとう",
                            detectedLanguage = "ja",
                            imageWidth = 120,
                            imageHeight = 180
                        )
                    )
                override fun detectScriptFromText(text: String) = "ja"
                override fun close() {}
            },
            mangaContextProcessor = MangaContextProcessor(),
            translationRouter = TranslationRouter(
                offlineEngine = object : TranslationEngine {
                    override val providerType = TranslationProviderType.OFFLINE_MLKIT
                    override suspend fun translate(request: TranslationRequest) =
                        TranslationResult.Success("ขอบคุณนะ", providerType, "ja")
                },
                geminiEngine = object : TranslationEngine {
                    override val providerType = TranslationProviderType.GEMINI_AI
                    override suspend fun translate(request: TranslationRequest) =
                        TranslationResult.Success("ขอบคุณนะ AI", providerType, "ja")
                }
            ),
            historyRepository = fakeHistory,
            diagnosticsRepository = DiagnosticsRepositoryImpl()
        )

        useCase.updateAccessibilityStatus(false)
        useCase.processCapturedBitmap(
            bitmapProvider = { Result.success(bitmap) },
            settings = AppSettings(
                captureMode = CaptureMode.MANUAL,
                translationMode = TranslationMode.OFFLINE,
                aiEnabled = false
            ),
            forceProcess = true
        )
        scope.advanceUntilIdle()

        assertFalse(useCase.state.value.isAutoScrollServiceConnected)
        assertEquals("ขอบคุณนะ", useCase.state.value.lastTranslatedText)
        assertNull(useCase.state.value.lastErrorThai)
    }

    @Test
    fun `home screen hides accessibility button and shows unavailable state in manual mode`() {
        composeTestRule.setContent {
            EterScreenTheme {
                HomeScreen(
                    uiState = HomeUiState(
                        isAutoScrollSupported = false,
                        editionName = "Manual Edition",
                        permissions = PermissionStatus(hasAccessibilityPermission = false)
                    ),
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
        composeTestRule.onNodeWithTag("grant_accessibility_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("manual_autoscroll_unavailable_row")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `home screen shows accessibility button in full mode`() {
        composeTestRule.setContent {
            EterScreenTheme {
                HomeScreen(
                    uiState = HomeUiState(
                        isAutoScrollSupported = true,
                        editionName = "Full Edition",
                        permissions = PermissionStatus(hasAccessibilityPermission = false)
                    ),
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
        composeTestRule.onNodeWithTag("grant_accessibility_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("manual_autoscroll_unavailable_row").assertDoesNotExist()
    }

    @Test
    fun `translator and settings screens show manual autoscroll notice when unsupported`() {
        composeTestRule.setContent {
            EterScreenTheme {
                TranslatorScreen(
                    uiState = TranslatorUiState(isAutoScrollSupported = false),
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
        composeTestRule.onNodeWithTag("manual_autoscroll_unavailable_notice")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `settings screen shows manual autoscroll notice when unsupported`() {
        composeTestRule.setContent {
            EterScreenTheme {
                SettingsScreen(
                    uiState = SettingsUiState(isAutoScrollSupported = false),
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
                    onResetSettings = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_manual_autoscroll_unavailable")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
