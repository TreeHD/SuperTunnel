package me.treexhd.supertunnel.transport.payload

import me.treexhd.supertunnel.domain.model.PayloadConfig
import me.treexhd.supertunnel.service.TunnelLogBook
import me.treexhd.supertunnel.transport.core.DuplexConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class PayloadHandshakeResult(
    val responseHeader: String?,
    val webSocketKey: String,
    val definesWebSocketUpgrade: Boolean
)

/** Sends the user-visible raw payload exactly once; no generator performs hidden rewrites. */
object PayloadHandshakeLayer {
    suspend fun apply(
        connection: DuplexConnection,
        config: PayloadConfig,
        context: PayloadContext
    ): PayloadHandshakeResult = withContext(Dispatchers.IO) {
        val parts = PayloadRenderer.render(config.raw, context)
        require(parts.isNotEmpty()) { "PAYLOAD_RENDER_FAILED: payload is empty" }
        TunnelLogBook.add(
            "payload plan: ${parts.size} write(s), ${parts.sumOf { it.bytes.size }} bytes, " +
                "${parts.sumOf { it.delayBeforeMs }} ms delay"
        )
        parts.forEachIndexed { index, part ->
            if (part.delayBeforeMs > 0) {
                TunnelLogBook.add("payload split ${index + 1}/${parts.size}: wait ${part.delayBeforeMs} ms")
                delay(part.delayBeforeMs)
            }
            connection.output.write(part.bytes)
            connection.output.flush()
            TunnelLogBook.add("payload split ${index + 1}/${parts.size}: sent ${part.bytes.size} bytes")
        }

        val response = if (config.waitForResponse) readHeader(connection, 64 * 1024) else null
        val status = response?.lineSequence()?.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
        if (response != null) {
            TunnelLogBook.add("payload response: HTTP ${status ?: "invalid"} (${response.toByteArray(Charsets.ISO_8859_1).size} header bytes)")
            if (!config.continueOnAnyStatus && status !in 200..299 && status != 101) {
                throw PayloadException("PAYLOAD_RESPONSE_REJECTED: ${status ?: "invalid"}")
            }
        } else {
            TunnelLogBook.add("payload response: not requested")
        }

        PayloadHandshakeResult(
            responseHeader = response,
            webSocketKey = context.webSocketKey,
            definesWebSocketUpgrade = definesWebSocketUpgrade(config.raw)
        )
    }

    private fun definesWebSocketUpgrade(template: String): Boolean =
        template.contains("[ws_key]") ||
            Regex("""(?im)^\s*Upgrade\s*:\s*websocket\s*$""").containsMatchIn(template) ||
            Regex("""(?im)^\s*Sec-WebSocket-Key\s*:""").containsMatchIn(template)

    private fun readHeader(connection: DuplexConnection, max: Int): String {
        val out = ArrayList<Byte>()
        var matched = 0
        val terminator = "\r\n\r\n"
        while (out.size < max) {
            val value = connection.input.read()
            if (value < 0) throw PayloadException("PAYLOAD_RESPONSE_EOF")
            out += value.toByte()
            matched = if (value == terminator[matched].code) matched + 1 else if (value == '\r'.code) 1 else 0
            if (matched == 4) return out.toByteArray().toString(Charsets.ISO_8859_1)
        }
        throw PayloadException("PAYLOAD_RESPONSE_TOO_LARGE")
    }
}

class PayloadException(message: String) : Exception(message)
