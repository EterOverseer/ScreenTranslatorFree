package com.example.translation

import com.example.domain.model.LanguageOption
import com.example.domain.model.OfflineModelInfo
import com.example.domain.model.TranslationFailureReason
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import com.example.domain.repository.DiagnosticsRepository
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class OfflineTranslationEngine(
    private val diagnosticsRepository: DiagnosticsRepository
) : TranslationEngine {

    override val providerType: TranslationProviderType = TranslationProviderType.OFFLINE_MLKIT

    private val modelManager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    private val managedLanguages = listOf(
        Triple(TranslateLanguage.JAPANESE, "ญี่ปุ่น (มังงะ)", "Japanese"),
        Triple(TranslateLanguage.KOREAN, "เกาหลี (มันฮวา)", "Korean"),
        Triple(TranslateLanguage.ENGLISH, "อังกฤษ", "English"),
        Triple(TranslateLanguage.THAI, "ไทย (ปลายทาง)", "Thai")
    )

    private val _modelsFlow = MutableStateFlow(
        managedLanguages.map { (code, thaiName, englishName) ->
            OfflineModelInfo(
                languageCode = code,
                thaiName = thaiName,
                englishName = englishName,
                isDownloaded = false,
                isDownloading = false,
                estimatedSizeMb = 30
            )
        }
    )
    val modelsFlow: StateFlow<List<OfflineModelInfo>> = _modelsFlow.asStateFlow()

    suspend fun refreshModelStatuses() = withContext(Dispatchers.IO) {
        val updated = managedLanguages.map { (code, thaiName, englishName) ->
            val model = TranslateRemoteModel.Builder(code).build()
            val downloaded = runCatching { modelManager.isModelDownloaded(model).await() }.getOrDefault(false)
            val existing = _modelsFlow.value.firstOrNull { it.languageCode == code }
            OfflineModelInfo(
                languageCode = code,
                thaiName = thaiName,
                englishName = englishName,
                isDownloaded = downloaded,
                isDownloading = existing?.isDownloading ?: false,
                estimatedSizeMb = 30,
                errorMessage = existing?.errorMessage
            )
        }
        _modelsFlow.value = updated
    }

    suspend fun downloadModel(languageCode: String, requireWifi: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            val mlKitCode = TranslateLanguage.fromLanguageTag(languageCode)
                ?: return@withContext Result.failure(IllegalArgumentException("ไม่รองรับภาษา $languageCode"))

            updateModelState(languageCode) { it.copy(isDownloading = true, errorMessage = null) }
            try {
                val model = TranslateRemoteModel.Builder(mlKitCode).build()
                val conditionsBuilder = DownloadConditions.Builder()
                if (requireWifi) {
                    conditionsBuilder.requireWifi()
                }
                modelManager.download(model, conditionsBuilder.build()).await()
                updateModelState(languageCode) {
                    it.copy(isDownloaded = true, isDownloading = false, errorMessage = null)
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                updateModelState(languageCode) { it.copy(isDownloading = false) }
                throw e
            } catch (e: Exception) {
                val msg = "ดาวน์โหลดโมเดล $languageCode ไม่สำเร็จ: ${e.localizedMessage ?: "ตรวจสอบอินเทอร์เน็ต"}"
                diagnosticsRepository.recordError("OfflineModelDownload", e.javaClass.simpleName + ": " + (e.message ?: "failed"))
                updateModelState(languageCode) {
                    it.copy(isDownloading = false, errorMessage = msg)
                }
                Result.failure(e)
            }
        }

    suspend fun deleteModel(languageCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val mlKitCode = TranslateLanguage.fromLanguageTag(languageCode)
            ?: return@withContext Result.failure(IllegalArgumentException("ไม่รองรับภาษา $languageCode"))
        try {
            val model = TranslateRemoteModel.Builder(mlKitCode).build()
            modelManager.deleteDownloadedModel(model).await()
            updateModelState(languageCode) {
                it.copy(isDownloaded = false, isDownloading = false, errorMessage = null)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticsRepository.recordError("OfflineModelDelete", e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult =
        withContext(Dispatchers.IO) {
            val cleanedSource = request.sourceText.trim()
            if (cleanedSource.isEmpty()) {
                return@withContext TranslationResult.Failure(
                    reason = TranslationFailureReason.EMPTY_INPUT,
                    userMessageThai = "ไม่พบข้อความสำหรับแปล",
                    technicalDetails = "Empty source text"
                )
            }

            val resolvedSourceCode = resolveSourceLanguageCode(cleanedSource, request.sourceLanguage)
            val resolvedTargetCode = TranslateLanguage.fromLanguageTag(request.targetLanguage.code)
                ?: TranslateLanguage.THAI

            if (resolvedSourceCode == null || resolvedSourceCode == "und") {
                return@withContext TranslationResult.Failure(
                    reason = TranslationFailureReason.LANGUAGE_PAIR_UNAVAILABLE,
                    userMessageThai = "ไม่สามารถระบุภาษาต้นฉบับสำหรับการแปลออฟไลน์ได้",
                    technicalDetails = "Unidentified source language"
                )
            }

            val mlKitSource = TranslateLanguage.fromLanguageTag(resolvedSourceCode)
            val supportedLanguages = TranslateLanguage.getAllLanguages()
            if (mlKitSource == null || !supportedLanguages.contains(mlKitSource) || !supportedLanguages.contains(resolvedTargetCode)) {
                return@withContext TranslationResult.Failure(
                    reason = TranslationFailureReason.LANGUAGE_PAIR_UNAVAILABLE,
                    userMessageThai = "คู่ภาษา $resolvedSourceCode → $resolvedTargetCode ไม่รองรับในโหมดออฟไลน์",
                    technicalDetails = "Unsupported ML Kit language pair: $resolvedSourceCode -> $resolvedTargetCode"
                )
            }

            if (mlKitSource == resolvedTargetCode) {
                return@withContext TranslationResult.Success(
                    translatedText = cleanedSource,
                    provider = TranslationProviderType.OFFLINE_MLKIT,
                    detectedLanguage = resolvedSourceCode
                )
            }

            // Strictly verify both offline models are installed before running translation
            val sourceModel = TranslateRemoteModel.Builder(mlKitSource).build()
            val targetModel = TranslateRemoteModel.Builder(resolvedTargetCode).build()
            val sourceReady = runCatching { modelManager.isModelDownloaded(sourceModel).await() }.getOrDefault(false)
            val targetReady = runCatching { modelManager.isModelDownloaded(targetModel).await() }.getOrDefault(false)

            if (!sourceReady || !targetReady) {
                val missing = buildList {
                    if (!sourceReady) add(mlKitSource.uppercase())
                    if (!targetReady) add(resolvedTargetCode.uppercase())
                }.joinToString(", ")
                return@withContext TranslationResult.Failure(
                    reason = TranslationFailureReason.OFFLINE_MODEL_NOT_DOWNLOADED,
                    userMessageThai = "ยังไม่ได้ดาวน์โหลดโมเดลออฟไลน์ ($missing) กรุณาดาวน์โหลดในหน้าตั้งค่า",
                    technicalDetails = "Missing offline ML Kit models: $missing"
                )
            }

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitSource)
                .setTargetLanguage(resolvedTargetCode)
                .build()
            val translator = Translation.getClient(options)

            try {
                // Preserve paragraph boundaries from MangaContextProcessor
                val paragraphs = cleanedSource.split(Regex("""\n{2,}"""))
                val translatedParagraphs = paragraphs.map { paragraph ->
                    val trimmed = paragraph.trim()
                    if (trimmed.isEmpty()) {
                        ""
                    } else if (trimmed.startsWith("[SFX] ")) {
                        val sfxBody = trimmed.removePrefix("[SFX] ").trim()
                        val out = translator.translate(sfxBody).await()
                        "[SFX] $out"
                    } else {
                        translator.translate(trimmed).await()
                    }
                }.filter { it.isNotBlank() }

                val finalOutput = translatedParagraphs.joinToString("\n\n").trim()
                if (finalOutput.isEmpty()) {
                    TranslationResult.Failure(
                        reason = TranslationFailureReason.UNKNOWN,
                        userMessageThai = "ระบบแปลออฟไลน์ไม่คืนค่าข้อความ",
                        technicalDetails = "ML Kit returned blank translation"
                    )
                } else {
                    TranslationResult.Success(
                        translatedText = finalOutput,
                        provider = TranslationProviderType.OFFLINE_MLKIT,
                        detectedLanguage = resolvedSourceCode,
                        note = "แปลด้วยโมเดลออฟไลน์บนเครื่อง (ML Kit)"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticsRepository.recordError("OfflineTranslationEngine", e.javaClass.simpleName + ": " + (e.message ?: "error"))
                TranslationResult.Failure(
                    reason = TranslationFailureReason.UNKNOWN,
                    userMessageThai = "แปลออฟไลน์ไม่สำเร็จ: ${e.localizedMessage ?: "ข้อผิดพลาดไม่ทราบสาเหตุ"}",
                    technicalDetails = e.message ?: "Offline translate exception"
                )
            } finally {
                runCatching { translator.close() }
            }
        }

    private suspend fun resolveSourceLanguageCode(text: String, preferred: LanguageOption): String? {
        if (preferred != LanguageOption.AUTO && preferred != LanguageOption.THAI) {
            return preferred.code
        }
        val scriptGuess = detectScriptByUnicode(text)
        if (scriptGuess != "und") return scriptGuess
        val identified = runCatching { languageIdentifier.identifyLanguage(text).await() }.getOrNull()
        return if (identified.isNullOrBlank() || identified == "und") null else identified
    }

    private fun detectScriptByUnicode(text: String): String {
        var kana = 0
        var kanji = 0
        var hangul = 0
        var latin = 0
        for (ch in text) {
            when {
                ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> kana++
                ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' -> hangul++
                ch in '\u4E00'..'\u9FFF' -> kanji++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
            }
        }
        return when {
            hangul > 0 && hangul >= kana -> "ko"
            kana > 0 || kanji > 0 -> "ja"
            latin > 0 -> "en"
            else -> "und"
        }
    }

    private fun updateModelState(
        languageCode: String,
        transform: (OfflineModelInfo) -> OfflineModelInfo
    ) {
        _modelsFlow.update { list ->
            list.map { item -> if (item.languageCode == languageCode) transform(item) else item }
        }
    }
}
