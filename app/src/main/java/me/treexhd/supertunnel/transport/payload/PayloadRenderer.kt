package me.treexhd.supertunnel.transport.payload

import me.treexhd.supertunnel.domain.model.Endpoint
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class PayloadContext(
    val ssh: Endpoint,
    val proxy: Endpoint? = null,
    val tls: Endpoint? = null,
    val sni: String = "",
    val userAgent: String = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36",
    val proxyUsername: String? = null,
    val proxyPassword: CharArray? = null,
    val method: String = "CONNECT",
    val protocol: String = "HTTP/1.1",
    val webSocketKey: String = newWebSocketKey()
)

/** delayBeforeMs is applied before this part is written, never after it. */
data class PayloadPart(val bytes: ByteArray, val delayBeforeMs: Long = 0)

object PayloadRenderer {
    private const val MAX_PAYLOAD_BYTES = 65_536
    private const val MAX_PARTS = 32
    private const val MAX_DELAY_MS = 5_000L
    private const val MAX_TOTAL_DELAY_MS = 15_000L
    private const val DEFAULT_DELAY_SPLIT_MS = 1_500L

    private val random = SecureRandom()
    private val rotatePositions = ConcurrentHashMap<String, AtomicInteger>()

    private val splitToken = Regex(
        """\[(?:split|instant_split|delay_split|split_delay|split=(\d+)|delay_split:(\d+))]"""
    )
    private val valueToken = Regex(
        """\[(?:method|host|port|host_port|ssh_host|ssh_port|proxy_host|proxy_port|tls_host|tls_port|sni|protocol|ua|user_agent|auth|netData|raw|real_raw|cr|lf|crlf|lfcr|crlf\*2|tab|ws_key|unix_time|random_uuid|random_alpha:\d+|random_numeric:\d+|random=(?:[^\[\]\r\n]|\[[^\]\r\n]*])*|rotate=(?:[^\[\]\r\n]|\[[^\]\r\n]*])*)]"""
    )

    fun render(template: String, context: PayloadContext): List<PayloadPart> {
        require(template.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "PAYLOAD_RENDER_FAILED: input too large"
        }

        val markers = splitToken.findAll(template).toList()
        require(markers.size + 1 <= MAX_PARTS) { "PAYLOAD_RENDER_FAILED: too many splits" }

        val result = ArrayList<PayloadPart>(markers.size + 1)
        var cursor = 0
        var nextDelay = 0L
        var totalDelay = 0L

        fun appendPart(source: String) {
            if (source.isEmpty()) return
            val rendered = valueToken.replace(source) { match -> expand(match.value, context) }
            val bytes = rendered.toByteArray(Charsets.US_ASCII)
            require(bytes.size <= MAX_PAYLOAD_BYTES) { "PAYLOAD_RENDER_FAILED: output too large" }
            result += PayloadPart(bytes, nextDelay)
            nextDelay = 0
        }

        markers.forEach { marker ->
            appendPart(template.substring(cursor, marker.range.first))
            val delay = splitDelay(marker)
            require(delay <= MAX_DELAY_MS) { "PAYLOAD_RENDER_FAILED: delay too long" }
            totalDelay += delay
            require(totalDelay <= MAX_TOTAL_DELAY_MS) { "PAYLOAD_RENDER_FAILED: total delay too long" }
            nextDelay += delay
            cursor = marker.range.last + 1
        }
        appendPart(template.substring(cursor))

        val outputBytes = result.sumOf { it.bytes.size }
        require(outputBytes <= MAX_PAYLOAD_BYTES) { "PAYLOAD_RENDER_FAILED: output too large" }
        return result
    }

    private fun splitDelay(marker: MatchResult): Long = when {
        marker.value.startsWith("[split=") -> marker.groups[1]?.value?.toLongOrNull() ?: 0
        marker.value.startsWith("[delay_split:") -> marker.groups[2]?.value?.toLongOrNull() ?: 0
        marker.value == "[delay_split]" || marker.value == "[split_delay]" -> DEFAULT_DELAY_SPLIT_MS
        else -> 0
    }

    private fun expand(value: String, c: PayloadContext): String = when (value) {
        "[method]" -> c.method
        "[host]", "[ssh_host]" -> c.ssh.host
        "[port]", "[ssh_port]" -> c.ssh.port.toString()
        "[host_port]" -> "${c.ssh.host}:${c.ssh.port}"
        "[proxy_host]" -> c.proxy?.host.orEmpty()
        "[proxy_port]" -> c.proxy?.port?.toString().orEmpty()
        "[tls_host]" -> c.tls?.host.orEmpty()
        "[tls_port]" -> c.tls?.port?.toString().orEmpty()
        "[sni]" -> c.sni
        "[protocol]" -> c.protocol
        "[ua]", "[user_agent]" -> c.userAgent
        "[auth]" -> proxyAuthorization(c)
        "[netData]" -> "${c.method} ${c.ssh.host}:${c.ssh.port} ${c.protocol}"
        "[raw]", "[real_raw]" -> "${c.method} ${c.ssh.host}:${c.ssh.port} HTTP/1.0\r\n\r\n"
        "[cr]" -> "\r"
        "[lf]" -> "\n"
        "[crlf]" -> "\r\n"
        "[lfcr]" -> "\n\r"
        "[crlf*2]" -> "\r\n\r\n"
        "[tab]" -> "\t"
        "[ws_key]" -> c.webSocketKey
        "[unix_time]" -> (System.currentTimeMillis() / 1000).toString()
        "[random_uuid]" -> UUID.randomUUID().toString()
        else -> when {
            value.startsWith("[random_alpha:") -> randomString(value, alpha = true)
            value.startsWith("[random_numeric:") -> randomString(value, alpha = false)
            value.startsWith("[random=") -> choose(value, rotate = false)
            value.startsWith("[rotate=") -> choose(value, rotate = true)
            else -> value
        }
    }

    private fun proxyAuthorization(c: PayloadContext): String {
        val username = c.proxyUsername ?: return ""
        val password = c.proxyPassword ?: return ""
        val raw = "$username:${password.concatToString()}".toByteArray(Charsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(raw)}"
    }

    private fun randomString(value: String, alpha: Boolean): String {
        val length = value.substringAfter(':').substringBefore(']').toInt()
        require(length in 1..1024) { "PAYLOAD_RENDER_FAILED: invalid random length" }
        return buildString(length) {
            repeat(length) {
                append(if (alpha) ('a'.code + random.nextInt(26)).toChar() else ('0'.code + random.nextInt(10)).toChar())
            }
        }
    }

    private fun choose(value: String, rotate: Boolean): String {
        val choices = value.substringAfter('=').dropLast(1).split(';')
        require(choices.isNotEmpty() && choices.size <= 256 && choices.none(String::isEmpty)) {
            "PAYLOAD_RENDER_FAILED: invalid ${if (rotate) "rotate" else "random"} choices"
        }
        if (!rotate) return choices[random.nextInt(choices.size)]
        val position = rotatePositions.computeIfAbsent(value) { AtomicInteger() }.getAndUpdate {
            if (it == Int.MAX_VALUE) 0 else it + 1
        }
        return choices[Math.floorMod(position, choices.size)]
    }

    internal fun resetRotationForTests() = rotatePositions.clear()
}

private fun newWebSocketKey(): String =
    Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
