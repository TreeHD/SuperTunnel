package me.treexhd.supertunnel.transport.proxy

import me.treexhd.supertunnel.domain.model.Endpoint
import me.treexhd.supertunnel.domain.model.HttpProxyConfig
import me.treexhd.supertunnel.transport.core.DuplexConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

class ProxyConnectException(message: String) : Exception(message)

object HttpConnectLayer {
    suspend fun connect(upstream: DuplexConnection, target: Endpoint, proxy: HttpProxyConfig, password: CharArray? = null): DuplexConnection = withContext(Dispatchers.IO) {
        val authority = "${target.host}:${target.port}"
        val auth = if (proxy.username != null && password != null) basicAuthorization(proxy.username, password) else ""
        val request = buildString {
            append("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\nProxy-Connection: keep-alive\r\nConnection: keep-alive\r\n")
            append(auth)
            append("\r\n")
        }
        upstream.output.write(request.toByteArray(Charsets.US_ASCII)); upstream.output.flush()
        val header = readHeader(upstream, 64 * 1024)
        val status = header.lineSequence().firstOrNull().orEmpty().split(' ').getOrNull(1)?.toIntOrNull()
        when {
            status == 407 -> throw ProxyConnectException("PROXY_AUTH_REQUIRED")
            status !in 200..299 -> throw ProxyConnectException("PROXY_CONNECT_REJECTED: ${status ?: "invalid response"}")
        }
        upstream
    }

    fun basicAuthorization(username: String, password: CharArray): String {
        val raw = "$username:${password.concatToString()}".toByteArray(Charsets.UTF_8)
        return "Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString(raw)}\r\n"
    }

    internal fun readHeader(connection: DuplexConnection, maxBytes: Int): String {
        val bytes = ArrayList<Byte>(1024); var matched = 0
        while (bytes.size < maxBytes) {
            val current = connection.input.read()
            if (current < 0) throw ProxyConnectException("PROXY_RESPONSE_EOF")
            bytes += current.toByte()
            matched = if (current == "\r\n\r\n"[matched].code) matched + 1 else if (current == '\r'.code) 1 else 0
            if (matched == 4) return bytes.toByteArray().toString(Charsets.ISO_8859_1)
        }
        throw ProxyConnectException("PROXY_RESPONSE_TOO_LARGE")
    }
}
