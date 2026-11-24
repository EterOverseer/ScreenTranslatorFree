package com.example.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.core.image.BitmapUtils
import com.example.data.repository.DiagnosticsRepositoryImpl
import com.example.domain.model.AppSettings
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoCaptureAndUseCaseTest {

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

    private fun makePatternBitmap(seedColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        for (x in 10 until 80) {
            for (y in 20 until 100) {
                bmp.setPixel(x, y, seedColor)
            }
        }
        return bmp
    }

    @Test
    fun `scroll debounce triggers after delay and respects cooldown`() = runTest {
        var fakeClock = 10_000L
        var triggerCount = 0
        val scheduler = AutoCaptureScheduler(
            scope = this,
            clockMillis = { fakeClock },
            onTriggerCapture = { triggerCount++ }
        ).apply {
            isEnabled = true
            delayMillis = 1500L
            cooldownMillis = 3000L
        }

        // Rapid scrolling resets debounce timer
        scheduler.onScrollActivity()
        advanceTimeBy(1000L)
        fakeClock += 1000L
        scheduler.onScrollActivity()
        advanceTimeBy(1000L)
        fakeClock += 1000L
        assertEquals(0, triggerCount)

        // Finish remaining 500ms after last scroll
        advanceTimeBy(550L)
        fakeClock += 550L
        runCurrent()
        assertEquals(1, triggerCount)

        // Immediate next scroll stops before cooldown expires -> should not trigger
        scheduler.onScrollActivity()
        advanceTimeBy(1550L)
        fakeClock += 1550L
        runCurrent()
        assertEquals(1, triggerCount)

        // After cooldown passes -> triggers again
        fakeClock += 3000L
        scheduler.onScrollActivity()
        advanceTimeBy(1550L)
        runCurrent()
        assertEquals(2, triggerCount)
    }

    @Test
    fun `unchanged screen is deduplicated and duplicate capture while busy is ignored`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var ocrCalls = 0

        val fakeOcr = object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap, preferredSource: LanguageOption): Result<OcrResult> {
                ocrCalls++
                delay(200)
                return Result.success(
                    OcrResult(
                        blocks = listOf(OcrBlock("行くぞ", listOf("行くぞ"), OcrBoundingBox(10, 10, 80, 40), "ja")),
                        rawText = "行くぞ",
                        detectedLanguage = "ja",
                        imageWidth = 120,
                        imageHeight = 200
                    )
                )
            }
            override fun detectScriptFromText(text: String) = "ja"
            override fun close() {}
        }

        val fakeOffline = object : TranslationEngine {
            override val providerType = TranslationProviderType.OFFLINE_MLKIT
            override suspend fun translate(request: TranslationRequest): TranslationResult {
                return TranslationResult.Success("ลุยกันเลย", providerType, "ja")
            }
        }
        val fakeGemini = object : TranslationEngine {
            override val providerType = TranslationProviderType.GEMINI_AI
            override suspend fun translate(request: TranslationRequest): TranslationResult {
                return TranslationResult.Success("ลุยกันเลย AI", providerType, "ja")
            }
        }

        val useCase = TranslateScreenUseCase(
            appContext = context,
            scope = scope,
            ocrEngine = fakeOcr,
            mangaContextProcessor = MangaContextProcessor(),
            translationRouter = TranslationRouter(fakeOffline, fakeGemini),
            historyRepository = FakeHistoryRepo(),
            diagnosticsRepository = DiagnosticsRepositoryImpl()
        )

        val settings = AppSettings(translationMode = TranslationMode.OFFLINE, aiEnabled = false)

        // Simultaneous second call while first is capturing -> ignored
        useCase.processCapturedBitmap({ Result.success(makePatternBitmap(Color.BLACK)) }, settings, forceProcess = false)
        useCase.processCapturedBitmap({ Result.success(makePatternBitmap(Color.BLACK)) }, settings, forceProcess = false)
        scope.advanceUntilIdle()
        assertEquals(1, ocrCalls)
        assertEquals("ลุยกันเลย", useCase.state.value.lastTranslatedText)

        // Identical screen with forceProcess = false -> skipped via dHash deduplication
        useCase.processCapturedBitmap({ Result.success(makePatternBitmap(Color.BLACK)) }, settings, forceProcess = false)
        scope.advanceUntilIdle()
        assertEquals(1, ocrCalls)
        assertNotNull(useCase.state.value.lastSkippedReason)
    }

    @Test
    fun `newer retranslate request cancels outdated slow translation job`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)

        val slowThenFastEngine = object : TranslationEngine {
            override val providerType = TranslationProviderType.OFFLINE_MLKIT
            override suspend fun translate(request: TranslationRequest): TranslationResult {
                if (request.sourceText.contains("OLD")) {
                    delay(5000L)
                    return TranslationResult.Success("ข้อความเก่า", providerType, "ja")
                }
                delay(100L)
                return TranslationResult.Success("ข้อความใหม่", providerType, "ja")
            }
        }
        val useCase = TranslateScreenUseCase(
            appContext = context,
            scope = scope,
            ocrEngine = object : OcrEngine {
                override suspend fun recognize(bitmap: Bitmap, preferredSource: LanguageOption) =
                    Result.failure<OcrResult>(IllegalStateException())
                override fun detectScriptFromText(text: String) = "ja"
                override fun close() {}
            },
            mangaContextProcessor = MangaContextProcessor(),
            translationRouter = TranslationRouter(slowThenFastEngine, slowThenFastEngine),
            historyRepository = FakeHistoryRepo(),
            diagnosticsRepository = DiagnosticsRepositoryImpl()
        )

        val settings = AppSettings(translationMode = TranslationMode.OFFLINE, aiEnabled = false)
        useCase.retranslateEditedText("OLD", settings)
        scope.advanceTimeBy(50L)
        useCase.retranslateEditedText("NEW", settings)
        scope.advanceUntilIdle()

        assertEquals("ข้อความใหม่", useCase.state.value.lastTranslatedText)
        assertTrue(!useCase.state.value.isTranslating)
    }
}
