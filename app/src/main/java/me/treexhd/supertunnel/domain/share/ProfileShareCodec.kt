package me.treexhd.supertunnel.domain.share

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.treexhd.supertunnel.domain.model.TunnelProfile

/** Clipboard/share format. Secrets themselves are never serialized; only opaque local secret IDs are. */
object ProfileShareCodec {
    private const val PREFIX = "spnt:v1:"
    private const val MAX_DECODED_BYTES = 256 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(profile: TunnelProfile): String {
        val payload = json.encodeToString(profile).toByteArray(StandardCharsets.UTF_8)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    fun decode(value: String): Result<TunnelProfile> = runCatching {
        val compact = value.trim()
        require(compact.startsWith(PREFIX, ignoreCase = true)) { "Not a SuperTunnel profile URL" }
        val encoded = compact.substring(PREFIX.length)
        require(encoded.isNotBlank()) { "Profile data is empty" }
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size <= MAX_DECODED_BYTES) { "Profile data is too large" }
        json.decodeFromString<TunnelProfile>(String(bytes, StandardCharsets.UTF_8)).copy(id = UUID.randomUUID().toString())
    }
}
