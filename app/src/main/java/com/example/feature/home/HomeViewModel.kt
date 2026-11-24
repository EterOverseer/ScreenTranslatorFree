package com.example.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.app.AppContainer
import com.example.core.edition.EditionConfig
import com.example.core.permissions.PermissionStatus
import com.example.core.security.FirebaseConfigStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.OfflineModelInfo
import com.example.domain.model.TranslatorState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val settings: AppSettings = AppSettings(),
    val translatorState: TranslatorState = TranslatorState(),
    val permissions: PermissionStatus = PermissionStatus(),
    val offlineModels: List<OfflineModelInfo> = emptyList(),
    val firebaseStatus: FirebaseConfigStatus = FirebaseConfigStatus(),
    val isAutoScrollSupported: Boolean = EditionConfig.isAutoScrollSupported,
    val editionName: String = EditionConfig.editionDisplayName,
    val showAiConsentDialog: Boolean = false
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        container.settingsRepository.settingsFlow,
        container.translateScreenUseCase.state,
        container.permissionStatus,
        container.offlineTranslationEngine.modelsFlow,
        container.firebaseStatus
    ) { settings, translator, permissions, models, firebaseStatus ->
        HomeUiState(
            settings = settings,
            translatorState = translator,
            permissions = permissions,
            offlineModels = models,
            firebaseStatus = firebaseStatus,
            isAutoScrollSupported = container.isAutoScrollSupported,
            editionName = EditionConfig.editionDisplayName,
            showAiConsentDialog = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun refreshStatuses() {
        container.refreshPermissions()
        container.refreshFirebaseStatus()
        viewModelScope.launch {
            container.offlineTranslationEngine.refreshModelStatuses()
        }
    }

    fun acknowledgeAiConsent() {
        viewModelScope.launch {
            container.settingsRepository.updateSettings { it.copy(aiDataNoticeAccepted = true) }
        }
    }

    fun stopTranslationSession() {
        container.stopCaptureService()
    }

    fun triggerQuickCapture() {
        container.triggerManualCapture()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(container) as T
                }
            }
    }
}
