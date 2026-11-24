package com.example.feature.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.app.AppContainer
import com.example.core.edition.EditionConfig
import com.example.core.permissions.PermissionStatus
import com.example.core.security.FirebaseConfigStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.DiagnosticErrorEntry
import com.example.domain.model.OfflineModelInfo
import com.example.domain.model.TranslationMode
import com.example.domain.model.TranslatorState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PrivacyUiState(
    val settings: AppSettings = AppSettings(),
    val translatorState: TranslatorState = TranslatorState(),
    val permissions: PermissionStatus = PermissionStatus(),
    val offlineModels: List<OfflineModelInfo> = emptyList(),
    val recentErrors: List<DiagnosticErrorEntry> = emptyList(),
    val firebaseStatus: FirebaseConfigStatus = FirebaseConfigStatus(),
    val isAutoScrollSupported: Boolean = EditionConfig.isAutoScrollSupported,
    val editionName: String = EditionConfig.editionDisplayName
) {
    val isNetworkTransmissionActive: Boolean
        get() = settings.aiEnabled && settings.translationMode != TranslationMode.OFFLINE
}

class PrivacyViewModel(private val container: AppContainer) : ViewModel() {

    private val baseState = combine(
        container.settingsRepository.settingsFlow,
        container.translateScreenUseCase.state,
        container.permissionStatus,
        container.offlineTranslationEngine.modelsFlow,
        container.diagnosticsRepository.errorsFlow
    ) { settings, translator, permissions, models, errors ->
        PrivacyUiState(
            settings = settings,
            translatorState = translator,
            permissions = permissions,
            offlineModels = models,
            recentErrors = errors,
            isAutoScrollSupported = container.isAutoScrollSupported,
            editionName = EditionConfig.editionDisplayName
        )
    }

    val uiState: StateFlow<PrivacyUiState> = combine(
        baseState,
        container.firebaseStatus
    ) { state, firebaseStatus ->
        state.copy(firebaseStatus = firebaseStatus)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PrivacyUiState()
    )

    fun refreshDiagnostics() {
        container.refreshPermissions()
        container.refreshFirebaseStatus()
        viewModelScope.launch {
            container.offlineTranslationEngine.refreshModelStatuses()
        }
    }

    fun clearErrors() {
        container.diagnosticsRepository.clearErrors()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PrivacyViewModel(container) as T
                }
            }
    }
}
