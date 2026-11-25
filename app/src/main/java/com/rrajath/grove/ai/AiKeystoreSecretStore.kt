// i18n: keystore constants and internal error text, never shown as UI text
package com.rrajath.grove.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key at rest: AES-256/GCM with a key held in Android Keystore (minSdk 23),
 * ciphertext (IV + bytes) in `filesDir/ai/secret.bin`. The directory is
 * excluded from cloud backup and device transfer in both backup rule files.
 *
 * A restored ciphertext whose Keystore key is gone is dropped, and the settings
 * screen simply asks for the key again — same behaviour as the WebDAV password.
 */
class AiKeystoreSecretStore(context: Context) : AiSecretStore {

    private val file = File(context.filesDir, SECRET_PATH)

    override fun save(secret: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val payload = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            file.parentFile?.mkdirs()
            file.writeText(Base64.encodeToString(payload, Base64.NO_WRAP))
        } catch (e: Exception) {
            file.delete()
            throw AiException("Could not store the API key: ${e.message}", cause = e)
        }
    }

    override fun load(): String? {
        val encoded = file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
            ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= IV_SIZE) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, payload, 0, IV_SIZE),
            )
            String(cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    override fun clear() {
        file.delete()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "bloom_ai_v1"
        private const val SECRET_PATH = "ai/secret.bin"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
