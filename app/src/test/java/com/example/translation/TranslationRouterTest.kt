package com.example.translation

import com.example.domain.model.HybridPriority
import com.example.domain.model.LanguageOption
import com.example.domain.model.TranslationFailureReason
import com.example.domain.model.TranslationMode
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRouterTest {

    private class FakeEngine(
        override val providerType: TranslationProviderType,
        private val responder: suspend (TranslationRequest) -> TranslationResult
    ) : TranslationEngine {
        var callCount = 0
        override suspend fun translate(request: TranslationRequest): TranslationResult {
            callCount++
            return responder(request)
        }
    }

    private fun sampleRequest(text: String = "大丈夫？") = TranslationRequest(
        sourceText = text,
        sourceLanguage = LanguageOption.JAPANESE,
        targetLanguage = LanguageOption.THAI
    )

    @Test
    fun `offline mode uses offline engine only and never calls gemini`() = runTest {
        val offline = FakeEngine(TranslationProviderType.OFFLINE_MLKIT) {
            TranslationResult.Success("ไม่เป็นไรนะ?", TranslationProviderType.OFFLINE_MLKIT, "ja")
        }
        val gemini = FakeEngine(TranslationProviderType.GEMINI_AI) {
            TranslationResult.Success("จาก Gemini", TranslationProviderType.GEMINI_AI, "ja")
        }
        val router = TranslationRouter(offline, gemini)

        val result = router.translate(
            request = sampleRequest(),
            mode = TranslationMode.OFFLINE,
            hybridPriority = HybridPriority.OFFLINE_FIRST,
            aiEnabled = true
        )

        assertTrue(result is TranslationResult.Success)
        assertEquals("ไม่เป็นไรนะ?", (result as TranslationResult.Success).translatedText)
        assertEquals(1, offline.callCount)
        assertEquals(0, gemini.callCount)
    }

    @Test
    fun `gemini mode returns AI_DISABLED when aiEnabled is false`() = runTest {
        val offline = FakeEngine(TranslationProviderType.OFFLINE_MLKIT) {
            TranslationResult.Success("ไม่เป็นไร", TranslationProviderType.OFFLINE_MLKIT, "ja")
        }
        val gemini = FakeEngine(TranslationProviderType.GEMINI_AI) {
            TranslationResult.Success("Gemini", TranslationProviderType.GEMINI_AI, "ja")
        }
        val router = TranslationRouter(offline, gemini)

        val result = router.translate(
            request = sampleRequest(),
            mode = TranslationMode.GEMINI_AI,
            hybridPriority = HybridPriority.OFFLINE_FIRST,
            aiEnabled = false
        )

        assertTrue(result is TranslationResult.Failure)
        assertEquals(TranslationFailureReason.AI_DISABLED, (result as TranslationResult.Failure).reason)
        assertEquals(0, gemini.callCount)
    }

    @Test
    fun `offline failure propagates transparently when ai is disabled`() = runTest {
        val offline = FakeEngine(TranslationProviderType.OFFLINE_MLKIT) {
            TranslationResult.Failure(
                TranslationFailureReason.OFFLINE_MODEL_NOT_DOWNLOADED,
                "ยังไม่ได้ดาวน์โหลดโมเดล",
                "Missing JA"
            )
        }
        val gemini = FakeEngine(TranslationProviderType.GEMINI_AI) {
            TranslationResult.Success("ควรไม่ถูกเรียก", TranslationProviderType.GEMINI_AI, "ja")
        }
        val router = TranslationRouter(offline, gemini)

        val result = router.translate(
            request = sampleRequest(),
            mode = TranslationMode.HYBRID,
            hybridPriority = HybridPriority.OFFLINE_FIRST,
            aiEnabled = false
        )

        assertTrue(result is TranslationResult.Failure)
        assertEquals(TranslationFailureReason.OFFLINE_MODEL_NOT_DOWNLOADED, (result as TranslationResult.Failure).reason)
        assertEquals(0, gemini.callCount)
    }

    @Test
    fun `hybrid offline-first falls back once to gemini on offline failure`() = runTest {
        val offline = FakeEngine(TranslationProviderType.OFFLINE_MLKIT) {
            TranslationResult.Failure(
                TranslationFailureReason.OFFLINE_MODEL_NOT_DOWNLOADED,
                "ไม่พบโมเดล",
                "Missing"
            )
        }
        val gemini = FakeEngine(TranslationProviderType.GEMINI_AI) {
            TranslationResult.Success("เป็นอะไรมั้ย?", TranslationProviderType.GEMINI_AI, "ja")
        }
        val router = TranslationRouter(offline, gemini)

        val result = router.translate(
            request = sampleRequest(),
            mode = TranslationMode.HYBRID,
            hybridPriority = HybridPriority.OFFLINE_FIRST,
            aiEnabled = true
        )

        assertTrue(result is TranslationResult.Success)
        assertEquals(TranslationProviderType.GEMINI_AI, (result as TranslationResult.Success).provider)
        assertEquals(1, offline.callCount)
        assertEquals(1, gemini.callCount)
    }

    @Test
    fun `hybrid gemini-preferred falls back once to offline when gemini fails`() = runTest {
        val offline = FakeEngine(TranslationProviderType.OFFLINE_MLKIT) {
            TranslationResult.Success("เป็นอะไรมั้ย (ออฟไลน์)", TranslationProviderType.OFFLINE_MLKIT, "ja")
        }
        val gemini = FakeEngine(TranslationProviderType.GEMINI_AI) {
            TranslationResult.Failure(
                TranslationFailureReason.NETWORK_UNAVAILABLE,
                "ไม่มีอินเทอร์เน็ต",
                "IOException"
            )
        }
        val router = TranslationRouter(offline, gemini)

        val result = router.translate(
            request = sampleRequest(),
            mode = TranslationMode.HYBRID,
            hybridPriority = HybridPriority.GEMINI_PREFERRED,
            aiEnabled = true
        )

        assertTrue(result is TranslationResult.Success)
        assertEquals(TranslationProviderType.OFFLINE_MLKIT, (result as TranslationResult.Success).provider)
        assertEquals(1, gemini.callCount)
        assertEquals(1, offline.callCount)
    }

    @Test
    fun `cache returns stored result without calling engines again and bounds capacity`() = runTest {
        val offline = FakeEngine(TranslationProviderType.OFFLINE_MLKIT) { req ->
            TranslationResult.Success("แปล: ${req.sourceText}", TranslationProviderType.OFFLINE_MLKIT, "ja")
        }
        val gemini = FakeEngine(TranslationProviderType.GEMINI_AI) {
            TranslationResult.Success("ไม่เรียก", TranslationProviderType.GEMINI_AI, "ja")
        }
        val router = TranslationRouter(offline, gemini, maxCacheEntries = 2)

        val first = router.translate(sampleRequest("おはよう"), TranslationMode.OFFLINE, HybridPriority.OFFLINE_FIRST, false)
        val second = router.translate(sampleRequest("おはよう"), TranslationMode.OFFLINE, HybridPriority.OFFLINE_FIRST, false)
        assertTrue(first is TranslationResult.Success)
        assertTrue((second as TranslationResult.Success).fromCache)
        assertEquals(1, offline.callCount)

        // Evict oldest when exceeding bounded capacity
        router.translate(sampleRequest("こんにちは"), TranslationMode.OFFLINE, HybridPriority.OFFLINE_FIRST, false)
        router.translate(sampleRequest("こんばんは"), TranslationMode.OFFLINE, HybridPriority.OFFLINE_FIRST, false)
        val afterEviction = router.translate(sampleRequest("おはよう"), TranslationMode.OFFLINE, HybridPriority.OFFLINE_FIRST, false)
        assertTrue(!(afterEviction as TranslationResult.Success).fromCache)
        assertEquals(4, offline.callCount)
    }
}
