package me.treexhd.supertunnel.transport.ssh

import android.net.Network
import android.net.VpnService
import me.treexhd.supertunnel.domain.model.*
import me.treexhd.supertunnel.transport.core.DuplexConnection
import me.treexhd.supertunnel.transport.core.StreamDuplexConnection
import me.treexhd.supertunnel.transport.payload.PayloadContext
import me.treexhd.supertunnel.transport.payload.PayloadHandshakeLayer
import me.treexhd.supertunnel.transport.payload.PayloadHandshakeResult
import me.treexhd.supertunnel.transport.proxy.HttpConnectLayer
import me.treexhd.supertunnel.transport.websocket.WebSocketLayer
import me.treexhd.supertunnel.service.TunnelLogBook
import com.trilead.ssh2.ProxyData
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class PipelineProxyData(private val vpn: VpnService, private val network: Network?, private val profile: TunnelProfile, private val proxyPassword: CharArray?) : ProxyData {
    override fun openConnection(host: String, port: Int, connectTimeout: Int): Socket {
        val proxyNeeded = profile.mode in setOf(TunnelMode.SSH_PROXY, TunnelMode.SSH_PROXY_PAYLOAD, TunnelMode.SSH_TLS_PROXY, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        val tlsNeeded = profile.mode in setOf(TunnelMode.SSH_TLS, TunnelMode.SSH_TLS_PROXY, TunnelMode.SSH_TLS_PAYLOAD, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        val payloadNeeded = profile.mode in setOf(TunnelMode.SSH_PAYLOAD, TunnelMode.SSH_PROXY_PAYLOAD, TunnelMode.SSH_TLS_PAYLOAD, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        // Payloads are protocol data, not templates: send exactly what the user
        // configured. In particular, HTTP 101 raw-SSH gateways use their Host
        // header as routing data and must not be replaced by a generated WS request.
        val payload = profile.payload
        require(payload?.webSocket == true) { "WEBSOCKET_GATEWAY_REQUIRED" }
        val sshTarget = Endpoint(host, port)
        // The raw-upgrade servers used by SuperTunnel-style profiles expose SSH directly
        // after an HTTP Upgrade on port 80. No separate payload endpoint is required.
        val payloadEndpoint = payload?.takeIf { it.endpointHost.isNotBlank() && it.endpointPort in 1..65535 }?.let { Endpoint(it.endpointHost, it.endpointPort) }
            ?: if (payload?.webSocket == true && payload.rawUpgradeMode) Endpoint(host, 80) else sshTarget
        // Every profile dials the public WS/WSS gateway. SSH host is only the
        // gateway-side target (127.0.0.1:22), never a phone-side TCP destination.
        val physical = if (proxyNeeded) Endpoint(profile.proxy!!.host, profile.proxy.port) else payloadEndpoint
        TunnelLogBook.add("TCP connect ${physical.host}:${physical.port} (SSH target ${host}:${port})")
        var socket = protectedSocket(physical, connectTimeout)
        var connection: DuplexConnection = duplex(socket, physical)
        val rawProxyPayload = proxyNeeded && payloadNeeded && payload?.proxyHandshake == ProxyHandshake.RAW_PAYLOAD
        if (proxyNeeded && !rawProxyPayload) { TunnelLogBook.add("HTTP CONNECT proxy handshake"); runBlocking { HttpConnectLayer.connect(connection, payloadEndpoint, profile.proxy!!, proxyPassword) } }
        val customWebSocketPayload = payload?.raw?.let {
            it.contains("[ws_key]") ||
                it.contains("Upgrade: websocket", ignoreCase = true) ||
                it.contains("Sec-WebSocket-Key:", ignoreCase = true)
        } == true
        // A WSS Upgrade is HTTP inside TLS. Older profiles defaulted every payload
        // to BEFORE_TLS, so recognize WS payloads and put them at the only valid layer.
        val payloadAfterTls = tlsNeeded &&
            (payload?.placement == PayloadPlacement.AFTER_TLS || customWebSocketPayload)
        val payloadContext = PayloadContext(
            ssh = Endpoint(host, port),
            proxy = profile.proxy?.let { Endpoint(it.host, it.port) },
            tls = profile.tls?.let { Endpoint(it.endpointHost, it.endpointPort) },
            sni = profile.tls?.sni.orEmpty(),
            proxyUsername = profile.proxy?.username,
            proxyPassword = proxyPassword
        )
        var payloadResult: PayloadHandshakeResult? = null
        if (payloadNeeded && !payload!!.raw.isBlank() && !payloadAfterTls) {
            TunnelLogBook.add("raw payload handshake (${payload.raw.length} chars)")
            payloadResult = runBlocking { PayloadHandshakeLayer.apply(connection, payload, payloadContext) }
        }
        if (tlsNeeded) { TunnelLogBook.add("WSS TLS handshake"); socket = tls(socket, profile.tls!!); connection = duplex(socket, payloadEndpoint) }
        if (payloadNeeded && payloadAfterTls && !payload!!.raw.isBlank()) {
            TunnelLogBook.add("raw payload handshake (${payload.raw.length} chars)")
            payloadResult = runBlocking { PayloadHandshakeLayer.apply(connection, payload, payloadContext) }
        }
        if (payload?.webSocket == true) {
            val result = payloadResult
            // A payload that receives HTTP 101 has already upgraded the same
            // connection. It may be a raw HTTP-to-SSH bridge rather than an RFC6455
            // frame stream, so never send a second generated Upgrade afterwards.
            val payloadReturned101 = result?.responseHeader
                ?.lineSequence()
                ?.firstOrNull()
                ?.contains(" 101 ") == true
            val customUpgrade = result?.definesWebSocketUpgrade == true || payloadReturned101
            val rawUpgrade = payload.rawUpgradeMode ||
                (payloadReturned101 && result?.definesWebSocketUpgrade != true)
            TunnelLogBook.add(
                "WebSocket upgrade path=${payload.webSocketPath.ifBlank { "/" }} " +
                    "mode=${if (rawUpgrade) "raw" else "RFC6455"} source=${if (customUpgrade) "payload" else "built-in"}"
            )
            val streams = if (customUpgrade) {
                val response = requireNotNull(result.responseHeader) {
                    "CUSTOM_WEBSOCKET_PAYLOAD_REQUIRES_RESPONSE"
                }
                WebSocketLayer.attach(
                    connection,
                    response,
                    result.webSocketKey.takeIf { payload.raw.contains("[ws_key]") },
                    rawUpgrade
                )
            } else {
                WebSocketLayer.upgrade(
                    connection,
                    profile.tls?.sni?.ifBlank { payloadEndpoint.host } ?: payloadEndpoint.host,
                    payload.webSocketPath,
                    rawUpgrade
                )
            }
            TunnelLogBook.add("WebSocket upgrade accepted; handing stream to SSH 127.0.0.1")
            return StreamSocket(streams.first, streams.second, socket)
        }
        return socket
    }
    private fun protectedSocket(endpoint: Endpoint, timeout: Int): Socket = Socket().also { socket ->
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.receiveBufferSize = 1024 * 1024
        socket.sendBufferSize = 1024 * 1024
        // This upstream socket is opened before the app establishes its TUN. On Android 17
        // This socket is opened before the TUN is established, so Android routes it
        // through the current physical default network. Do not bind it to a cached
        // Network handle: after a VPN replacement that handle can be stale and make
        // an otherwise reachable gateway time out.
        val protected = vpn.protect(socket)
        if (!protected) TunnelLogBook.add("VpnService.protect unavailable before TUN; using physical default network")
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeout)
    }
    private fun tls(socket: Socket, config: TlsConfig): SSLSocket = (SSLContext.getInstance("TLS").apply {
        val managers = if (config.insecure) arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }) else null
        init(null, managers, SecureRandom())
    }.socketFactory.createSocket(socket, config.endpointHost, config.endpointPort, true) as SSLSocket).apply {
        val serverName = config.sni.trim()
        if (serverName.isNotBlank()) sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(serverName)); if (!config.insecure) endpointIdentificationAlgorithm = "HTTPS" }
        startHandshake()
    }
    private fun duplex(socket: Socket, endpoint: Endpoint) = StreamDuplexConnection(socket.inputStream, socket.outputStream, "${endpoint.host}:${endpoint.port}") { socket.close() }
}
private class StreamSocket(private val source: java.io.InputStream, private val sink: java.io.OutputStream, private val delegate: Socket) : Socket() { override fun getInputStream() = source; override fun getOutputStream() = sink; override fun isConnected() = true; override fun close() = delegate.close(); override fun getInetAddress() = delegate.inetAddress; override fun getPort() = delegate.port }
