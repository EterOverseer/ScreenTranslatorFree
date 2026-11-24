package com.example.domain.repository

import com.example.domain.model.AppSettings
import com.example.domain.model.DiagnosticErrorEntry
import com.example.domain.model.TranslationHistoryItem
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun observeHistory(query: String = "", languageFilter: String? = null): Flow<List<TranslationHistoryItem>>
    suspend fun saveRecord(item: TranslationHistoryItem): Long
    suspend fun deleteRecord(id: Long)
    suspend fun clearAll()
}

interface SettingsRepository {
    val settingsFlow: Flow<AppSettings>
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
    suspend fun resetSettings()
}

interface DiagnosticsRepository {
    val errorsFlow: Flow<List<DiagnosticErrorEntry>>
    fun recordError(component: String, safeMessage: String)
    fun clearErrors()
}
