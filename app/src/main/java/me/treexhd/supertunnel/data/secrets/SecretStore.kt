package me.treexhd.supertunnel.data.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secret values never enter Room profiles or logs. */
class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted-secrets", Context.MODE_PRIVATE)
    fun put(value: CharArray): String {
        val id = UUID.randomUUID().toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.concatToString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        preferences.edit().putString(id, encoded).apply()
        value.fill('\u0000')
        return id
    }
    fun get(id: String): CharArray? = preferences.getString(id, null)?.let { stored ->
        val raw = Base64.decode(stored, Base64.NO_WRAP); if (raw.size < 13) return@let null
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }.doFinal(raw.copyOfRange(12, raw.size)).toString(Charsets.UTF_8).toCharArray()
    }
    fun delete(id: String) = preferences.edit().remove(id).apply()
    fun clear() = preferences.edit().clear().apply()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ssh-tunnel-secrets", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ssh-tunnel-secrets", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
