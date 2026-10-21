// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AuthSessionStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** Only ciphertext is persisted, outside Android backup and device-transfer directories. */
class KeystoreSessionStore(context: Context) : AuthSessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "auth-session.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    override fun read(): String? = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized null
        require(file.baseFile.length() in 29..MAX_BYTES.toLong())
        val bytes = file.readFully()
        require(bytes.size in 29..MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    override fun write(value: String) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        require(bytes.size <= MAX_BYTES)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    override fun clear() = synchronized(lock) { file.delete() }

    companion object {
        private const val ALIAS = "stylemate.auth.session.v1"
        private const val MAX_BYTES = 64 * 1024
        private val lock = Any()
    }
}
