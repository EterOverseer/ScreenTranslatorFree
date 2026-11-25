package com.example.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.capture.AutoCaptureScheduler
import com.example.capture.ScreenCaptureManager
import com.example.core.edition.EditionConfig
import com.example.core.overlay.OverlayCaptureGuard
import com.example.core.permissions.PermissionHelper
import com.example.core.permissions.PermissionStatus
import com.example.core.security.FirebaseConfigStatus
import com.example.core.security.KeystoreCryptoManager
import com.example.core.security.inspectFirebaseStatus
import com.example.core.service.AutoScrollBridge
import com.example.core.service.ScreenCaptureForegroundService
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.DiagnosticsRepositoryImpl
import com.example.data.repository.HistoryRepositoryImpl
import com.example.data.repository.SettingsRepositoryImpl
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureStatus
import com.example.domain.repository.DiagnosticsRepository
import com.example.domain.repository.HistoryRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.usecase.TranslateScreenUseCase
import com.example.manga.MangaContextProcessor
import com.example.ocr.MlKitOcrEngine
import com.example.ocr.OcrEngine
import com.example.translation.GeminiTranslationEngine
import com.example.translation.OfflineTranslationEngine
import com.example.translation.TranslationRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppContainer(private val appContext: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val cryptoManager: KeystoreCryptoManager by lazy { KeystoreCryptoManager() }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(appContext, cryptoManager) }

    val historyRepository: HistoryRepository by lazy {
        HistoryRepositoryImpl(database.translationHistoryDao())
    }
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(settingsDataStore)
    }
    val diagnosticsRepository: DiagnosticsRepository by lazy {
        DiagnosticsRepositoryImpl()
    }

    @Volatile
    var latestSettings: AppSettings = AppSettings()
        private set

    val ocrEngine: OcrEngine by lazy { MlKitOcrEngine() }
    val mangaContextProcessor: MangaContextProcessor by lazy { MangaContextProcessor() }
    val offlineTranslationEngine: OfflineTranslationEngine by lazy {
        OfflineTranslationEngine(diagnosticsRepository)
    }
    val geminiTranslationEngine: GeminiTranslationEngine by lazy {
        GeminiTranslationEngine(
            diagnosticsRepository = diagnosticsRepository,
            customNoteProvider = { latestSettings.customMangaPromptNote },
            firebaseReadyProvider = { firebaseStatus.value.isFirebaseAppReady }
        )
    }
    val translationRouter: TranslationRouter by lazy {
        TranslationRouter(offlineTranslationEngine, geminiTranslationEngine)
    }

    val screenCaptureManager: ScreenCaptureManager by lazy {
        ScreenCaptureManager(appContext, diagnosticsRepository)
    }

    @Volatile
    private var activeOverlayCaptureGuard: OverlayCaptureGuard = OverlayCaptureGuard.NO_OP

    private val delegatingOverlayCaptureGuard = object : OverlayCaptureGuard {
        override suspend fun suspendForCapture() {
            activeOverlayCaptureGuard.suspendForCapture()
        }

        override suspend fun restoreAfterCapture() {
            activeOverlayCaptureGuard.restoreAfterCapture()
        }
    }

    fun registerOverlayCaptureGuard(guard: OverlayCaptureGuard?) {
        activeOverlayCaptureGuard = guard ?: OverlayCaptureGuard.NO_OP
    }

    val translateScreenUseCase: TranslateScreenUseCase by lazy {
        TranslateScreenUseCase(
            appContext = appContext,
            scope = appScope,
            ocrEngine = ocrEngine,
            mangaContextProcessor = mangaContextProcessor,
            translationRouter = translationRouter,
            historyRepository = historyRepository,
            diagnosticsRepository = diagnosticsRepository,
            overlayCaptureGuard = delegatingOverlayCaptureGuard
        )
    }

    val autoCaptureScheduler: AutoCaptureScheduler by lazy {
        AutoCaptureScheduler(
            scope = appScope,
            onScrollStarted = {
                translateScreenUseCase.clearTranslatedRegions()
            },
            onTriggerCapture = {
                if (screenCaptureManager.isSessionActive.value) {
                    translateScreenUseCase.processCapturedBitmap(
                        bitmapProvider = { screenCaptureManager.captureSingleFrame() },
                        settings = latestSettings,
                        forceProcess = false
                    )
                }
            }
        )
    }

    private val _permissionStatus = MutableStateFlow(PermissionStatus())
    val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()

    private val _firebaseStatus = MutableStateFlow(inspectFirebaseStatus(appContext))
    val firebaseStatus: StateFlow<FirebaseConfigStatus> = _firebaseStatus.asStateFlow()

    val isAutoScrollSupported: Boolean
        get() = EditionConfig.isAutoScrollSupported

    init {
        appScope.launch {
            settingsDataStore.migrateLegacySecrets()
            settingsRepository.settingsFlow.collect { settings ->
                latestSettings = settings
            }
        }
        appScope.launch {
            AutoScrollBridge.isConnected.collect { connected ->
                val effectiveConnected = EditionConfig.isAutoScrollSupported && connected
                translateScreenUseCase.updateAccessibilityStatus(effectiveConnected)
                refreshPermissions()
            }
        }
        appScope.launch {
            screenCaptureManager.isSessionActive.collect {
                refreshPermissions()
            }
        }
        appScope.launch {
            offlineTranslationEngine.refreshModelStatuses()
        }
    }

    fun updateFirebaseStatus(status: FirebaseConfigStatus) {
        _firebaseStatus.value = status
    }

    fun refreshFirebaseStatus() {
        _firebaseStatus.value = inspectFirebaseStatus(appContext)
    }

    fun refreshPermissions() {
        _permissionStatus.value = PermissionHelper.checkStatus(
            context = appContext,
            isMediaProjectionActive = screenCaptureManager.isSessionActive.value
        )
    }

    fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = ScreenCaptureForegroundService.startIntent(appContext, resultCode, data)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopCaptureService() {
        appContext.startService(ScreenCaptureForegroundService.stopIntent(appContext))
        translateScreenUseCase.updateCaptureStatus(CaptureStatus.IDLE)
    }

    fun triggerManualCapture() {
        if (!screenCaptureManager.isSessionActive.value) {
            translateScreenUseCase.updateCaptureStatus(CaptureStatus.ERROR)
            return
        }
        appContext.startService(ScreenCaptureForegroundService.manualCaptureIntent(appContext))
    }

    fun pauseCapture() {
        appContext.startService(ScreenCaptureForegroundService.pauseIntent(appContext))
    }

    fun resumeCapture() {
        appContext.startService(ScreenCaptureForegroundService.resumeIntent(appContext))
    }
}
