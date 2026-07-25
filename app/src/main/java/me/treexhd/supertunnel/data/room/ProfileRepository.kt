package me.treexhd.supertunnel.data.room

import android.content.Context
import me.treexhd.supertunnel.domain.model.PayloadConfig
import me.treexhd.supertunnel.domain.model.TunnelMode
import me.treexhd.supertunnel.domain.model.TunnelProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable profile repository. Secrets stay in SecretStore; only profile metadata is stored here. */
class ProfileRepository(context: Context) {
    private val preferences = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profiles = MutableStateFlow(load())
    fun observeAll(): Flow<List<TunnelProfile>> = profiles
    suspend fun get(id: String): TunnelProfile? = profiles.value.firstOrNull { it.id == id }
    /** Names are identifiers in the UI, so comparison is trim + case insensitive. */
    suspend fun save(profile: TunnelProfile): ProfileSaveResult {
        val normalizedName = profile.name.trim()
        if (normalizedName.isBlank()) return ProfileSaveResult.InvalidName
        if (profiles.value.any { it.id != profile.id && it.name.trim().equals(normalizedName, ignoreCase = true) }) {
            return ProfileSaveResult.DuplicateName
        }
        profiles.value = profiles.value.filterNot { it.id == profile.id } + profile.copy(name = normalizedName)
        persist()
        return ProfileSaveResult.Saved
    }
    suspend fun delete(id: String) { profiles.value = profiles.value.filterNot { it.id == id }; persist() }
    private fun load(): List<TunnelProfile> = preferences.getString("items", "[]")?.let { runCatching { json.decodeFromString<List<TunnelProfile>>(it) }.getOrDefault(emptyList()) }.orEmpty().map(::migrateWsGateway)
    private fun migrateWsGateway(profile: TunnelProfile): TunnelProfile {
        val tlsMode = profile.mode in setOf(TunnelMode.SSH_TLS, TunnelMode.SSH_TLS_PROXY, TunnelMode.SSH_TLS_PAYLOAD, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        val oldPayload = profile.payload
        val gatewayHost = oldPayload?.endpointHost?.takeIf { it.isNotBlank() }
            ?: profile.tls?.endpointHost?.takeIf { it.isNotBlank() }
            ?: profile.ssh.host.takeUnless { it == "127.0.0.1" }.orEmpty()
        val fallbackPort = if (tlsMode) profile.tls?.endpointPort ?: 443 else profile.ssh.port
        val gatewayPort = oldPayload?.endpointPort?.takeIf { it in 1..65535 } ?: fallbackPort
        val payload = (oldPayload ?: PayloadConfig()).copy(endpointHost = gatewayHost, endpointPort = gatewayPort, webSocket = true)
        val tls = if (tlsMode) (profile.tls ?: return profile.copy(ssh = profile.ssh.copy(host = "127.0.0.1", port = 22), payload = payload)).copy(endpointHost = gatewayHost, endpointPort = gatewayPort, insecure = true) else profile.tls
        return profile.copy(ssh = profile.ssh.copy(host = "127.0.0.1", port = 22), tls = tls, payload = payload)
    }
    private fun persist() { preferences.edit().putString("items", json.encodeToString(profiles.value)).commit() }
}

sealed interface ProfileSaveResult {
    data object Saved : ProfileSaveResult
    data object DuplicateName : ProfileSaveResult
    data object InvalidName : ProfileSaveResult
}
