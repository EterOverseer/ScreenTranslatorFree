package com.example.ocr

import android.graphics.Bitmap
import com.example.domain.model.LanguageOption
import com.example.domain.model.OcrResult

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap, preferredSource: LanguageOption): Result<OcrResult>
    fun detectScriptFromText(text: String): String
    fun close()
}
