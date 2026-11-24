package com.example.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCryptoManager(
    private val keyAlias: String = "eterscreen_config_aes_key"
) {
    private val transformation = "AES/GCM/NoPadding"
    private val tagLengthBits = 128
    private val ivSizeBytes = 12

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val secretKey = getOrCreateSecretKey() ?: return plainText
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            "enc:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore encryption unavailable; storing raw value", e)
            plainText
        }
    }

    fun decrypt(storedValue: String): String {
        if (storedValue.isEmpty()) return ""
        if (!storedValue.startsWith("enc:")) return storedValue
        return try {
            val payload = Base64.decode(storedValue.removePrefix("enc:"), Base64.NO_WRAP)
            if (payload.size <= ivSizeBytes) return ""
            val iv = payload.copyOfRange(0, ivSizeBytes)
            val cipherText = payload.copyOfRange(ivSizeBytes, payload.size)
            val secretKey = getOrCreateSecretKey() ?: return ""
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(tagLengthBits, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore decryption failed", e)
            ""
        }
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val existing = keyStore.getKey(keyAlias, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore unavailable in current runtime", e)
            null
        }
    }

    companion object {
        private const val TAG = "KeystoreCryptoManager"
    }
}
