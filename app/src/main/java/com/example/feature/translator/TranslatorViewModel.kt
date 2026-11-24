package com.example.feature.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.app.AppContainer
import com.example.core.permissions.PermissionStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureMode
import com.example.domain.model.ReadingDirection
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.TranslatorState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TranslatorUiState(
    val settings: AppSettings = AppSettings(),
    val translatorState: TranslatorState = TranslatorState(),
    val permissions: PermissionStatus = PermissionStatus()
)

class TranslatorViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<TranslatorUiState> = combine(
        container.settingsRepository.settingsFlow,
        container.translateScreenUseCase.state,
        container.permissionStatus
    ) { settings, translator, permissions ->
        TranslatorUiState(
            settings = settings,
            translatorState = translator,
            permissions = permissions
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TranslatorUiState()
    )

    fun setCaptureMode(mode: CaptureMode) {
        viewModelScope.launch {
            container.settingsRepository.updateSettings { it.copy(captureMode = mode) }
        }
    }

    fun setReadingDirection(direction: ReadingDirection) {
        viewModelScope.launch {
            container.settingsRepository.updateSettings { it.copy(readingDirection = direction) }
        }
    }

    fun updateEditableSource(newText: String) {
        container.translateScreenUseCase.updateEditableSourceText(newText)
    }

    fun translateEditedText(forceProvider: TranslationProviderType? = null) {
        val text = container.translateScreenUseCase.state.value.editableSourceText
        container.translateScreenUseCase.retranslateEditedText(
            editedText = text,
            settings = container.latestSettings,
            forceProvider = forceProvider,
            bypassCache = true
        )
    }

    fun triggerManualCapture() {
        container.triggerManualCapture()
    }

    fun pauseCapture() {
        container.pauseCapture()
    }

    fun resumeCapture() {
        container.resumeCapture()
    }

    fun stopCapture() {
        container.stopCaptureService()
    }

    fun cancelActiveJob() {
        container.translateScreenUseCase.cancelActiveTranslation()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TranslatorViewModel(container) as T
                }
            }
    }
}
