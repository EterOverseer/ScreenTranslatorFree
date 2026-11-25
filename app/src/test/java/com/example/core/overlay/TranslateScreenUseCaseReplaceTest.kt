package com.example.core.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.DiagnosticsRepositoryImpl
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureStatus
import com.example.domain.model.LanguageOption
import com.example.domain.model.OcrBlock
import com.example.domain.model.OcrBoundingBox
import com.example.domain.model.OcrResult
import com.example.domain.model.RegionTranslationRequest
import com.example.domain.model.RegionTranslationResult
import com.example.domain.model.TranslationBackgroundTreatment
import com.example.domain.model.TranslationDisplayMode
import com.example.domain.model.TranslationHistoryItem
import com.example.domain.model.TranslationMode
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import com.example.domain.repository.HistoryRepository
import com.example.domain.usecase.TranslateScreenUseCase
import com.example.manga.MangaContextProcessor
import com.example.ocr.OcrEngine
import com.example.translation.TranslationEngine
import com.example.translation.TranslationRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslateScreenUseCaseReplaceTest {

    private class RecordingGuard : OverlayCaptureGuard {
        val events = mutableListOf<String>()
        override suspend fun suspendForCapture() {
            events += "suspend"
        }
        override suspend fun restoreAfterCapture() {
            events += "restore"
        }
    }

    private class FakeHistoryRepo : HistoryRepository {
        val saved = MutableStateFlow<List<TranslationHistoryItem>>(emptyList())
        override fun observeHistory(query: String, languageFilter: String?): Flow<List<TranslationHistoryItem>> = saved
        override suspend fun saveRecord(item: TranslationHistoryItem): Long {
            saved.value = saved.value + item
            return saved.value.size.toLong()
        }
        override suspend fun deleteRecord(id: Long) {}
        override suspend fun clearAll() { saved.value = emptyList() }
    }

    private fun makeTwoBubbleBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        // Dark bubble on the bottom region
        for (y in 320 until 520) {
            for (x in 40 until 240) {
                bmp.setPixel(x, y, Color.rgb(24, 28, 36))
            }
        }
        return bmp
    }

    @Test
    fun `overlay guard suspends before capture and always restores even when capture fails`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val guard = RecordingGuard()
        val engine = object : TranslationEngine {
            override val providerType = TranslationProviderType.OFFLINE_MLKIT
            override suspend fun translate(request: TranslationRequest) =
                TranslationResult.Success("ok", providerType, "ja")
        }
        val useCase = TranslateScreenUseCase(
            appContext = context,
            scope = scope,
            ocrEngine = object : OcrEngine {
                override suspend fun recognize(bitmap: Bitmap, preferredSource: LanguageOption) =
                    Result.failure<OcrResult>(IllegalStateException("unused"))
                override fun detectScriptFromText(text: String) = "ja"
                override fun close() {}
            },
            mangaContextProcessor = MangaContextProcessor(),
            translationRouter = TranslationRouter(engine, engine),
            historyRepository = FakeHistoryRepo(),
            diagnosticsRepository = DiagnosticsRepositoryImpl(),
            overlayCaptureGuard = guard
        )

        useCase.processCapturedBitmap(
            bitmapProvider = {
                guard.events += "capture_fail"
                Result.failure(IllegalStateException("MediaProjection busy"))
            },
            settings = AppSettings(),
            forceProcess = true
        )
        scope.advanceUntilIdle()

        assertEquals(listOf("suspend", "capture_fail", "restore"), guard.events)
    }

    @Test
    fun `replace mode estimates bubble colors before recycling and saves history once per screen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val guard = RecordingGuard()
        val history = FakeHistoryRepo()

        val fakeOcr = object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap, preferredSource: LanguageOption): Result<OcrResult> {
                return Result.success(
                    OcrResult(
                        blocks = listOf(
                            OcrBlock("大丈夫？", listOf("大丈夫？"), OcrBoundingBox(220, 50, 360, 140), "ja"),
                            OcrBlock("行くぞ！", listOf("行くぞ！"), OcrBoundingBox(60, 360, 210, 460), "ja")
                        ),
                        rawText = "大丈夫？\n行くぞ！",
                        detectedLanguage = "ja",
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height
                    )
                )
            }
            override fun detectScriptFromText(text: String) = "ja"
            override fun close() {}
        }

        val fakeEngine = object : TranslationEngine {
            override val providerType = TranslationProviderType.GEMINI_AI
            override suspend fun translate(request: TranslationRequest) =
                TranslationResult.Success("Unused", providerType, "ja")
            override suspend fun translateRegions(request: RegionTranslationRequest): RegionTranslationResult {
                return RegionTranslationResult.Success(
                    translationsById = mapOf(
                        "region_2" to "ลุยกันเลย!",
                        "region_1" to "ไม่เป็นไรนะ?"
                    ),
                    provider = providerType,
                    detectedLanguage = "ja"
                )
            }
        }

        val useCase = TranslateScreenUseCase(
            appContext = context,
            scope = scope,
            ocrEngine = fakeOcr,
            mangaContextProcessor = MangaContextProcessor(),
            translationRouter = TranslationRouter(fakeEngine, fakeEngine),
            historyRepository = history,
            diagnosticsRepository = DiagnosticsRepositoryImpl(),
            overlayCaptureGuard = guard
        )

        val settings = AppSettings(
            translationDisplayMode = TranslationDisplayMode.REPLACE,
            translationBackgroundTreatment = TranslationBackgroundTreatment.BUBBLE,
            translationMode = TranslationMode.GEMINI_AI,
            aiEnabled = true
        )

        useCase.processCapturedBitmap(
            bitmapProvider = {
                guard.events += "capture_ok"
                Result.success(makeTwoBubbleBitmap())
            },
            settings = settings,
            forceProcess = true
        )
        scope.advanceUntilIdle()

        assertEquals(listOf("suspend", "capture_ok", "restore"), guard.events)
        assertEquals(1, history.saved.value.size)

        val regions = useCase.state.value.translatedRegions
        assertEquals(2, regions.size)
        assertEquals("region_1", regions[0].id)
        assertEquals("ไม่เป็นไรนะ?", regions[0].translatedText)
        assertEquals("region_2", regions[1].id)
        assertEquals("ลุยกันเลย!", regions[1].translatedText)

        // First bubble (white top-right) vs second bubble (dark bottom-left)
        assertTrue(Color.red(regions[0].estimatedBackgroundArgb) > 220)
        assertTrue(Color.red(regions[1].estimatedBackgroundArgb) < 60)
        assertEquals("ไม่เป็นไรนะ?\n\nลุยกันเลย!", useCase.state.value.lastTranslatedText)
    }

    @Test
    fun `newer capture supersedes slow older translation so stale regions never overwrite`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))

        val fakeOcr = object : OcrEngine {
            private var count = 0
            override suspend fun recognize(bitmap: Bitmap, preferredSource: LanguageOption): Result<OcrResult> {
                count++
                val text = if (count == 1) "古いページ" else "新しいページ"
                return Result.success(
                    OcrResult(
                        blocks = listOf(OcrBlock(text, listOf(text), OcrBoundingBox(40, 40, 220, 120), "ja")),
                        rawText = text,
                        detectedLanguage = "ja",
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height
                    )
                )
            }
            override fun detectScriptFromText(text: String) = "ja"
            override fun close() {}
        }

        val timedEngine = object : TranslationEngine {
            override val providerType = TranslationProviderType.OFFLINE_MLKIT
            override suspend fun translate(request: TranslationRequest) =
                TranslationResult.Success("ok", providerType, "ja")
            override suspend fun translateRegions(request: RegionTranslationRequest): RegionTranslationResult {
                val src = request.regions.first().text
                if (src == "古いページ") {
                    delay(4000L)
                    return RegionTranslationResult.Success(mapOf("region_1" to "หน้าเก่า"), providerType, "ja")
                }
                delay(50L)
                return RegionTranslationResult.Success(mapOf("region_1" to "หน้าใหม่"), providerType, "ja")
            }
        }

        val useCase = TranslateScreenUseCase(
            appContext = context,
            scope = scope,
            ocrEngine = fakeOcr,
            mangaContextProcessor = MangaContextProcessor(),
            translationRouter = TranslationRouter(timedEngine, timedEngine),
            historyRepository = FakeHistoryRepo(),
            diagnosticsRepository = DiagnosticsRepositoryImpl()
        )

        val settings = AppSettings(translationMode = TranslationMode.OFFLINE, aiEnabled = false)
        useCase.processCapturedBitmap({ Result.success(makeTwoBubbleBitmap()) }, settings, forceProcess = true)
        // Advance just enough for capture + OCR of page 1 to release captureInProgress while translation delays
        scope.advanceTimeBy(50L)

        // Start newer capture while page 1 translation is still pending
        useCase.processCapturedBitmap({ Result.success(makeTwoBubbleBitmap()) }, settings, forceProcess = true)
        scope.advanceUntilIdle()

        assertEquals(1, useCase.state.value.translatedRegions.size)
        assertEquals("หน้าใหม่", useCase.state.value.translatedRegions.first().translatedText)

        // Pausing or stopping clears translated patches immediately
        useCase.updateCaptureStatus(CaptureStatus.PAUSED)
        assertTrue(useCase.state.value.translatedRegions.isEmpty())
    }
}
