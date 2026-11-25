package com.example.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.security.KeystoreCryptoManager
import com.example.core.security.KeystoreDecryptionException
import com.example.core.security.KeystoreEncryptionException
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureMode
import com.example.domain.model.HybridPriority
import com.example.domain.model.LanguageOption
import com.example.domain.model.ReadingDirection
import com.example.domain.model.TranslationBackgroundTreatment
import com.example.domain.model.TranslationDisplayMode
import com.example.domain.model.TranslationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "eterscreen_settings"
)

class SettingsDataStore(
    private val context: Context,
    private val cryptoManager: KeystoreCryptoManager,
    private val dataStore: DataStore<Preferences> = context.settingsDataStore
) {
    private object Keys {
        val SOURCE_LANG = stringPreferencesKey("source_language")
        val TARGET_LANG = stringPreferencesKey("target_language")
        val TRANSLATION_MODE = stringPreferencesKey("translation_mode")
        val HYBRID_PRIORITY = stringPreferencesKey("hybrid_priority")
        val CAPTURE_MODE = stringPreferencesKey("capture_mode")
        val READING_DIRECTION = stringPreferencesKey("reading_direction")
        val AUTO_DELAY_MS = longPreferencesKey("auto_capture_delay_ms")
        val COOLDOWN_MS = longPreferencesKey("cooldown_ms")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_DATA_NOTICE_ACCEPTED = booleanPreferencesKey("ai_data_notice_accepted")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val BUBBLE_SIZE_DP = floatPreferencesKey("bubble_size_dp")
        val BUBBLE_OPACITY = floatPreferencesKey("bubble_opacity")
        val BUBBLE_X = intPreferencesKey("bubble_x")
        val BUBBLE_Y = intPreferencesKey("bubble_y")
        val SAVE_HISTORY_IMAGES = booleanPreferencesKey("save_history_images")
        val ENCRYPTED_CUSTOM_PROMPT = stringPreferencesKey("encrypted_custom_manga_prompt")
        val TRANSLATION_DISPLAY_MODE = stringPreferencesKey("translation_display_mode")
        val TRANSLATION_BACKGROUND_TREATMENT = stringPreferencesKey("translation_background_treatment")
    }

    val settingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        buildAppSettings(prefs)
    }

    /**
     * Migrates any legacy unencrypted custom prompt value to AES-GCM ciphertext on startup.
     * If encryption fails, clears the unencrypted entry rather than leaving plaintext on disk.
     */
    suspend fun migrateLegacySecrets() {
        dataStore.edit { prefs ->
            val raw = prefs[Keys.ENCRYPTED_CUSTOM_PROMPT].orEmpty()
            if (raw.isNotEmpty() && !cryptoManager.isEncryptedFormat(raw)) {
                try {
                    prefs[Keys.ENCRYPTED_CUSTOM_PROMPT] = cryptoManager.migrateLegacyValue(raw)
                } catch (e: KeystoreEncryptionException) {
                    Log.w(TAG, "Legacy secret migration failed; clearing unencrypted value")
                    prefs.remove(Keys.ENCRYPTED_CUSTOM_PROMPT)
                } catch (e: KeystoreDecryptionException) {
                    prefs.remove(Keys.ENCRYPTED_CUSTOM_PROMPT)
                }
            }
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = buildAppSettings(prefs)
            val updated = transform(current)

            // Encrypt custom note BEFORE mutating preferences so fail-closed errors never write plaintext
            val encryptedNote = if (updated.customMangaPromptNote.isEmpty()) {
                ""
            } else {
                cryptoManager.encrypt(updated.customMangaPromptNote)
            }

            prefs[Keys.SOURCE_LANG] = updated.sourceLanguage.code
            prefs[Keys.TARGET_LANG] = updated.targetLanguage.code
            prefs[Keys.TRANSLATION_MODE] = updated.translationMode.name
            prefs[Keys.HYBRID_PRIORITY] = updated.hybridPriority.name
            prefs[Keys.CAPTURE_MODE] = updated.captureMode.name
            prefs[Keys.READING_DIRECTION] = updated.readingDirection.name
            prefs[Keys.AUTO_DELAY_MS] = updated.autoCaptureDelayMs.coerceIn(1000L, 5000L)
            prefs[Keys.COOLDOWN_MS] = updated.cooldownMs.coerceIn(1000L, 10000L)
            prefs[Keys.AI_ENABLED] = updated.aiEnabled
            prefs[Keys.AI_DATA_NOTICE_ACCEPTED] = updated.aiDataNoticeAccepted
            prefs[Keys.OVERLAY_ENABLED] = updated.overlayEnabled
            prefs[Keys.BUBBLE_SIZE_DP] = updated.bubbleSizeDp.coerceIn(44f, 80f)
            prefs[Keys.BUBBLE_OPACITY] = updated.bubbleOpacity.coerceIn(0.35f, 1.0f)
            prefs[Keys.BUBBLE_X] = updated.bubbleX
            prefs[Keys.BUBBLE_Y] = updated.bubbleY
            prefs[Keys.SAVE_HISTORY_IMAGES] = updated.saveHistoryImages
            prefs[Keys.ENCRYPTED_CUSTOM_PROMPT] = encryptedNote
            prefs[Keys.TRANSLATION_DISPLAY_MODE] = updated.translationDisplayMode.name
            prefs[Keys.TRANSLATION_BACKGROUND_TREATMENT] = updated.translationBackgroundTreatment.name
        }
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    private fun buildAppSettings(prefs: Preferences): AppSettings {
        val rawNote = prefs[Keys.ENCRYPTED_CUSTOM_PROMPT].orEmpty()
        val decryptedNote = if (rawNote.isEmpty()) {
            ""
        } else {
            try {
                cryptoManager.decrypt(rawNote)
            } catch (e: KeystoreDecryptionException) {
                Log.w(TAG, "Unable to decrypt stored custom note; ignoring stored entry")
                ""
            }
        }

        return AppSettings(
            sourceLanguage = LanguageOption.fromCode(prefs[Keys.SOURCE_LANG]),
            targetLanguage = prefs[Keys.TARGET_LANG]?.let { LanguageOption.fromCode(it) } ?: LanguageOption.THAI,
            translationMode = enumOrDefault(prefs[Keys.TRANSLATION_MODE], TranslationMode.HYBRID),
            hybridPriority = enumOrDefault(prefs[Keys.HYBRID_PRIORITY], HybridPriority.OFFLINE_FIRST),
            captureMode = enumOrDefault(prefs[Keys.CAPTURE_MODE], CaptureMode.BOTH),
            readingDirection = enumOrDefault(prefs[Keys.READING_DIRECTION], ReadingDirection.AUTO),
            autoCaptureDelayMs = (prefs[Keys.AUTO_DELAY_MS] ?: 1500L).coerceIn(1000L, 5000L),
            cooldownMs = (prefs[Keys.COOLDOWN_MS] ?: 2500L).coerceIn(1000L, 10000L),
            aiEnabled = prefs[Keys.AI_ENABLED] ?: true,
            aiDataNoticeAccepted = prefs[Keys.AI_DATA_NOTICE_ACCEPTED] ?: false,
            overlayEnabled = prefs[Keys.OVERLAY_ENABLED] ?: true,
            bubbleSizeDp = (prefs[Keys.BUBBLE_SIZE_DP] ?: 56f).coerceIn(44f, 80f),
            bubbleOpacity = (prefs[Keys.BUBBLE_OPACITY] ?: 0.92f).coerceIn(0.35f, 1.0f),
            bubbleX = prefs[Keys.BUBBLE_X] ?: 24,
            bubbleY = prefs[Keys.BUBBLE_Y] ?: 360,
            saveHistoryImages = prefs[Keys.SAVE_HISTORY_IMAGES] ?: false,
            customMangaPromptNote = decryptedNote,
            translationDisplayMode = enumOrDefault(
                prefs[Keys.TRANSLATION_DISPLAY_MODE],
                TranslationDisplayMode.REPLACE
            ),
            translationBackgroundTreatment = enumOrDefault(
                prefs[Keys.TRANSLATION_BACKGROUND_TREATMENT],
                TranslationBackgroundTreatment.BUBBLE
            )
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T {
        if (raw.isNullOrBlank()) return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    companion object {
        private const val TAG = "SettingsDataStore"
    }
}
