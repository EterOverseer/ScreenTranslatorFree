package com.example.translation

import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslationRequest
import com.example.domain.model.TranslationResult

interface TranslationEngine {
    val providerType: TranslationProviderType
    suspend fun translate(request: TranslationRequest): TranslationResult
}
