package me.treexhd.supertunnel.data.knownhosts

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

sealed interface HostKeyDecision { data object Trusted : HostKeyDecision; data class Unknown(val fingerprint: String) : HostKeyDecision; data class Changed(val oldFingerprint: String, val newFingerprint: String) : HostKeyDecision }

/** TOFU store. An unknown key is surfaced to UI; it is never automatically accepted. */
class KnownHostsStore(context: Context) {
    private val preferences = context.getSharedPreferences("known-hosts", Context.MODE_PRIVATE)
    fun verify(host: String, port: Int, algorithm: String, key: ByteArray): HostKeyDecision {
        val fingerprint = fingerprint(key); val id = "$host:$port:$algorithm"; val previous = preferences.getString(id, null)
        return when {
            previous == null -> { preferences.edit().putString("pending:$id", Base64.encodeToString(key, Base64.NO_WRAP)).apply(); HostKeyDecision.Unknown(fingerprint) }
            previous == fingerprint -> HostKeyDecision.Trusted
            else -> HostKeyDecision.Changed(previous, fingerprint)
        }
    }
    fun trust(host: String, port: Int, algorithm: String, key: ByteArray) { preferences.edit().putString("$host:$port:$algorithm", fingerprint(key)).apply() }
    fun remove(host: String, port: Int, algorithm: String) { preferences.edit().remove("$host:$port:$algorithm").apply() }
    fun trustPending(host: String, port: Int): Boolean {
        val pending = preferences.all.filterKeys { it.startsWith("pending:$host:$port:") }
        if (pending.isEmpty()) return false
        val editor = preferences.edit()
        pending.forEach { (id, encoded) ->
            val key = Base64.decode(encoded as String, Base64.NO_WRAP)
            editor.putString(id.removePrefix("pending:"), fingerprint(key)).remove(id)
        }
        return editor.commit()
    }
    private fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP)
}
