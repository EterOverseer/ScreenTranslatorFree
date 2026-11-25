package com.example.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import com.example.core.image.BitmapUtils
import com.example.core.image.BubbleBackgroundEstimate
import com.example.core.image.BubbleBackgroundEstimator
import com.example.core.overlay.OverlayCaptureGuard
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureStatus
import com.example.domain.model.LanguageOption
import com.example.domain.model.MangaContextBatch
import com.example.domain.model.OverlayRenderStatus
import com.example.domain.model.RegionTranslationInput
import com.example.domain.model.RegionTranslationRequest
import com.example.domain.model.RegionTranslationResult
import com.example.domain.model.TranslatedRegion
import com.example.domain.model.TranslationDisplayMode
import com.example.domain.model.TranslationHistoryItem
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import com.example.domain.model.TranslatorState
import com.example.domain.repository.DiagnosticsRepository
import com.example.domain.repository.HistoryRepository
import com.example.manga.MangaContextProcessor
import com.example.ocr.OcrEngine
import com.example.translation.RegionBatchValidator
import com.example.translation.TranslationRouter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val diagnosticsRepository: DiagnosticsRepository,
    private val overlayCaptureGuard: OverlayCaptureGuard = OverlayCaptureGuard.NO_OP
) {
    private val _state = MutableStateFlow(TranslatorState())
    val state: StateFlow<TranslatorState> = _state.asStateFlow()

    private val captureInProgress = AtomicBoolean(false)
    private val captureGenerationCounter = AtomicLong(0L)
    private var activeTranslationJob: Job? = null
    private var lastScreenHash: Long? = null

    fun updateCaptureStatus(status: CaptureStatus) {
        _state.update { current ->
            val shouldClearRegions = status == CaptureStatus.IDLE || status == CaptureStatus.PAUSED
            current.copy(
                captureStatus = status,
                translatedRegions = if (shouldClearRegions) emptyList() else current.translatedRegions,
                overlayRenderStatus = when (status) {
                    CaptureStatus.IDLE -> OverlayRenderStatus.IDLE
                    CaptureStatus.PAUSED -> OverlayRenderStatus.PAUSED
                    else -> current.overlayRenderStatus
                }
            )
        }
    }

    fun updateAccessibilityStatus(connected: Boolean) {
        _state.update { it.copy(isAutoScrollServiceConnected = connected) }
    }

    fun updateOverlayShowing(showing: Boolean) {
        _state.update { it.copy(isOverlayShowing = showing) }
    }

    fun updateOverlayRenderOutcome(status: OverlayRenderStatus, warning: String?) {
        _state.update { current ->
            if (current.overlayRenderStatus == status && current.overlayWarning == warning) {
                current
            } else {
                current.copy(overlayRenderStatus = status, overlayWarning = warning)
            }
        }
    }

    fun clearTranslatedRegions() {
        _state.update { current ->
            if (current.translatedRegions.isEmpty()) current
            else current.copy(translatedRegions = emptyList())
        }
    }

    fun updateEditableSourceText(text: String) {
        _state.update { it.copy(editableSourceText = text) }
    }

    fun clearScreenDeduplication() {
        lastScreenHash = null
    }

    fun cancelActiveTranslation() {
        captureGenerationCounter.incrementAndGet()
        activeTranslationJob?.cancel()
        activeTranslationJob = null
        _state.update { it.copy(isOcrRunning = false, isTranslating = false) }
    }

    fun processCapturedBitmap(
        bitmapProvider: suspend () -> Result<Bitmap>,
        settings: AppSettings,
        forceProcess: Boolean = false
    ) {
        // Prevent duplicate capture requests while frame acquisition + OCR is still running
        if (!captureInProgress.compareAndSet(false, true)) {
            _state.update { it.copy(lastSkippedReason = "กำลังประมวลผลภาพหน้าจอก่อนหน้าอยู่") }
            return
        }

        // Supersede any outdated translation job from a previous page
        val generationId = captureGenerationCounter.incrementAndGet()
        activeTranslationJob?.cancel()
        activeTranslationJob = scope.launch {
            var capturedBitmap: Bitmap? = null
            var ocrBitmap: Bitmap? = null
            var capturePhaseReleased = false

            fun releaseCapturePhase() {
                if (!capturePhaseReleased) {
                    capturePhaseReleased = true
                    if (ocrBitmap != null && ocrBitmap !== capturedBitmap) {
                        BitmapUtils.safeRecycle(ocrBitmap)
                    }
                    BitmapUtils.safeRecycle(capturedBitmap)
                    ocrBitmap = null
                    capturedBitmap = null
                    captureInProgress.set(false)
                }
            }

            try {
                _state.update {
                    it.copy(
                        captureStatus = CaptureStatus.CAPTURING,
                        isOcrRunning = true,
                        isTranslating = false,
                        lastErrorThai = null,
                        lastSkippedReason = null,
                        captureGenerationId = generationId
                    )
                }

                // Mandatory self-capture prevention: hide app overlays before MediaProjection frame capture
                // and always restore in finally even on errors or cancellation.
                overlayCaptureGuard.suspendForCapture()
                val captureResult = try {
                    bitmapProvider()
                } finally {
                    overlayCaptureGuard.restoreAfterCapture()
                }

                val rawBitmap = captureResult.getOrElse { err ->
                    _state.update {
                        if (captureGenerationCounter.get() != generationId) return@update it
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
                        if (captureGenerationCounter.get() != generationId) return@update it
                        it.copy(
                            captureStatus = CaptureStatus.ACTIVE,
                            isOcrRunning = false,
                            lastSkippedReason = "หน้าจอเดิม ไม่พบข้อความใหม่ (ข้ามการแปลซ้ำ)"
                        )
                    }
                    return@launch
                }

                // New screen content detected: remove stale overlay patches immediately while processing
                if (settings.translationDisplayMode == TranslationDisplayMode.REPLACE) {
                    _state.update {
                        if (captureGenerationCounter.get() != generationId) it
                        else it.copy(translatedRegions = emptyList(), overlayWarning = null)
                    }
                }

                val localOcrBitmap = BitmapUtils.downscaleForOcr(rawBitmap)
                ocrBitmap = localOcrBitmap
                val ocrResult = ocrEngine.recognize(localOcrBitmap, settings.sourceLanguage)
                    .getOrElse { err ->
                        diagnosticsRepository.recordError("OcrEngine", err.javaClass.simpleName)
                        _state.update {
                            if (captureGenerationCounter.get() != generationId) return@update it
                            it.copy(
                                captureStatus = CaptureStatus.ACTIVE,
                                isOcrRunning = false,
                                translatedRegions = emptyList(),
                                lastErrorThai = "อ่านข้อความจากภาพไม่สำเร็จ: ${err.localizedMessage ?: "ลองใหม่อีกครั้ง"}"
                            )
                        }
                        return@launch
                    }

                if (ocrResult.blocks.isEmpty() || ocrResult.rawText.isBlank()) {
                    lastScreenHash = screenHash
                    _state.update {
                        if (captureGenerationCounter.get() != generationId) return@update it
                        it.copy(
                            captureStatus = CaptureStatus.ACTIVE,
                            isOcrRunning = false,
                            translatedRegions = emptyList(),
                            lastErrorThai = "ไม่พบข้อความญี่ปุ่น เกาหลี หรืออังกฤษบนหน้าจอนี้"
                        )
                    }
                    return@launch
                }

                val batch = mangaContextProcessor.process(
                    ocrResult = ocrResult,
                    readingDirectionPreference = settings.readingDirection
                )

                // Estimate bubble background colors locally from OCR bitmap BEFORE recycling bitmaps
                val bgEstimatesByRegionId = if (
                    settings.translationDisplayMode == TranslationDisplayMode.REPLACE &&
                    batch.regions.isNotEmpty()
                ) {
                    batch.regions.associate { region ->
                        region.id to BubbleBackgroundEstimator.estimate(localOcrBitmap, region.box)
                    }
                } else {
                    emptyMap()
                }

                lastScreenHash = screenHash
                val optionalImagePath = if (settings.saveHistoryImages) {
                    BitmapUtils.saveOptionalThumbnail(appContext, rawBitmap)
                } else {
                    null
                }

                // Release bitmaps and capture lock before potentially slow translation network/model work
                releaseCapturePhase()

                if (captureGenerationCounter.get() != generationId) return@launch

                _state.update {
                    if (captureGenerationCounter.get() != generationId) return@update it
                    it.copy(
                        captureStatus = CaptureStatus.ACTIVE,
                        isOcrRunning = false,
                        isTranslating = true,
                        lastOcrText = batch.formattedText,
                        editableSourceText = batch.formattedText,
                        lastDetectedLanguage = batch.detectedSourceLanguage,
                        lastCaptureTimestamp = System.currentTimeMillis(),
                        sourceImageWidth = ocrResult.imageWidth,
                        sourceImageHeight = ocrResult.imageHeight
                    )
                }

                if (settings.translationDisplayMode == TranslationDisplayMode.REPLACE && batch.regions.isNotEmpty()) {
                    executeRegionTranslation(
                        generationId = generationId,
                        batch = batch,
                        imageWidth = ocrResult.imageWidth,
                        imageHeight = ocrResult.imageHeight,
                        bgEstimatesByRegionId = bgEstimatesByRegionId,
                        settings = settings,
                        optionalImagePath = optionalImagePath
                    )
                } else {
                    executeTranslation(
                        generationId = generationId,
                        sourceText = batch.formattedText,
                        detectedLanguage = batch.detectedSourceLanguage,
                        previousContext = batch.previousContextLines,
                        settings = settings,
                        optionalImagePath = optionalImagePath,
                        forceProvider = null,
                        bypassCache = false
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    if (captureGenerationCounter.get() != generationId) it
                    else it.copy(isOcrRunning = false, isTranslating = false)
                }
                throw e
            } finally {
                releaseCapturePhase()
                _state.update { current ->
                    if (current.captureStatus == CaptureStatus.CAPTURING &&
                        captureGenerationCounter.get() == generationId
                    ) {
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
        val generationId = captureGenerationCounter.incrementAndGet()
        activeTranslationJob?.cancel()
        activeTranslationJob = scope.launch {
            _state.update {
                it.copy(
                    isTranslating = true,
                    lastErrorThai = null,
                    lastOcrText = clean,
                    editableSourceText = clean,
                    translatedRegions = emptyList(),
                    captureGenerationId = generationId
                )
            }
            val detected = ocrEngine.detectScriptFromText(clean)
            executeTranslation(
                generationId = generationId,
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

    private suspend fun executeRegionTranslation(
        generationId: Long,
        batch: MangaContextBatch,
        imageWidth: Int,
        imageHeight: Int,
        bgEstimatesByRegionId: Map<String, BubbleBackgroundEstimate>,
        settings: AppSettings,
        optionalImagePath: String?
    ) {
        val sourceOption = if (settings.sourceLanguage != LanguageOption.AUTO) {
            settings.sourceLanguage
        } else {
            LanguageOption.fromCode(batch.detectedSourceLanguage)
        }

        val request = RegionTranslationRequest(
            regions = batch.regions.map { region ->
                RegionTranslationInput(
                    id = region.id,
                    text = region.text,
                    isSfx = region.isSfx,
                    isShortExpression = region.isShortExpression
                )
            },
            sourceLanguage = sourceOption,
            targetLanguage = settings.targetLanguage,
            readingDirection = batch.readingDirection,
            previousContext = batch.previousContextLines
        )

        when (val result = translationRouter.translateRegions(
            request = request,
            mode = settings.translationMode,
            hybridPriority = settings.hybridPriority,
            aiEnabled = settings.aiEnabled,
            bypassCache = false
        )) {
            is RegionTranslationResult.Success -> {
                if (captureGenerationCounter.get() != generationId) return

                val translatedRegions = batch.regions.mapIndexed { orderIndex, region ->
                    val bg = bgEstimatesByRegionId[region.id]
                        ?: BubbleBackgroundEstimator.estimate(null, region.box)
                    val translated = result.translationsById.getValue(region.id)
                    TranslatedRegion(
                        id = region.id,
                        sourceText = region.text,
                        translatedText = translated,
                        box = region.box,
                        isSfx = region.isSfx,
                        isShortExpression = region.isShortExpression,
                        detectedLanguage = result.detectedLanguage,
                        estimatedBackgroundArgb = bg.colorArgb,
                        estimatedTextArgb = bg.textColorArgb,
                        backgroundConfidence = bg.confidence,
                        readingOrderIndex = orderIndex,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )
                }

                val combinedThai = RegionBatchValidator.formatCombinedPanelText(
                    regions = batch.regions,
                    translationsById = result.translationsById
                )

                mangaContextProcessor.recordTranslatedContext(
                    sourceLines = batch.regions.map { it.text },
                    translatedSummary = combinedThai
                )

                historyRepository.saveRecord(
                    TranslationHistoryItem(
                        sourceText = batch.formattedText,
                        translatedText = combinedThai,
                        sourceLanguageCode = result.detectedLanguage,
                        targetLanguageCode = settings.targetLanguage.code,
                        provider = result.provider,
                        timestampMillis = System.currentTimeMillis(),
                        imagePath = optionalImagePath
                    )
                )

                if (captureGenerationCounter.get() != generationId) return

                _state.update {
                    if (captureGenerationCounter.get() != generationId) return@update it
                    it.copy(
                        isTranslating = false,
                        lastTranslatedText = combinedThai,
                        activeProvider = result.provider,
                        lastDetectedLanguage = result.detectedLanguage,
                        lastErrorThai = null,
                        translatedRegions = translatedRegions,
                        sourceImageWidth = imageWidth,
                        sourceImageHeight = imageHeight,
                        overlayWarning = null,
                        captureGenerationId = generationId
                    )
                }
            }
            is RegionTranslationResult.Failure -> {
                if (captureGenerationCounter.get() != generationId) return
                _state.update {
                    if (captureGenerationCounter.get() != generationId) return@update it
                    it.copy(
                        isTranslating = false,
                        activeProvider = TranslationProviderType.UNAVAILABLE,
                        lastErrorThai = result.userMessageThai,
                        translatedRegions = emptyList(),
                        overlayRenderStatus = OverlayRenderStatus.FAILED
                    )
                }
            }
        }
    }

    private suspend fun executeTranslation(
        generationId: Long,
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
                if (captureGenerationCounter.get() != generationId) return

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
                    if (captureGenerationCounter.get() != generationId) return@update it
                    it.copy(
                        isTranslating = false,
                        lastTranslatedText = result.translatedText,
                        activeProvider = result.provider,
                        lastDetectedLanguage = result.detectedLanguage,
                        lastErrorThai = null,
                        translatedRegions = emptyList(),
                        overlayRenderStatus = if (settings.translationDisplayMode == TranslationDisplayMode.PANEL) {
                            OverlayRenderStatus.PANEL_ONLY
                        } else {
                            it.overlayRenderStatus
                        },
                        captureGenerationId = generationId
                    )
                }
            }
            is TranslationResult.Failure -> {
                if (captureGenerationCounter.get() != generationId) return
                _state.update {
                    if (captureGenerationCounter.get() != generationId) return@update it
                    it.copy(
                        isTranslating = false,
                        activeProvider = TranslationProviderType.UNAVAILABLE,
                        lastErrorThai = result.userMessageThai,
                        translatedRegions = emptyList()
                    )
                }
            }
        }
    }
}
