package com.example.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import com.example.core.image.BitmapUtils
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureStatus
import com.example.domain.model.LanguageOption
import com.example.domain.model.TranslationHistoryItem
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import com.example.domain.model.TranslatorState
import com.example.domain.repository.DiagnosticsRepository
import com.example.domain.repository.HistoryRepository
import com.example.manga.MangaContextProcessor
import com.example.ocr.OcrEngine
import com.example.translation.TranslationRouter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TranslateScreenUseCase(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val ocrEngine: OcrEngine,
    private val mangaContextProcessor: MangaContextProcessor,
    private val translationRouter: TranslationRouter,
    private val historyRepository: HistoryRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) {
    private val _state = MutableStateFlow(TranslatorState())
    val state: StateFlow<TranslatorState> = _state.asStateFlow()

    private val captureInProgress = AtomicBoolean(false)
    private var activeTranslationJob: Job? = null
    private var lastScreenHash: Long? = null

    fun updateCaptureStatus(status: CaptureStatus) {
        _state.update { it.copy(captureStatus = status) }
    }

    fun updateAccessibilityStatus(connected: Boolean) {
        _state.update { it.copy(isAutoScrollServiceConnected = connected) }
    }

    fun updateOverlayShowing(showing: Boolean) {
        _state.update { it.copy(isOverlayShowing = showing) }
    }

    fun updateEditableSourceText(text: String) {
        _state.update { it.copy(editableSourceText = text) }
    }

    fun clearScreenDeduplication() {
        lastScreenHash = null
    }

    fun cancelActiveTranslation() {
        activeTranslationJob?.cancel()
        activeTranslationJob = null
        _state.update { it.copy(isOcrRunning = false, isTranslating = false) }
    }

    fun processCapturedBitmap(
        bitmapProvider: suspend () -> Result<Bitmap>,
        settings: AppSettings,
        forceProcess: Boolean = false
    ) {
        // Prevent duplicate capture requests while a capture+OCR is already running
        if (!captureInProgress.compareAndSet(false, true)) {
            _state.update { it.copy(lastSkippedReason = "กำลังประมวลผลภาพหน้าจอก่อนหน้าอยู่") }
            return
        }

        // Cancel outdated translation job when a newer screen capture starts
        activeTranslationJob?.cancel()
        activeTranslationJob = scope.launch {
            var capturedBitmap: Bitmap? = null
            var ocrBitmap: Bitmap? = null
            try {
                _state.update {
                    it.copy(
                        captureStatus = CaptureStatus.CAPTURING,
                        isOcrRunning = true,
                        isTranslating = false,
                        lastErrorThai = null,
                        lastSkippedReason = null
                    )
                }

                val captureResult = bitmapProvider()
                val rawBitmap = captureResult.getOrElse { err ->
                    _state.update {
                        it.copy(
                            captureStatus = CaptureStatus.ACTIVE,
                            isOcrRunning = false,
                            lastErrorThai = err.localizedMessage ?: "จับภาพหน้าจอไม่สำเร็จ"
                        )
                    }
                    return@launch
                }
                capturedBitmap = rawBitmap

                val screenHash = BitmapUtils.computeDHash(rawBitmap)
                if (!forceProcess && BitmapUtils.isScreenUnchanged(lastScreenHash, screenHash)) {
                    _state.update {
                        it.copy(
                            captureStatus = CaptureStatus.ACTIVE,
                            isOcrRunning = false,
                            lastSkippedReason = "หน้าจอเดิม ไม่พบข้อความใหม่ (ข้ามการแปลซ้ำ)"
                        )
                    }
                    return@launch
                }

                ocrBitmap = BitmapUtils.downscaleForOcr(rawBitmap)
                val ocrResult = ocrEngine.recognize(ocrBitmap, settings.sourceLanguage)
                    .getOrElse { err ->
                        diagnosticsRepository.recordError("OcrEngine", err.javaClass.simpleName)
                        _state.update {
                            it.copy(
                                captureStatus = CaptureStatus.ACTIVE,
                                isOcrRunning = false,
                                lastErrorThai = "อ่านข้อความจากภาพไม่สำเร็จ: ${err.localizedMessage ?: "ลองใหม่อีกครั้ง"}"
                            )
                        }
                        return@launch
                    }

                if (ocrResult.blocks.isEmpty() || ocrResult.rawText.isBlank()) {
                    lastScreenHash = screenHash
                    _state.update {
                        it.copy(
                            captureStatus = CaptureStatus.ACTIVE,
                            isOcrRunning = false,
                            lastErrorThai = "ไม่พบข้อความญี่ปุ่น เกาหลี หรืออังกฤษบนหน้าจอนี้"
                        )
                    }
                    return@launch
                }

                val batch = mangaContextProcessor.process(
                    ocrResult = ocrResult,
                    readingDirectionPreference = settings.readingDirection
                )

                lastScreenHash = screenHash
                val optionalImagePath = if (settings.saveHistoryImages) {
                    BitmapUtils.saveOptionalThumbnail(appContext, rawBitmap)
                } else {
                    null
                }

                // Release bitmaps immediately before network/model translation
                if (ocrBitmap !== rawBitmap) BitmapUtils.safeRecycle(ocrBitmap)
                BitmapUtils.safeRecycle(rawBitmap)
                ocrBitmap = null
                capturedBitmap = null

                _state.update {
                    it.copy(
                        captureStatus = CaptureStatus.ACTIVE,
                        isOcrRunning = false,
                        isTranslating = true,
                        lastOcrText = batch.formattedText,
                        editableSourceText = batch.formattedText,
                        lastDetectedLanguage = batch.detectedSourceLanguage,
                        lastCaptureTimestamp = System.currentTimeMillis()
                    )
                }

                executeTranslation(
                    sourceText = batch.formattedText,
                    detectedLanguage = batch.detectedSourceLanguage,
                    previousContext = batch.previousContextLines,
                    settings = settings,
                    optionalImagePath = optionalImagePath,
                    forceProvider = null,
                    bypassCache = false
                )
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        isOcrRunning = false,
                        isTranslating = false
                    )
                }
                throw e
            } finally {
                if (ocrBitmap != null && ocrBitmap !== capturedBitmap) {
                    BitmapUtils.safeRecycle(ocrBitmap)
                }
                BitmapUtils.safeRecycle(capturedBitmap)
                captureInProgress.set(false)
                _state.update { current ->
                    if (current.captureStatus == CaptureStatus.CAPTURING) {
                        current.copy(captureStatus = CaptureStatus.ACTIVE)
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun retranslateEditedText(
        editedText: String,
        settings: AppSettings,
        forceProvider: TranslationProviderType? = null,
        bypassCache: Boolean = true
    ) {
        val clean = editedText.trim()
        if (clean.isEmpty()) {
            _state.update { it.copy(lastErrorThai = "กรุณากรอกข้อความต้นฉบับก่อนแปล") }
            return
        }
        activeTranslationJob?.cancel()
        activeTranslationJob = scope.launch {
            _state.update {
                it.copy(
                    isTranslating = true,
                    lastErrorThai = null,
                    lastOcrText = clean,
                    editableSourceText = clean
                )
            }
            val detected = ocrEngine.detectScriptFromText(clean)
            executeTranslation(
                sourceText = clean,
                detectedLanguage = detected,
                previousContext = mangaContextProcessor.snapshotContext(),
                settings = settings,
                optionalImagePath = null,
                forceProvider = forceProvider,
                bypassCache = bypassCache
            )
        }
    }

    private suspend fun executeTranslation(
        sourceText: String,
        detectedLanguage: String,
        previousContext: List<String>,
        settings: AppSettings,
        optionalImagePath: String?,
        forceProvider: TranslationProviderType?,
        bypassCache: Boolean
    ) {
        val sourceOption = if (settings.sourceLanguage != LanguageOption.AUTO) {
            settings.sourceLanguage
        } else {
            LanguageOption.fromCode(detectedLanguage)
        }

        val request = TranslationRequest(
            sourceText = sourceText,
            sourceLanguage = sourceOption,
            targetLanguage = settings.targetLanguage,
            readingDirection = settings.readingDirection,
            previousContext = previousContext,
            forceProvider = forceProvider
        )

        when (val result = translationRouter.translate(
            request = request,
            mode = settings.translationMode,
            hybridPriority = settings.hybridPriority,
            aiEnabled = settings.aiEnabled,
            bypassCache = bypassCache
        )) {
            is TranslationResult.Success -> {
                mangaContextProcessor.recordTranslatedContext(
                    sourceLines = sourceText.lines().filter { it.isNotBlank() },
                    translatedSummary = result.translatedText
                )
                historyRepository.saveRecord(
                    TranslationHistoryItem(
                        sourceText = sourceText,
                        translatedText = result.translatedText,
                        sourceLanguageCode = result.detectedLanguage,
                        targetLanguageCode = settings.targetLanguage.code,
                        provider = result.provider,
                        timestampMillis = System.currentTimeMillis(),
                        imagePath = optionalImagePath
                    )
                )
                _state.update {
                    it.copy(
                        isTranslating = false,
                        lastTranslatedText = result.translatedText,
                        activeProvider = result.provider,
                        lastDetectedLanguage = result.detectedLanguage,
                        lastErrorThai = null
                    )
                }
            }
            is TranslationResult.Failure -> {
                _state.update {
                    it.copy(
                        isTranslating = false,
                        activeProvider = TranslationProviderType.UNAVAILABLE,
                        lastErrorThai = result.userMessageThai
                    )
                }
            }
        }
    }
}
