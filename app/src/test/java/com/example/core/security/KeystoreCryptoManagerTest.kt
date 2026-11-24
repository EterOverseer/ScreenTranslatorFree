package com.example.core.security

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeystoreCryptoManagerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun generateTestAesKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return generator.generateKey()
    }

    private fun inMemoryKeyProvider(
        initialKey: SecretKey? = generateTestAesKey(),
        failCreate: Boolean = false
    ): SecretKeyProvider = object : SecretKeyProvider {
        private var key: SecretKey? = initialKey

        override fun getOrCreateKey(): SecretKey {
            if (failCreate) {
                throw KeystoreEncryptionException("Simulated Keystore unavailable")
            }
            return key ?: generateTestAesKey().also { key = it }
        }

        override fun getExistingKey(): SecretKey? = key
    }

    @Test
    fun `encrypt and decrypt round trip succeeds and produces non-plaintext ciphertext`() {
        val manager = KeystoreCryptoManager(keyProvider = inMemoryKeyProvider())
        val secretPrompt = "Translate Senpai cleanly with natural Thai honorifics"

        val cipherText1 = manager.encrypt(secretPrompt)
        val cipherText2 = manager.encrypt(secretPrompt)

        assertTrue(manager.isEncryptedFormat(cipherText1))
        assertFalse(cipherText1.contains(secretPrompt))
        assertNotEquals(cipherText1, cipherText2)
        assertEquals(secretPrompt, manager.decrypt(cipherText1))
        assertEquals(secretPrompt, manager.decrypt(cipherText2))
    }

    @Test
    fun `invalid or tampered ciphertext throws KeystoreDecryptionException without fallback`() {
        val manager = KeystoreCryptoManager(keyProvider = inMemoryKeyProvider())
        val valid = manager.encrypt("Top secret glossary")

        val truncatedPayload = KeystoreCryptoManager.CIPHER_PREFIX +
            Base64.encodeToString(ByteArray(10) { 1 }, Base64.NO_WRAP)

        val tamperedBytes = Base64.decode(
            valid.removePrefix(KeystoreCryptoManager.CIPHER_PREFIX),
            Base64.NO_WRAP
        ).also { bytes ->
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0xFF).toByte()
        }
        val tampered = KeystoreCryptoManager.CIPHER_PREFIX +
            Base64.encodeToString(tamperedBytes, Base64.NO_WRAP)

        for (badInput in listOf(truncatedPayload, tampered, "enc:not-valid-base64!!!")) {
            try {
                manager.decrypt(badInput)
                fail("Expected KeystoreDecryptionException for $badInput")
            } catch (_: KeystoreDecryptionException) {
                // Expected fail-closed behavior
            }
        }
    }

    @Test
    fun `missing or unavailable key fails closed on decrypt and encrypt`() {
        val missingKeyProvider = inMemoryKeyProvider(initialKey = null, failCreate = true)
        val managerWithoutKey = KeystoreCryptoManager(keyProvider = missingKeyProvider)

        val validWithOtherKey = KeystoreCryptoManager(keyProvider = inMemoryKeyProvider())
            .encrypt("Character note")

        try {
            managerWithoutKey.decrypt(validWithOtherKey)
            fail("Expected KeystoreDecryptionException when key is missing")
        } catch (_: KeystoreDecryptionException) {
            // Expected
        }

        try {
            managerWithoutKey.encrypt("Sensitive note")
            fail("Expected KeystoreEncryptionException when key generation is unavailable")
        } catch (e: KeystoreEncryptionException) {
            assertFalse(e.message.orEmpty().contains("Sensitive note"))
        }
    }

    @Test
    fun `cipher failure during encryption never returns plaintext`() {
        val sensitive = "DoNotLeakThisPlaintextSecret"
        val failingManager = KeystoreCryptoManager(
            keyProvider = inMemoryKeyProvider(),
            cipherFactory = { throw GeneralSecurityException("Hardware Keymaster error") }
        )

        try {
            val result = failingManager.encrypt(sensitive)
            fail("Expected KeystoreEncryptionException, got $result")
        } catch (e: KeystoreEncryptionException) {
            assertFalse(e.message.orEmpty().contains(sensitive))
        }
    }

    @Test
    fun `legacy plaintext values are rejected on decrypt and safely re-encrypted on migrate`() {
        val manager = KeystoreCryptoManager(keyProvider = inMemoryKeyProvider())
        val legacyPlaintext = "legacy-unencrypted-note"

        try {
            manager.decrypt(legacyPlaintext)
            fail("Expected KeystoreDecryptionException for legacy plaintext")
        } catch (_: KeystoreDecryptionException) {
            // Expected: legacy plaintext must never be returned by decrypt()
        }

        val migrated = manager.migrateLegacyValue(legacyPlaintext)
        assertTrue(manager.isEncryptedFormat(migrated))
        assertNotEquals(legacyPlaintext, migrated)
        assertEquals(legacyPlaintext, manager.decrypt(migrated))

        val brokenManager = KeystoreCryptoManager(
            keyProvider = inMemoryKeyProvider(initialKey = null, failCreate = true)
        )
        try {
            brokenManager.migrateLegacyValue(legacyPlaintext)
            fail("Expected KeystoreEncryptionException when migration cannot encrypt")
        } catch (_: KeystoreEncryptionException) {
            // Expected fail-closed migration
        }
    }

    @Test
    fun `SettingsDataStore aborts write and migrates legacy plaintext safely without plaintext fallback`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val testFile = File(tempFolder.newFolder(), "keystore_test.preferences_pb")
        val rawDataStore = PreferenceDataStoreFactory.create(
            scope = dsScope,
            produceFile = { testFile }
        )
        try {
            val workingCrypto = KeystoreCryptoManager(keyProvider = inMemoryKeyProvider())
            val dataStoreOk = SettingsDataStore(context, workingCrypto, rawDataStore)
            dataStoreOk.update { it.copy(customMangaPromptNote = "Initial encrypted note") }
            assertEquals("Initial encrypted note", dataStoreOk.settingsFlow.first().customMangaPromptNote)

            val failingCrypto = KeystoreCryptoManager(
                keyProvider = inMemoryKeyProvider(initialKey = null, failCreate = true)
            )
            val dataStoreFailing = SettingsDataStore(context, failingCrypto, rawDataStore)
            try {
                dataStoreFailing.update { it.copy(customMangaPromptNote = "PlaintextMustNotPersist") }
                fail("Expected KeystoreEncryptionException")
            } catch (_: KeystoreEncryptionException) {
                // Expected
            }

            // Original encrypted setting remains intact and new plaintext was never persisted
            assertEquals("Initial encrypted note", dataStoreOk.settingsFlow.first().customMangaPromptNote)

            // Simulate legacy plaintext in DataStore and verify migrateLegacySecrets() re-encrypts it
            val legacyKey = stringPreferencesKey("encrypted_custom_manga_prompt")
            rawDataStore.edit { it[legacyKey] = "legacy-note-on-disk" }
            dataStoreOk.migrateLegacySecrets()
            val storedAfterMigration = rawDataStore.data.first()[legacyKey].orEmpty()
            assertTrue(workingCrypto.isEncryptedFormat(storedAfterMigration))
            assertEquals("legacy-note-on-disk", dataStoreOk.settingsFlow.first().customMangaPromptNote)

            // If encryption fails during legacy migration, unencrypted value is purged instead of kept
            rawDataStore.edit { it[legacyKey] = "legacy-must-be-cleared" }
            dataStoreFailing.migrateLegacySecrets()
            assertEquals("", rawDataStore.data.first()[legacyKey].orEmpty())
        } finally {
            dsScope.cancel()
        }
    }
}
