package com.example.ocr

import android.graphics.Bitmap
import com.example.domain.model.LanguageOption
import com.example.domain.model.OcrBlock
import com.example.domain.model.OcrBoundingBox
import com.example.domain.model.OcrResult
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MlKitOcrEngine : OcrEngine {

    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val languageIdentifier by lazy {
        LanguageIdentification.getClient()
    }

    override suspend fun recognize(
        bitmap: Bitmap,
        preferredSource: LanguageOption
    ): Result<OcrResult> = withContext(Dispatchers.Default) {
        try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return@withContext Result.failure(IllegalArgumentException("ภาพหน้าจอไม่พร้อมใช้งาน"))
            }

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val width = bitmap.width
            val height = bitmap.height

            val visionText: Text = when (preferredSource) {
                LanguageOption.JAPANESE -> japaneseRecognizer.process(inputImage).await()
                LanguageOption.KOREAN -> koreanRecognizer.process(inputImage).await()
                LanguageOption.ENGLISH -> latinRecognizer.process(inputImage).await()
                LanguageOption.AUTO, LanguageOption.THAI -> recognizeAuto(inputImage)
            }

            val filteredBlocks = visionText.textBlocks.mapNotNull { block ->
                val rect = block.boundingBox ?: return@mapNotNull null
                val cleanText = block.text.trim()
                if (cleanText.isEmpty()) return@mapNotNull null
                if (isSystemUiNoise(cleanText, rect.top, rect.bottom, height)) return@mapNotNull null

                val lines = block.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) return@mapNotNull null

                val box = OcrBoundingBox(
                    left = rect.left.coerceAtLeast(0),
                    top = rect.top.coerceAtLeast(0),
                    right = rect.right.coerceAtMost(width),
                    bottom = rect.bottom.coerceAtMost(height)
                )
                val isVertical = box.height > box.width * 1.4f && lines.size >= 2
                val blockLang = detectScriptFromText(cleanText)

                OcrBlock(
                    text = cleanText,
                    lines = lines,
                    box = box,
                    detectedLanguage = blockLang,
                    isVerticalHint = isVertical
                )
            }

            val rawJoined = filteredBlocks.joinToString("\n\n") { it.text }
            val detectedLang = if (preferredSource != LanguageOption.AUTO && preferredSource != LanguageOption.THAI) {
                preferredSource.code
            } else {
                val scriptGuess = detectScriptFromText(rawJoined)
                if (scriptGuess != "und") {
                    scriptGuess
                } else if (rawJoined.isNotBlank()) {
                    runCatching {
                        val tag = languageIdentifier.identifyLanguage(rawJoined).await()
                        if (tag == "und") "en" else tag
                    }.getOrDefault("en")
                } else {
                    "und"
                }
            }

            Result.success(
                OcrResult(
                    blocks = filteredBlocks,
                    rawText = rawJoined,
                    detectedLanguage = detectedLang,
                    imageWidth = width,
                    imageHeight = height
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun recognizeAuto(inputImage: InputImage): Text = coroutineScope {
        val jaDeferred = async { runCatching { japaneseRecognizer.process(inputImage).await() }.getOrNull() }
        val koDeferred = async { runCatching { koreanRecognizer.process(inputImage).await() }.getOrNull() }
        val jaResult = jaDeferred.await()
        val koResult = koDeferred.await()

        val jaHangulCount = koResult?.text?.count { isHangul(it) } ?: 0
        val jaJapaneseCount = jaResult?.text?.count { isHiraganaOrKatakana(it) } ?: 0
        val jaCjkCount = jaResult?.text?.count { isCjkUnified(it) } ?: 0

        when {
            jaHangulCount > 2 && jaHangulCount >= jaJapaneseCount -> koResult!!
            jaResult != null && (jaJapaneseCount > 0 || jaCjkCount > 0) -> jaResult
            koResult != null && koResult.text.isNotBlank() -> koResult
            jaResult != null -> jaResult
            else -> latinRecognizer.process(inputImage).await()
        }
    }

    override fun detectScriptFromText(text: String): String {
        if (text.isBlank()) return "und"
        var hiraganaKatakana = 0
        var cjk = 0
        var hangul = 0
        var latin = 0
        var thai = 0

        for (ch in text) {
            when {
                isHiraganaOrKatakana(ch) -> hiraganaKatakana++
                isHangul(ch) -> hangul++
                isCjkUnified(ch) -> cjk++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
                ch in '\u0E00'..'\u0E7F' -> thai++
            }
        }

        return when {
            hangul > 0 && hangul >= hiraganaKatakana -> "ko"
            hiraganaKatakana > 0 || cjk > 0 -> "ja"
            thai > latin && thai > 0 -> "th"
            latin > 0 -> "en"
            else -> "und"
        }
    }

    override fun close() {
        runCatching { japaneseRecognizer.close() }
        runCatching { koreanRecognizer.close() }
        runCatching { latinRecognizer.close() }
        runCatching { languageIdentifier.close() }
    }

    private fun isSystemUiNoise(text: String, top: Int, bottom: Int, imageHeight: Int): Boolean {
        if (imageHeight <= 0) return false
        val inTopStatusBar = top < (imageHeight * 0.042f)
        val inBottomNavBar = bottom > (imageHeight * 0.965f)
        val trimmed = text.trim()

        if (inTopStatusBar) {
            val statusBarRegex = Regex("""^(\d{1,2}:\d{2}|5G|4G|LTE|Wi-?Fi|\d{1,3}%|VOLTE|\s)+$""", RegexOption.IGNORE_CASE)
            if (statusBarRegex.matches(trimmed) || trimmed.length <= 4) {
                return true
            }
        }
        if (inBottomNavBar && trimmed.length <= 3) {
            return true
        }
        // Pure punctuation / single isolated glyph noise
        if (trimmed.length == 1 && !trimmed[0].isLetterOrDigit()) {
            return true
        }
        return false
    }

    private fun isHiraganaOrKatakana(ch: Char): Boolean {
        return ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' || ch in '\uFF65'..'\uFF9F'
    }

    private fun isHangul(ch: Char): Boolean {
        return ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F'
    }

    private fun isCjkUnified(ch: Char): Boolean {
        return ch in '\u4E00'..'\u9FFF'
    }
}
