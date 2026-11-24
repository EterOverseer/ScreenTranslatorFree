package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.TranslationHistoryItem
import com.example.domain.model.TranslationProviderType

@Entity(tableName = "translation_history")
data class TranslationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguageCode: String,
    val targetLanguageCode: String,
    val providerName: String,
    val timestampMillis: Long,
    val imagePath: String? = null
) {
    fun toDomain(): TranslationHistoryItem {
        val provider = runCatching { TranslationProviderType.valueOf(providerName) }
            .getOrDefault(TranslationProviderType.UNAVAILABLE)
        return TranslationHistoryItem(
            id = id,
            sourceText = sourceText,
            translatedText = translatedText,
            sourceLanguageCode = sourceLanguageCode,
            targetLanguageCode = targetLanguageCode,
            provider = provider,
            timestampMillis = timestampMillis,
            imagePath = imagePath
        )
    }

    companion object {
        fun fromDomain(item: TranslationHistoryItem): TranslationHistoryEntity {
            return TranslationHistoryEntity(
                id = item.id,
                sourceText = item.sourceText,
                translatedText = item.translatedText,
                sourceLanguageCode = item.sourceLanguageCode,
                targetLanguageCode = item.targetLanguageCode,
                providerName = item.provider.name,
                timestampMillis = item.timestampMillis,
                imagePath = item.imagePath
            )
        }
    }
}
