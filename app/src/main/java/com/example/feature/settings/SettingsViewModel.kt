package com.example.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.app.AppContainer
import com.example.core.edition.EditionConfig
import com.example.core.security.KeystoreEncryptionException
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureMode
import com.example.domain.model.HybridPriority
import com.example.domain.model.LanguageOption
import com.example.domain.model.OfflineModelInfo
import com.example.domain.model.ReadingDirection
import com.example.domain.model.TranslationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val offlineModels: List<OfflineModelInfo> = emptyList(),
    val isAutoScrollSupported: Boolean = EditionConfig.isAutoScrollSupported,
    val cryptoErrorMessage: String? = null
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val cryptoErrorFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        container.settingsRepository.settingsFlow,
        container.offlineTranslationEngine.modelsFlow,
        cryptoErrorFlow
    ) { settings, models, cryptoError ->
        SettingsUiState(
            settings = settings,
            offlineModels = models,
            isAutoScrollSupported = container.isAutoScrollSupported,
            cryptoErrorMessage = cryptoError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setSourceLanguage(language: LanguageOption) = update { it.copy(sourceLanguage = language) }
    fun setTargetLanguage(language: LanguageOption) = update { it.copy(targetLanguage = language) }
    fun setTranslationMode(mode: TranslationMode) = update { it.copy(translationMode = mode) }
    fun setHybridPriority(priority: HybridPriority) = update { it.copy(hybridPriority = priority) }
    fun setCaptureMode(mode: CaptureMode) = update {
        it.copy(captureMode = EditionConfig.normalizeCaptureMode(mode))
    }
    fun setReadingDirection(dir: ReadingDirection) = update { it.copy(readingDirection = dir) }
    fun setAutoDelayMs(delayMs: Long) = update { it.copy(autoCaptureDelayMs = delayMs.coerceIn(1000L, 5000L)) }
    fun setCooldownMs(cooldownMs: Long) = update { it.copy(cooldownMs = cooldownMs.coerceIn(1000L, 10000L)) }

    fun setAiEnabled(enabled: Boolean) = update {
        val nextMode = if (!enabled && it.translationMode == TranslationMode.GEMINI_AI) {
            TranslationMode.OFFLINE
        } else {
            it.translationMode
        }
        it.copy(aiEnabled = enabled, translationMode = nextMode)
    }

    fun setOverlayEnabled(enabled: Boolean) = update { it.copy(overlayEnabled = enabled) }
    fun setBubbleSize(sizeDp: Float) = update { it.copy(bubbleSizeDp = sizeDp.coerceIn(44f, 80f)) }
    fun setBubbleOpacity(opacity: Float) = update { it.copy(bubbleOpacity = opacity.coerceIn(0.35f, 1f)) }
    fun resetBubblePosition() = update { it.copy(bubbleX = 24, bubbleY = 360) }
    fun setSaveHistoryImages(enabled: Boolean) = update { it.copy(saveHistoryImages = enabled) }
    fun setCustomMangaPromptNote(note: String) = update { it.copy(customMangaPromptNote = note.take(300)) }

    fun downloadOfflineModel(languageCode: String) {
        viewModelScope.launch {
            container.offlineTranslationEngine.downloadModel(languageCode, requireWifi = false)
        }
    }

    fun deleteOfflineModel(languageCode: String) {
        viewModelScope.launch {
            container.offlineTranslationEngine.deleteModel(languageCode)
        }
    }

    fun refreshOfflineModels() {
        viewModelScope.launch {
            container.offlineTranslationEngine.refreshModelStatuses()
        }
    }

    fun clearTranslationCache() {
        container.translationRouter.clearCache()
    }

    fun resetSettings() {
        viewModelScope.launch {
            cryptoErrorFlow.value = null
            container.settingsRepository.resetSettings()
            container.translationRouter.clearCache()
            container.mangaContextProcessor.clearContext()
        }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                cryptoErrorFlow.value = null
                container.settingsRepository.updateSettings(transform)
            } catch (e: KeystoreEncryptionException) {
                container.diagnosticsRepository.recordError(
                    "KeystoreCryptoManager",
                    "Encryption unavailable; custom note was not stored"
                )
                cryptoErrorFlow.value =
                    "ไม่สามารถเข้ารหัสข้อความแนะนำผ่าน Android Keystore ได้ ข้อมูลจึงไม่ถูกบันทึกเพื่อความปลอดภัย"
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(container) as T
                }
            }
    }
}
