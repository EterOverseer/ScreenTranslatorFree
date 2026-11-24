package com.example.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.app.AppContainer
import com.example.domain.model.TranslationHistoryItem
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val searchQuery: String = "",
    val languageFilter: String = "all",
    val items: List<TranslationHistoryItem> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val languageFilter = MutableStateFlow("all")

    val uiState: StateFlow<HistoryUiState> = combine(searchQuery, languageFilter) { q, lang ->
        q to lang
    }.flatMapLatest { (q, lang) ->
        val dbFilter = if (lang == "all") null else lang
        combine(
            container.historyRepository.observeHistory(q, dbFilter),
            searchQuery,
            languageFilter
        ) { items, currentQ, currentLang ->
            HistoryUiState(
                searchQuery = currentQ,
                languageFilter = currentLang,
                items = items
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HistoryUiState()
    )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onLanguageFilterChange(filter: String) {
        languageFilter.value = filter
    }

    fun deleteItem(item: TranslationHistoryItem) {
        viewModelScope.launch {
            item.imagePath?.let { path -> runCatching { File(path).delete() } }
            container.historyRepository.deleteRecord(item.id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            uiState.value.items.forEach { item ->
                item.imagePath?.let { path -> runCatching { File(path).delete() } }
            }
            container.historyRepository.clearAll()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HistoryViewModel(container) as T
                }
            }
    }
}
