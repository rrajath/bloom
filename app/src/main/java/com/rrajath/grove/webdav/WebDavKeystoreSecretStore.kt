// i18n: keystore constants and internal error text, never shown as UI text
package com.rrajath.grove.webdav

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
 * Stores the WebDAV password in an app-private file, encrypted with an
 * AES-256/GCM key held in Android Keystore (minSdk 23).
 *
 * The key never leaves the device and is excluded from backups, so a cloud
 * backup or device-to-device transfer cannot carry a usable password: after a
 * restore [load] returns null and the user simply re-enters it once. The file
 * itself is also excluded from backup rules.
 */
class WebDavKeystoreSecretStore(context: Context) : WebDavSecretStore {

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
            throw WebDavException("Could not store the WebDAV password: ${e.message}", e)
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
            // Restored ciphertext without its Keystore key (or a corrupt file):
            // drop it so the settings screen asks for the password again.
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
        private const val ALIAS = "bloom_webdav_v1"
        private const val SECRET_PATH = "webdav/secret.bin"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
