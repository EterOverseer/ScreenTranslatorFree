package com.example.data.repository

import com.example.data.local.TranslationHistoryDao
import com.example.data.local.TranslationHistoryEntity
import com.example.data.preferences.SettingsDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.DiagnosticErrorEntry
import com.example.domain.model.TranslationHistoryItem
import com.example.domain.repository.DiagnosticsRepository
import com.example.domain.repository.HistoryRepository
import com.example.domain.repository.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class HistoryRepositoryImpl(
    private val dao: TranslationHistoryDao,
    private val deleteImageFile: (String) -> Unit = { path ->
        runCatching { File(path).delete() }
    }
) : HistoryRepository {

    override fun observeHistory(
        query: String,
        languageFilter: String?
    ): Flow<List<TranslationHistoryItem>> {
        val normalizedFilter = languageFilter?.takeIf { it.isNotBlank() && it != "all" }
        return dao.searchHistory(query.trim(), normalizedFilter).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveRecord(item: TranslationHistoryItem): Long {
        return dao.insertHistory(TranslationHistoryEntity.fromDomain(item))
    }

    override suspend fun deleteRecord(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun clearAll() {
        dao.clearAll()
    }

    fun cleanupOptionalScreenshot(path: String?) {
        if (!path.isNullOrBlank()) {
            deleteImageFile(path)
        }
    }
}

class SettingsRepositoryImpl(
    private val dataStore: SettingsDataStore
) : SettingsRepository {
    override val settingsFlow: Flow<AppSettings> = dataStore.settingsFlow

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.update(transform)
    }

    override suspend fun resetSettings() {
        dataStore.reset()
    }
}

class DiagnosticsRepositoryImpl(
    private val maxEntries: Int = 30
) : DiagnosticsRepository {

    private val idCounter = AtomicLong(1L)
    private val _errorsFlow = MutableStateFlow<List<DiagnosticErrorEntry>>(emptyList())
    override val errorsFlow: Flow<List<DiagnosticErrorEntry>> = _errorsFlow.asStateFlow()

    override fun recordError(component: String, safeMessage: String) {
        val sanitized = safeMessage.take(220)
        val entry = DiagnosticErrorEntry(
            id = idCounter.getAndIncrement(),
            timestampMillis = System.currentTimeMillis(),
            component = component,
            message = sanitized
        )
        _errorsFlow.update { current ->
            (listOf(entry) + current).take(maxEntries)
        }
    }

    override fun clearErrors() {
        _errorsFlow.value = emptyList()
    }
}
