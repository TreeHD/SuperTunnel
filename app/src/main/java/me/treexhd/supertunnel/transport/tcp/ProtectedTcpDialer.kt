package me.treexhd.supertunnel.transport.tcp

import android.net.Network
import android.net.VpnService
import me.treexhd.supertunnel.domain.model.Endpoint
import me.treexhd.supertunnel.transport.core.DuplexConnection
import me.treexhd.supertunnel.transport.core.StreamDuplexConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Opens only physical upstream sockets. protect() occurs before connect to prevent VPN loops. */
class ProtectedTcpDialer(private val vpnService: VpnService, private val network: Network?) {
    suspend fun dial(endpoint: Endpoint, timeoutMs: Int = 15_000): DuplexConnection = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            check(vpnService.protect(socket)) { "VpnService.protect failed" }
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            StreamDuplexConnection(socket.getInputStream(), socket.getOutputStream(), "${endpoint.host}:${endpoint.port}") { socket.close() }
        } catch (e: Exception) { runCatching { socket.close() }; throw e }
    }
}
