package com.example.translation

import com.example.domain.model.RegionTranslationItem
import com.example.domain.model.RegionTranslationRequest
import com.example.domain.model.RegionTranslationResult
import com.example.domain.model.TranslationFailureReason
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult

interface TranslationEngine {
    val providerType: TranslationProviderType
    suspend fun translate(request: TranslationRequest): TranslationResult

    suspend fun translateRegions(request: RegionTranslationRequest): RegionTranslationResult {
        if (request.regions.isEmpty()) {
            return RegionTranslationResult.Failure(
                reason = TranslationFailureReason.EMPTY_INPUT,
                userMessageThai = "ไม่พบพื้นที่ข้อความสำหรับแปล",
                technicalDetails = "Empty region list"
            )
        }

        val items = ArrayList<RegionTranslationItem>(request.regions.size)
        var resolvedLang = request.sourceLanguage.code
        var lastNote: String? = null

        for (region in request.regions) {
            val single = translate(
                TranslationRequest(
                    sourceText = region.text,
                    sourceLanguage = request.sourceLanguage,
                    targetLanguage = request.targetLanguage,
                    readingDirection = request.readingDirection,
                    previousContext = request.previousContext,
                    forceProvider = request.forceProvider
                )
            )
            when (single) {
                is TranslationResult.Failure -> {
                    return RegionTranslationResult.Failure(
                        reason = single.reason,
                        userMessageThai = single.userMessageThai,
                        technicalDetails = "Region ${region.id} failed: ${single.technicalDetails}"
                    )
                }
                is TranslationResult.Success -> {
                    resolvedLang = single.detectedLanguage
                    lastNote = single.note ?: lastNote
                    items.add(RegionTranslationItem(region.id, single.translatedText))
                }
            }
        }

        return when (val validation = RegionBatchValidator.validateItems(request.regions, items, resolvedLang, lastNote)) {
            is RegionBatchValidationResult.Valid -> RegionTranslationResult.Success(
                translationsById = validation.translationsById,
                provider = providerType,
                detectedLanguage = validation.detectedLanguage ?: resolvedLang,
                note = validation.note
            )
            is RegionBatchValidationResult.Invalid -> RegionTranslationResult.Failure(
                reason = validation.reason,
                userMessageThai = validation.userMessageThai,
                technicalDetails = validation.technicalDetails
            )
        }
    }
}
