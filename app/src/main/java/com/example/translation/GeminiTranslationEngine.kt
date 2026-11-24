package com.example.translation

import android.util.Log
import com.example.domain.model.LanguageOption
import com.example.domain.model.ReadingDirection
import com.example.domain.model.TranslationFailureReason
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult
import com.example.domain.repository.DiagnosticsRepository
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class GeminiTranslationEngine(
    private val diagnosticsRepository: DiagnosticsRepository,
    private val customNoteProvider: () -> String = { "" }
) : TranslationEngine {

    override val providerType: TranslationProviderType = TranslationProviderType.GEMINI_AI

    private val responseSchema = Schema.obj(
        properties = mapOf(
            "detectedLanguage" to Schema.string(
                description = "ISO 639-1 language code of the source text, e.g. ja, ko, en, mixed"
            ),
            "translatedText" to Schema.string(
                description = "Natural Thai translation preserving dialogue paragraph breaks and [SFX: ...] sound effects"
            ),
            "toneNote" to Schema.string(
                description = "Brief optional note in Thai about character tone or terminology",
                nullable = true
            )
        ),
        optionalProperties = listOf("toneNote")
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = this@GeminiTranslationEngine.responseSchema
                temperature = 0.25f
            }
        )
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult =
        withContext(Dispatchers.IO) {
            val cleanedSource = request.sourceText.trim()
            if (cleanedSource.isEmpty()) {
                return@withContext TranslationResult.Failure(
                    reason = TranslationFailureReason.EMPTY_INPUT,
                    userMessageThai = "ไม่พบข้อความสำหรับส่งให้ Gemini AI",
                    technicalDetails = "Empty source text"
                )
            }

            val prompt = buildMangaTranslationPrompt(request, cleanedSource)
            try {
                val response = withTimeout(60_000L) {
                    model.generateContent(prompt)
                }
                val rawJson = response.text?.trim()
                if (rawJson.isNullOrEmpty()) {
                    return@withContext TranslationResult.Failure(
                        reason = TranslationFailureReason.UNKNOWN,
                        userMessageThai = "Gemini AI ไม่ได้ส่งคำตอบกลับมา",
                        technicalDetails = "Empty model response"
                    )
                }

                val json = JSONObject(rawJson)
                val translatedText = json.optString("translatedText", "").trim()
                val detectedLang = json.optString("detectedLanguage", request.sourceLanguage.code)
                    .ifBlank { request.sourceLanguage.code }
                val toneNote = json.optString("toneNote", "").takeIf { it.isNotBlank() && it != "null" }

                if (translatedText.isEmpty()) {
                    TranslationResult.Failure(
                        reason = TranslationFailureReason.UNKNOWN,
                        userMessageThai = "ผลลัพธ์จาก Gemini AI ว่างเปล่า",
                        technicalDetails = "Blank translatedText in JSON"
                    )
                } else {
                    TranslationResult.Success(
                        translatedText = translatedText,
                        provider = TranslationProviderType.GEMINI_AI,
                        detectedLanguage = detectedLang,
                        note = toneNote
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini translation timed out", e)
                diagnosticsRepository.recordError("GeminiTranslationEngine", "Request timed out after 60s")
                TranslationResult.Failure(
                    reason = TranslationFailureReason.TIMEOUT,
                    userMessageThai = "การเชื่อมต่อ Gemini AI หมดเวลา (60 วินาที) กรุณาลองใหม่",
                    technicalDetails = "TimeoutCancellationException"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini translation failed", e)
                mapExceptionToFailure(e)
            }
        }

    private fun buildMangaTranslationPrompt(
        request: TranslationRequest,
        sourceText: String
    ): String {
        val styleHint = when (request.readingDirection) {
            ReadingDirection.RTL_MANGA -> "Japanese Manga (Right-to-Left dialogue bubbles)"
            ReadingDirection.LTR_MANHWA -> "Korean Manhwa / Webtoon or general app screen"
            ReadingDirection.AUTO -> when (request.sourceLanguage) {
                LanguageOption.JAPANESE -> "Japanese Manga"
                LanguageOption.KOREAN -> "Korean Manhwa"
                else -> "Manga/Manhwa or general mobile screen"
            }
        }
        val contextSection = if (request.previousContext.isNotEmpty()) {
            "Previous dialogue context for consistent character names & pronouns (do NOT repeat these in output):\n" +
                request.previousContext.joinToString("\n") { "- $it" }
        } else {
            "No previous context."
        }
        val customNote = customNoteProvider().trim()
        val customSection = if (customNote.isNotEmpty()) {
            "\nUser glossary / tone preference: $customNote"
        } else {
            ""
        }

        return """
            You are an expert Japanese/Korean Manga & Manhwa translator into natural, idiomatic Thai.
            Content type: $styleHint
            Source language hint: ${request.sourceLanguage.code}
            Target language: Thai (th)

            Strict Rules:
            1. Translate ONLY what appears in the OCR text below. NEVER invent or hallucinate extra dialogue.
            2. Produce natural Thai dialogue suitable for comics/screen reading with appropriate pronouns, honorifics (e.g. พี่/รุ่นพี่/คุณ/ท่าน), and emotional tone.
            3. Keep separate dialogue paragraphs separated by blank lines (double newline).
            4. For sound effects marked [SFX] or short comic SFX, interpret them naturally as [SFX: คำเลียนเสียงภาษาไทย].
            5. Keep character names and proper nouns consistent with previous context.$customSection

            $contextSection

            OCR Text to Translate:
            $sourceText
        """.trimIndent()
    }

    private fun mapExceptionToFailure(e: Exception): TranslationResult.Failure {
        val msg = e.message.orEmpty()
        val lower = msg.lowercase()
        diagnosticsRepository.recordError("GeminiTranslationEngine", "${e.javaClass.simpleName}: ${msg.take(120)}")

        return when {
            e is UnknownHostException || e is IOException || "network" in lower || "unable to resolve host" in lower -> {
                TranslationResult.Failure(
                    reason = TranslationFailureReason.NETWORK_UNAVAILABLE,
                    userMessageThai = "ไม่สามารถเชื่อมต่ออินเทอร์เน็ตเพื่อใช้งาน Gemini AI",
                    technicalDetails = e.javaClass.simpleName
                )
            }
            "quota" in lower || "429" in lower || "resource_exhausted" in lower -> {
                TranslationResult.Failure(
                    reason = TranslationFailureReason.QUOTA_EXCEEDED,
                    userMessageThai = "เกินโควตาการใช้งาน Gemini AI ชั่วคราว กรุณารอสักครู่หรือสลับเป็นโหมดออฟไลน์",
                    technicalDetails = "Quota / 429"
                )
            }
            "403" in lower || "app check" in lower || "attestation" in lower || "unauthenticated" in lower -> {
                TranslationResult.Failure(
                    reason = TranslationFailureReason.APP_CHECK_OR_SERVICE_UNAVAILABLE,
                    userMessageThai = "บริการ Gemini AI / App Check ยังไม่พร้อมใช้งาน (HTTP 403)",
                    technicalDetails = "App Check / 403"
                )
            }
            else -> {
                TranslationResult.Failure(
                    reason = TranslationFailureReason.UNKNOWN,
                    userMessageThai = "Gemini AI ขัดข้อง: ${e.localizedMessage ?: "ไม่สามารถแปลได้ในขณะนี้"}",
                    technicalDetails = e.javaClass.simpleName
                )
            }
        }
    }

    companion object {
        private const val TAG = "GeminiTranslationEngine"
    }
}
