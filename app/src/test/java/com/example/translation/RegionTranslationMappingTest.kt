package com.example.translation

import com.example.data.repository.DiagnosticsRepositoryImpl
import com.example.domain.model.HybridPriority
import com.example.domain.model.LanguageOption
import com.example.domain.model.MangaRegion
import com.example.domain.model.OcrBoundingBox
import com.example.domain.model.ReadingDirection
import com.example.domain.model.RegionTranslationInput
import com.example.domain.model.RegionTranslationItem
import com.example.domain.model.RegionTranslationRequest
import com.example.domain.model.RegionTranslationResult
import com.example.domain.model.TranslationFailureReason
import com.example.domain.model.TranslationMode
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegionTranslationMappingTest {

    private val expectedRegions = listOf(
        RegionTranslationInput(id = "region_1", text = "大丈夫か？"),
        RegionTranslationInput(id = "region_2", text = "うおおお！", isSfx = true),
        RegionTranslationInput(id = "region_3", text = "急ごう！")
    )

    private fun sampleBatchRequest(
        regions: List<RegionTranslationInput> = expectedRegions
    ) = RegionTranslationRequest(
        regions = regions,
        sourceLanguage = LanguageOption.JAPANESE,
        targetLanguage = LanguageOption.THAI,
        readingDirection = ReadingDirection.RTL_MANGA,
        previousContext = listOf("先輩 => รุ่นพี่")
    )

    private class FakeRegionEngine(
        override val providerType: TranslationProviderType,
        private val singleResponder: suspend (TranslationRequest) -> TranslationResult = {
            TranslationResult.Success("แปล", providerType, "ja")
        },
        private val regionResponder: (suspend (RegionTranslationRequest) -> RegionTranslationResult)? = null
    ) : TranslationEngine {
        var singleCallCount = 0
        var regionCallCount = 0

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            singleCallCount++
            return singleResponder(request)
        }

        override suspend fun translateRegions(request: RegionTranslationRequest): RegionTranslationResult {
            regionCallCount++
            return regionResponder?.invoke(request) ?: super.translateRegions(request)
        }
    }

    @Test
    fun `maps exact region IDs regardless of returned JSON array order`() = runTest {
        var capturedPrompt = ""
        val engine = GeminiTranslationEngine(
            diagnosticsRepository = DiagnosticsRepositoryImpl(),
            customNoteProvider = { "เรียกตัวเอกว่า รุ่นพี่" },
            firebaseReadyProvider = { true },
            rawBatchInvoker = { prompt ->
                capturedPrompt = prompt
                // Intentionally return region_3, region_1, region_2 (out of reading order)
                """
                {
                  "detectedLanguage": "ja",
                  "regions": [
                    { "id": "region_3", "translatedText": "รีบไปกันเถอะ!" },
                    { "id": "region_1", "translatedText": "ไม่เป็นไรใช่ไหม?" },
                    { "id": "region_2", "translatedText": "ว้ากกก!" }
                  ],
                  "toneNote": "โทนตื่นเต้น"
                }
                """.trimIndent()
            }
        )

        val result = engine.translateRegions(sampleBatchRequest())

        assertTrue(result is RegionTranslationResult.Success)
        val success = result as RegionTranslationResult.Success
        assertEquals(listOf("region_1", "region_2", "region_3"), success.translationsById.keys.toList())
        assertEquals("ไม่เป็นไรใช่ไหม?", success.translationsById["region_1"])
        assertEquals("ว้ากกก!", success.translationsById["region_2"])
        assertEquals("รีบไปกันเถอะ!", success.translationsById["region_3"])
        assertTrue(capturedPrompt.contains("Japanese Manga"))
        assertTrue(capturedPrompt.contains("\"isSfx\": true"))
        assertTrue(capturedPrompt.contains("รุ่นพี่"))
    }

    @Test
    fun `rejects batch when any expected region ID is missing`() = runTest {
        val rawJson = """
            {
              "detectedLanguage": "ja",
              "regions": [
                { "id": "region_1", "translatedText": "ไม่เป็นไร" },
                { "id": "region_3", "translatedText": "ไปกัน" }
              ]
            }
        """.trimIndent()

        val result = RegionBatchValidator.parseAndValidateGeminiBatchJson(
            rawJson = rawJson,
            expectedInputs = expectedRegions,
            fallbackLanguage = "ja"
        )

        assertTrue(result is RegionBatchValidationResult.Invalid)
        assertTrue((result as RegionBatchValidationResult.Invalid).technicalDetails.contains("region_2"))
    }

    @Test
    fun `rejects batch when duplicate region IDs are returned`() = runTest {
        val result = RegionBatchValidator.validateItems(
            expectedInputs = expectedRegions,
            returnedItems = listOf(
                RegionTranslationItem("region_1", "เอ"),
                RegionTranslationItem("region_1", "บี"),
                RegionTranslationItem("region_3", "ซี")
            )
        )

        assertTrue(result is RegionBatchValidationResult.Invalid)
        assertTrue((result as RegionBatchValidationResult.Invalid).technicalDetails.contains("Duplicate"))
    }

    @Test
    fun `rejects batch when unknown region ID is returned`() = runTest {
        val result = RegionBatchValidator.validateItems(
            expectedInputs = expectedRegions,
            returnedItems = listOf(
                RegionTranslationItem("region_1", "เอ"),
                RegionTranslationItem("region_2", "บี"),
                RegionTranslationItem("region_99", "ปลอม")
            )
        )

        assertTrue(result is RegionBatchValidationResult.Invalid)
        assertTrue((result as RegionBatchValidationResult.Invalid).technicalDetails.contains("Unknown"))
    }

    @Test
    fun `rejects batch when any region translation is blank`() = runTest {
        val result = RegionBatchValidator.validateItems(
            expectedInputs = expectedRegions,
            returnedItems = listOf(
                RegionTranslationItem("region_1", "โอเค"),
                RegionTranslationItem("region_2", "   "),
                RegionTranslationItem("region_3", "ไปเลย")
            )
        )

        assertTrue(result is RegionBatchValidationResult.Invalid)
        assertTrue((result as RegionBatchValidationResult.Invalid).technicalDetails.contains("Blank"))
    }

    @Test
    fun `rejects malformed JSON batch response`() = runTest {
        val engine = GeminiTranslationEngine(
            diagnosticsRepository = DiagnosticsRepositoryImpl(),
            firebaseReadyProvider = { true },
            rawBatchInvoker = { "{ not valid json" }
        )

        val result = engine.translateRegions(sampleBatchRequest())
        assertTrue(result is RegionTranslationResult.Failure)
    }

    @Test
    fun `partial failure in default translateRegions fails whole batch without guessing`() = runTest {
        val partialOffline = FakeRegionEngine(
            providerType = TranslationProviderType.OFFLINE_MLKIT,
            singleResponder = { req ->
                if (req.sourceText == "うおおお！") {
                    TranslationResult.Failure(
                        TranslationFailureReason.OFFLINE_MODEL_NOT_DOWNLOADED,
                        "ไม่มีโมเดล",
                        "Missing"
                    )
                } else {
                    TranslationResult.Success("โอเค", TranslationProviderType.OFFLINE_MLKIT, "ja")
                }
            }
        )

        val result = partialOffline.translateRegions(sampleBatchRequest())
        assertTrue(result is RegionTranslationResult.Failure)
        assertEquals(
            TranslationFailureReason.OFFLINE_MODEL_NOT_DOWNLOADED,
            (result as RegionTranslationResult.Failure).reason
        )
    }

    @Test
    fun `hybrid mode falls back cleanly between offline and gemini region engines`() = runTest {
        val failingOffline = FakeRegionEngine(
            providerType = TranslationProviderType.OFFLINE_MLKIT,
            regionResponder = {
                RegionTranslationResult.Failure(
                    TranslationFailureReason.OFFLINE_MODEL_NOT_DOWNLOADED,
                    "ยังไม่มีโมเดล",
                    "Missing"
                )
            }
        )
        val workingGemini = FakeRegionEngine(
            providerType = TranslationProviderType.GEMINI_AI,
            regionResponder = { req ->
                RegionTranslationResult.Success(
                    translationsById = req.regions.associate { it.id to "ไทย:${it.id}" },
                    provider = TranslationProviderType.GEMINI_AI,
                    detectedLanguage = "ja"
                )
            }
        )

        val routerOfflineFirst = TranslationRouter(failingOffline, workingGemini)
        val res1 = routerOfflineFirst.translateRegions(
            request = sampleBatchRequest(),
            mode = TranslationMode.HYBRID,
            hybridPriority = HybridPriority.OFFLINE_FIRST,
            aiEnabled = true
        )
        assertTrue(res1 is RegionTranslationResult.Success)
        assertEquals(TranslationProviderType.GEMINI_AI, (res1 as RegionTranslationResult.Success).provider)
        assertEquals(1, failingOffline.regionCallCount)
        assertEquals(1, workingGemini.regionCallCount)

        // Reverse: GEMINI_PREFERRED falls back to offline if Gemini fails
        val workingOffline = FakeRegionEngine(
            providerType = TranslationProviderType.OFFLINE_MLKIT,
            regionResponder = { req ->
                RegionTranslationResult.Success(
                    translationsById = req.regions.associate { it.id to "ออฟไลน์:${it.id}" },
                    provider = TranslationProviderType.OFFLINE_MLKIT,
                    detectedLanguage = "ja"
                )
            }
        )
        val failingGemini = FakeRegionEngine(
            providerType = TranslationProviderType.GEMINI_AI,
            regionResponder = {
                RegionTranslationResult.Failure(
                    TranslationFailureReason.NETWORK_UNAVAILABLE,
                    "ไม่มีเน็ต",
                    "Offline"
                )
            }
        )

        val routerGeminiPref = TranslationRouter(workingOffline, failingGemini)
        val res2 = routerGeminiPref.translateRegions(
            request = sampleBatchRequest(),
            mode = TranslationMode.HYBRID,
            hybridPriority = HybridPriority.GEMINI_PREFERRED,
            aiEnabled = true
        )
        assertTrue(res2 is RegionTranslationResult.Success)
        assertEquals(TranslationProviderType.OFFLINE_MLKIT, (res2 as RegionTranslationResult.Success).provider)
    }

    @Test
    fun `aiDisabled prevents any Gemini region request`() = runTest {
        val offline = FakeRegionEngine(
            providerType = TranslationProviderType.OFFLINE_MLKIT,
            regionResponder = {
                RegionTranslationResult.Failure(
                    TranslationFailureReason.OFFLINE_MODEL_NOT_DOWNLOADED,
                    "ยังไม่มีโมเดล",
                    "Missing"
                )
            }
        )
        val gemini = FakeRegionEngine(
            providerType = TranslationProviderType.GEMINI_AI,
            regionResponder = {
                RegionTranslationResult.Success(
                    translationsById = mapOf("region_1" to "ควรไม่ถูกเรียก"),
                    provider = TranslationProviderType.GEMINI_AI,
                    detectedLanguage = "ja"
                )
            }
        )

        val router = TranslationRouter(offline, gemini)
        val hybridDisabled = router.translateRegions(
            request = sampleBatchRequest(),
            mode = TranslationMode.HYBRID,
            hybridPriority = HybridPriority.GEMINI_PREFERRED,
            aiEnabled = false
        )
        val geminiDisabled = router.translateRegions(
            request = sampleBatchRequest(),
            mode = TranslationMode.GEMINI_AI,
            hybridPriority = HybridPriority.OFFLINE_FIRST,
            aiEnabled = false
        )

        assertTrue(hybridDisabled is RegionTranslationResult.Failure)
        assertTrue(geminiDisabled is RegionTranslationResult.Failure)
        assertEquals(TranslationFailureReason.AI_DISABLED, (geminiDisabled as RegionTranslationResult.Failure).reason)
        assertEquals(0, gemini.regionCallCount)
    }

    @Test
    fun `formatCombinedPanelText preserves reading order and SFX tags`() {
        val regions = listOf(
            MangaRegion("region_1", "行くぞ", false, true, OcrBoundingBox(10, 10, 80, 40)),
            MangaRegion("region_2", "ドン！", true, false, OcrBoundingBox(10, 60, 80, 100))
        )
        val formatted = RegionBatchValidator.formatCombinedPanelText(
            regions = regions,
            translationsById = mapOf("region_2" to "ตึง!", "region_1" to "ลุยกันเลย")
        )
        assertEquals("ลุยกันเลย\n\n[SFX] ตึง!", formatted)
        assertFalse(formatted.startsWith("[SFX]"))
    }
}
