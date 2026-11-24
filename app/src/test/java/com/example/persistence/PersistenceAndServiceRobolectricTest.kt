package com.example.persistence

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.capture.ScreenCaptureManager
import com.example.core.permissions.PermissionHelper
import com.example.core.security.KeystoreCryptoManager
import com.example.core.service.ScreenCaptureForegroundService
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.DiagnosticsRepositoryImpl
import com.example.data.repository.HistoryRepositoryImpl
import com.example.data.repository.SettingsRepositoryImpl
import com.example.domain.model.CaptureStatus
import com.example.domain.model.LanguageOption
import com.example.domain.model.TranslationHistoryItem
import com.example.domain.model.TranslationMode
import com.example.domain.model.TranslationProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersistenceAndServiceRobolectricTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `room history persistence supports insert search filter delete and clearAll`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = HistoryRepositoryImpl(db.translationHistoryDao())
            val id1 = repo.saveRecord(
                TranslationHistoryItem(
                    sourceText = "おはよう",
                    translatedText = "อรุณสวัสดิ์",
                    sourceLanguageCode = "ja",
                    targetLanguageCode = "th",
                    provider = TranslationProviderType.OFFLINE_MLKIT
                )
            )
            repo.saveRecord(
                TranslationHistoryItem(
                    sourceText = "안녕하세요",
                    translatedText = "สวัสดีครับ",
                    sourceLanguageCode = "ko",
                    targetLanguageCode = "th",
                    provider = TranslationProviderType.GEMINI_AI
                )
            )

            val all = repo.observeHistory().first()
            assertEquals(2, all.size)

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
        } finally {
            db.close()
        }
    }

    @Test
    fun `datastore settings persistence saves and resets preferences`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val rawDataStore = PreferenceDataStoreFactory.create(
            scope = dsScope,
            produceFile = { File(tempFolder.newFolder(), "settings_test.preferences_pb") }
        )
        try {
            val dataStore = SettingsDataStore(context, KeystoreCryptoManager(), rawDataStore)
            val repo = SettingsRepositoryImpl(dataStore)

            repo.updateSettings {
                it.copy(
                    sourceLanguage = LanguageOption.KOREAN,
                    translationMode = TranslationMode.OFFLINE,
                    autoCaptureDelayMs = 3500L,
                    aiEnabled = false,
                    bubbleOpacity = 0.65f
                )
            }

            val saved = repo.settingsFlow.first()
            assertEquals(LanguageOption.KOREAN, saved.sourceLanguage)
            assertEquals(TranslationMode.OFFLINE, saved.translationMode)
            assertEquals(3500L, saved.autoCaptureDelayMs)
            assertFalse(saved.aiEnabled)

            repo.resetSettings()
            val reset = repo.settingsFlow.first()
            assertEquals(TranslationMode.HYBRID, reset.translationMode)
            assertEquals(1500L, reset.autoCaptureDelayMs)
            assertTrue(reset.aiEnabled)
        } finally {
            dsScope.cancel()
        }
    }

    @Test
    fun `permission denial and missing capture session are handled gracefully`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val status = PermissionHelper.checkStatus(context, isMediaProjectionActive = false)
        assertFalse(status.isMediaProjectionActive)
        assertFalse(status.hasOverlayPermission)
        assertFalse(status.hasAccessibilityPermission)

        val manager = ScreenCaptureManager(context, DiagnosticsRepositoryImpl())
        val frameResult = manager.captureSingleFrame()
        assertTrue(frameResult.isFailure)
        manager.release()
    }

    @Test
    fun `foreground service stop command releases resources and resets status`() {
        val controller = Robolectric.buildService(ScreenCaptureForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(service, ScreenCaptureForegroundService::class.java).setAction(ScreenCaptureForegroundService.ACTION_STOP), 0, 1)
        controller.destroy()

        val app = ApplicationProvider.getApplicationContext<com.example.app.EterScreenApplication>()
        assertEquals(CaptureStatus.IDLE, app.container.translateScreenUseCase.state.value.captureStatus)
        assertFalse(app.container.screenCaptureManager.isSessionActive.value)
    }
}
