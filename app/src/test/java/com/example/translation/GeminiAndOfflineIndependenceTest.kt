package com.example.translation

import com.example.data.repository.DiagnosticsRepositoryImpl
import com.example.domain.model.HybridPriority
import com.example.domain.model.LanguageOption
import com.example.domain.model.ReadingDirection
import com.example.domain.model.TranslationFailureReason
import com.example.domain.model.TranslationMode
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAndOfflineIndependenceTest {

    @Test
    fun `GeminiTranslationEngine keeps configured model and fails gracefully when FirebaseApp is unavailable`() = runTest {
        assertEquals("gemini-3.8-flash", GeminiTranslationEngine.MODEL_NAME)

        val diagnostics = DiagnosticsRepositoryImpl()
        val engine = GeminiTranslationEngine(
            diagnosticsRepository = diagnostics,
            firebaseReadyProvider = { false }
        )

        val result = engine.translate(
            TranslationRequest(
                sourceText = "先輩、行くよ！",
                sourceLanguage = LanguageOption.JAPANESE,
                targetLanguage = LanguageOption.THAI,
                readingDirection = ReadingDirection.RTL_MANGA
            )
        )

        assertTrue(result is TranslationResult.Failure)
        val failure = result as TranslationResult.Failure
        assertEquals(TranslationFailureReason.APP_CHECK_OR_SERVICE_UNAVAILABLE, failure.reason)
        assertTrue(failure.userMessageThai.contains("Firebase"))
        assertTrue(diagnostics.errorsFlow.first().isNotEmpty())
    }

    @Test
    fun `Offline mode works completely without Firebase configuration`() = runTest {
        val diagnostics = DiagnosticsRepositoryImpl()
        val geminiUnavailable = GeminiTranslationEngine(
            diagnosticsRepository = diagnostics,
            firebaseReadyProvider = { false }
        )
        val offlineEngine = object : TranslationEngine {
            override val providerType = TranslationProviderType.OFFLINE_MLKIT
            override suspend fun translate(request: TranslationRequest): TranslationResult {
                return TranslationResult.Success(
                    translatedText = "รุ่นพี่ ไปกันเถอะ!",
                    provider = TranslationProviderType.OFFLINE_MLKIT,
                    detectedLanguage = "ja"
                )
            }
        }

        val router = TranslationRouter(offlineEngine, geminiUnavailable)
        val request = TranslationRequest(
            sourceText = "先輩、行くよ！",
            sourceLanguage = LanguageOption.JAPANESE,
            targetLanguage = LanguageOption.THAI,
            readingDirection = ReadingDirection.RTL_MANGA
        )

        val offlineResult = router.translate(
            request = request,
            mode = TranslationMode.OFFLINE,
            hybridPriority = HybridPriority.OFFLINE_FIRST,
            aiEnabled = false
        )
        assertTrue(offlineResult is TranslationResult.Success)
        assertEquals(
            TranslationProviderType.OFFLINE_MLKIT,
            (offlineResult as TranslationResult.Success).provider
        )

        // Even in HYBRID / GEMINI_PREFERRED, unconfigured Firebase falls back cleanly to Offline
        val fallbackResult = router.translate(
            request = request,
            mode = TranslationMode.HYBRID,
            hybridPriority = HybridPriority.GEMINI_PREFERRED,
            aiEnabled = true
        )
        assertTrue(fallbackResult is TranslationResult.Success)
        assertEquals(
            TranslationProviderType.OFFLINE_MLKIT,
            (fallbackResult as TranslationResult.Success).provider
        )
    }

    @Test
    fun `OfflineTranslationEngine initializes local model catalog without Firebase`() {
        val engine = OfflineTranslationEngine(DiagnosticsRepositoryImpl())
        val codes = engine.modelsFlow.value.map { it.languageCode }
        assertEquals(listOf("ja", "ko", "en", "th"), codes)
    }
}
