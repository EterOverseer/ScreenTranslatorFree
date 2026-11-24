package com.example.persistence

import com.example.data.local.TranslationHistoryDao
import com.example.data.local.TranslationHistoryEntity
import com.example.data.repository.DiagnosticsRepositoryImpl
import com.example.data.repository.HistoryRepositoryImpl
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureMode
import com.example.domain.model.LanguageOption
import com.example.domain.model.TranslationHistoryItem
import com.example.domain.model.TranslationMode
import com.example.domain.model.TranslationProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAndDiagnosticsUnitTest {

    private class InMemoryHistoryDao : TranslationHistoryDao {
        private val items = MutableStateFlow<List<TranslationHistoryEntity>>(emptyList())
        private var nextId = 1L

        override fun getAllHistory(): Flow<List<TranslationHistoryEntity>> = items

        override fun searchHistory(
            searchQuery: String,
            languageFilter: String?
        ): Flow<List<TranslationHistoryEntity>> {
            return items.map { list ->
                list.filter { entity ->
                    val langMatch = languageFilter == null || entity.sourceLanguageCode == languageFilter
                    val qMatch = searchQuery.isEmpty() ||
                        entity.sourceText.contains(searchQuery, ignoreCase = true) ||
                        entity.translatedText.contains(searchQuery, ignoreCase = true)
                    langMatch && qMatch
                }.sortedByDescending { it.timestampMillis }
            }
        }

        override suspend fun insertHistory(entity: TranslationHistoryEntity): Long {
            val assigned = if (entity.id == 0L) nextId++ else entity.id
            val stored = entity.copy(id = assigned)
            items.value = items.value.filterNot { it.id == assigned } + stored
            return assigned
        }

        override suspend fun deleteById(id: Long) {
            items.value = items.value.filterNot { it.id == id }
        }

        override suspend fun clearAll() {
            items.value = emptyList()
        }
    }

    @Test
    fun `history repository supports save search filter delete and clearAll`() = runTest {
        val repo = HistoryRepositoryImpl(InMemoryHistoryDao())
        val id1 = repo.saveRecord(
            TranslationHistoryItem(
                sourceText = "おはよう",
                translatedText = "อรุณสวัสดิ์",
                sourceLanguageCode = "ja",
                targetLanguageCode = "th",
                provider = TranslationProviderType.OFFLINE_MLKIT,
                timestampMillis = 1000L
            )
        )
        repo.saveRecord(
            TranslationHistoryItem(
                sourceText = "안녕하세요",
                translatedText = "สวัสดีครับ",
                sourceLanguageCode = "ko",
                targetLanguageCode = "th",
                provider = TranslationProviderType.GEMINI_AI,
                timestampMillis = 2000L
            )
        )

        assertEquals(2, repo.observeHistory().first().size)
        val jaOnly = repo.observeHistory(languageFilter = "ja").first()
        assertEquals(1, jaOnly.size)
        assertEquals("อรุณสวัสดิ์", jaOnly.first().translatedText)

        val searchHit = repo.observeHistory(query = "สวัสดี").first()
        assertEquals(1, searchHit.size)
        assertEquals("ko", searchHit.first().sourceLanguageCode)

        repo.deleteRecord(id1)
        assertEquals(1, repo.observeHistory().first().size)

        repo.clearAll()
        assertEquals(0, repo.observeHistory().first().size)
    }

    @Test
    fun `diagnostics repository bounds error entries and clears cleanly`() = runTest {
        val diagnostics = DiagnosticsRepositoryImpl(maxEntries = 3)
        diagnostics.recordError("OcrEngine", "err1")
        diagnostics.recordError("Gemini", "err2")
        diagnostics.recordError("Capture", "err3")
        diagnostics.recordError("Router", "err4")

        val entries = diagnostics.errorsFlow.first()
        assertEquals(3, entries.size)
        assertEquals("Router", entries.first().component)

        diagnostics.clearErrors()
        assertTrue(diagnostics.errorsFlow.first().isEmpty())
    }

    @Test
    fun `default app settings enforce privacy-safe defaults`() {
        val defaults = AppSettings()
        assertEquals(TranslationMode.HYBRID, defaults.translationMode)
        assertEquals(CaptureMode.BOTH, defaults.captureMode)
        assertEquals(LanguageOption.THAI, defaults.targetLanguage)
        assertEquals(1500L, defaults.autoCaptureDelayMs)
        assertFalse(defaults.saveHistoryImages)
    }
}
