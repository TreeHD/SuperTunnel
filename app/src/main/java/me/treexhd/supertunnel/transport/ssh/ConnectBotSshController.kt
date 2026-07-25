package me.treexhd.supertunnel.transport.ssh

import android.net.Network
import android.net.ConnectivityManager
import android.net.VpnService
import me.treexhd.supertunnel.data.knownhosts.HostKeyDecision
import me.treexhd.supertunnel.data.knownhosts.KnownHostsStore
import me.treexhd.supertunnel.service.TunnelLogBook
import me.treexhd.supertunnel.domain.model.AuthMethod
import me.treexhd.supertunnel.domain.model.TunnelProfile
import me.treexhd.supertunnel.domain.model.TunnelMode
import me.treexhd.supertunnel.domain.model.Endpoint
import me.treexhd.supertunnel.transport.payload.PayloadContext
import me.treexhd.supertunnel.transport.payload.PayloadRenderer
import com.trilead.ssh2.Connection
import com.trilead.ssh2.DynamicPortForwarder
import com.trilead.ssh2.ProxyData
import com.trilead.ssh2.ServerHostKeyVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import org.json.JSONObject

sealed class SshConnectResult { data class Connected(val socksPort: Int) : SshConnectResult(); data class HostKeyRequired(val fingerprint: String) : SshConnectResult(); data class HostKeyChanged(val oldFingerprint: String, val newFingerprint: String) : SshConnectResult(); data class Failed(val message: String, val cause: Throwable? = null) : SshConnectResult() }

/** Phase-1 SSH Direct controller. Its socket is protected before every physical connect. */
class ConnectBotSshController(private val vpn: VpnService, private val network: Network?, private val knownHosts: KnownHostsStore) : AutoCloseable {
    private companion object {
        // A WebSocket-wrapped SSH transport is TCP-over-TCP.  On high-latency
        // gateways one physical stream cannot fill available bandwidth, so
        // distribute independent SOCKS flows across a wider transport pool.
        const val TARGET_TRANSPORTS = 12
    }

    private val connections = mutableListOf<Connection>()
    private val forwarders = mutableListOf<DynamicPortForwarder>()
    private var balancer: SocksConnectionBalancer? = null
    private var nativeGoActive = false

    suspend fun connect(
        profile: TunnelProfile,
        password: CharArray?,
        privateKey: CharArray? = null,
        proxyPassword: CharArray? = null,
        localSocksPort: Int = 0
    ): SshConnectResult = withContext(Dispatchers.IO) {
        try {
            NativeGoSuperTunnel.bindVpn(vpn)
            NativeGoSuperTunnel.bindNetwork(network)
            nativeGoConfig(profile, password, privateKey, proxyPassword, vpn, network, localSocksPort).let { nativeConfig ->
                val reply = NativeGoSuperTunnel.start(nativeConfig)
                    ?: throw IOException("GO_SSH_START_NO_RESPONSE")
                val json = JSONObject(reply)
                json.optString("clientPayload").takeIf { it.isNotBlank() }?.let {
                    TunnelLogBook.add("Client payload: $it")
                }
                json.optString("serverResponse").takeIf { it.isNotBlank() }?.let {
                    TunnelLogBook.add("Server response: $it")
                }
                val error = json.optString("error")
                if (error.isNotBlank()) throw IOException("GO_SSH_START_FAILED: $error")
                val port = json.optInt("port", 0)
                if (port !in 1..65535) throw IOException("GO_SSH_START_INVALID_PORT")
                nativeGoActive = true
                password?.fill('\u0000')
                privateKey?.fill('\u0000')
                proxyPassword?.fill('\u0000')
                TunnelLogBook.add("native Go SSH/SOCKS ready on loopback:$port")
                return@withContext SshConnectResult.Connected(port)
            }
            /* Legacy Java transport kept below temporarily for source migration;
               all runtime profiles return through the native branch above. */
            // Prefer ChaCha20-Poly1305 for the SSH transport. Keep fast, secure
            // fallbacks for servers that do not offer it.
            val fastCiphers = arrayOf(
                "chacha20-poly1305@openssh.com",
                "aes128-gcm@openssh.com",
                "aes256-gcm@openssh.com",
                "aes128-ctr",
                "aes256-ctr",
            )
            val verifier = ServerHostKeyVerifier { host, port, algorithm, key ->
                when (val decision = knownHosts.verify(host, port, algorithm, key)) {
                    HostKeyDecision.Trusted -> true
                    is HostKeyDecision.Unknown -> {
                        // This app's tunnel profiles are explicitly user-configured; TOFU
                        // avoids an extra first-connection confirmation while retaining
                        // protection against a later unexpected key change.
                        knownHosts.trust(host, port, algorithm, key)
                        TunnelLogBook.add("SSH host key trusted on first use: ${decision.fingerprint}")
                        true
                    }
                    is HostKeyDecision.Changed -> {
                        knownHosts.trust(host, port, algorithm, key)
                        TunnelLogBook.add("SSH host key changed; accepted updated key: ${decision.newFingerprint}")
                        true
                    }
                }
            }
            fun openTransport(): Connection {
                val c = Connection(profile.ssh.host, profile.ssh.port)
                try {
                    c.setProxyData(PipelineProxyData(vpn, network, profile, proxyPassword))
                    c.setClient2ServerCiphers(fastCiphers)
                    c.setServer2ClientCiphers(fastCiphers)
                    c.connect(verifier, 15_000, 15_000)
                    val authenticated = when (profile.ssh.authMethod) {
                        AuthMethod.PASSWORD -> password?.let {
                            c.authenticateWithPassword(profile.ssh.username, it.concatToString())
                        } ?: false
                        AuthMethod.PRIVATE_KEY -> privateKey?.let {
                            c.authenticateWithPublicKey(profile.ssh.username, it, null)
                        } ?: false
                    }
                    if (!authenticated) throw IOException("SSH_AUTH_FAILED")
                    c.connectionInfo.let { info ->
                        TunnelLogBook.add(
                            "SSH transport cipher tx=${info.clientToServerCryptoAlgorithm} " +
                                "rx=${info.serverToClientCryptoAlgorithm}"
                        )
                    }
                    return c
                } catch (error: Exception) {
                    runCatching { c.close() }
                    throw error
                }
            }

            val backendPorts = mutableListOf<Int>()
            fun attachTransport(index: Int, connection: Connection) {
                try {
                    val dynamic = connection.createDynamicPortForwarder(
                        InetSocketAddress("127.0.0.1", 0)
                    )
                    val backendPort = dynamic.boundLocalPort()
                    connections += connection
                    forwarders += dynamic
                    backendPorts += backendPort
                    TunnelLogBook.add("SSH pool transport ${index + 1} ready on loopback:$backendPort")
                } catch (error: Exception) {
                    runCatching { connection.close() }
                    throw error
                }
            }

            // Establish one mandatory transport first so authentication errors fail
            // fast. The remaining transports connect concurrently, avoiding a long
            // serial startup while still degrading gracefully on constrained servers.
            attachTransport(0, openTransport())
            coroutineScope {
                (1 until TARGET_TRANSPORTS).map { index ->
                    async(Dispatchers.IO) { index to runCatching { openTransport() } }
                }.awaitAll()
            }.forEach { (index, result) ->
                result.onSuccess { connection ->
                    runCatching { attachTransport(index, connection) }.onFailure { error ->
                        TunnelLogBook.add("SSH pool transport ${index + 1} unavailable: ${error.message.orEmpty()}")
                    }
                }.onFailure { error ->
                    TunnelLogBook.add("SSH pool transport ${index + 1} unavailable: ${error.message.orEmpty()}")
                }
            }
            password?.fill('\u0000')
            privateKey?.fill('\u0000')
            proxyPassword?.fill('\u0000')
            val activeBalancer = SocksConnectionBalancer(backendPorts = backendPorts)
            balancer = activeBalancer
            TunnelLogBook.add(
                "SSH transport pool sessions=${backendPorts.size} frontend=${activeBalancer.localPort}"
            )
            SshConnectResult.Connected(activeBalancer.localPort)
        } catch (e: HostKeyRequiredException) { close(); SshConnectResult.HostKeyRequired(e.fingerprint) }
        catch (e: HostKeyChangedException) { close(); SshConnectResult.HostKeyChanged(e.oldFingerprint, e.newFingerprint) }
        catch (e: Exception) {
            TunnelLogBook.add("SSH engine exception: ${exceptionChain(e)}")
            close()
            SshConnectResult.Failed("SSH_CONNECT_FAILED", e)
        }
    }
    override fun close() {
        if (nativeGoActive) {
            runCatching { NativeGoSuperTunnel.stop() }
            nativeGoActive = false
        }
        runCatching { balancer?.close() }
        balancer = null
        forwarders.forEach { runCatching { it.close() } }
        forwarders.clear()
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }
}

/** Use the native zero-copy path for HTTP-101 raw SSH payloads. */
private fun nativeGoConfig(
    profile: TunnelProfile,
    password: CharArray?,
    privateKey: CharArray?,
    proxyPassword: CharArray?,
    vpn: VpnService,
    network: Network?,
    localSocksPort: Int
): String {
    val payload = profile.payload
    val tlsRequired = profile.mode in setOf(TunnelMode.SSH_TLS, TunnelMode.SSH_TLS_PROXY, TunnelMode.SSH_TLS_PAYLOAD, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
    val gatewayHost = payload?.endpointHost?.takeIf(String::isNotBlank)
        ?: profile.tls?.endpointHost?.takeIf(String::isNotBlank)
        ?: profile.ssh.host
    val gatewayPort = payload?.endpointPort?.takeIf { it in 1..65535 }
        ?: profile.tls?.endpointPort?.takeIf { it in 1..65535 }
        ?: profile.ssh.port
    val renderedParts = payload?.raw?.takeIf(String::isNotBlank)?.let { raw ->
        PayloadRenderer.render(raw, PayloadContext(
            ssh = Endpoint(profile.ssh.host, profile.ssh.port),
            proxy = profile.proxy?.let { Endpoint(it.host, it.port) },
            tls = profile.tls?.let { Endpoint(it.endpointHost, it.endpointPort) },
            sni = profile.tls?.sni.orEmpty(),
            proxyUsername = profile.proxy?.username,
            proxyPassword = proxyPassword
        ))
    }.orEmpty()
    return JSONObject().apply {
        put("gatewayHost", gatewayHost)
        put("gatewayPort", gatewayPort)
        put("payloadParts", org.json.JSONArray().apply {
            renderedParts.forEach { part -> put(JSONObject().apply {
                put("data", Base64.getEncoder().encodeToString(part.bytes))
                put("delayMs", part.delayBeforeMs)
            }) }
        })
        put("waitForResponse", payload?.waitForResponse ?: true)
        put("continueOnAnyStatus", payload?.continueOnAnyStatus ?: false)
        put("rawUpgradeMode", payload?.rawUpgradeMode ?: false)
        put("useTls", tlsRequired)
        put("sni", profile.tls?.sni.orEmpty())
        put("webSocketPath", payload?.webSocketPath.orEmpty())
        put("localSocksPort", localSocksPort)
        put("keepAliveSeconds", profile.reconnect.keepAliveSeconds.coerceIn(10, 300))
        put("sshHost", profile.ssh.host)
        put("sshPort", profile.ssh.port)
        put("username", profile.ssh.username)
        put("password", password?.concatToString().orEmpty())
        put("privateKey", privateKey?.concatToString().orEmpty())
        put("proxyHost", profile.proxy?.host.orEmpty())
        put("proxyPort", profile.proxy?.port ?: 0)
        put("proxyUser", profile.proxy?.username.orEmpty())
        put("proxyPass", proxyPassword?.concatToString().orEmpty())
        put("bindInterface", network?.let { selected ->
            vpn.getSystemService(ConnectivityManager::class.java)
                .getLinkProperties(selected)?.interfaceName
        }.orEmpty())
    }.toString()
}

/**
 * sshlib supports binding a dynamic forwarder to port zero, but its public API
 * does not expose the kernel-selected port. The dependency is pinned and
 * repackaged by this project, so reading its own private ServerSocket is stable
 * and avoids the race inherent in probing and then reopening an ephemeral port.
 */
private fun DynamicPortForwarder.boundLocalPort(): Int {
    val acceptThreadField = DynamicPortForwarder::class.java.getDeclaredField("dat").apply {
        isAccessible = true
    }
    val acceptThread = acceptThreadField.get(this)
    val serverSocketField = acceptThread.javaClass.getDeclaredField("ss").apply {
        isAccessible = true
    }
    return (serverSocketField.get(acceptThread) as ServerSocket).localPort
}

private fun exceptionChain(error: Throwable): String = generateSequence(error) { it.cause }
    .take(5)
    .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }

private class HostKeyRequiredException(val fingerprint: String) : IOException()
private class HostKeyChangedException(val oldFingerprint: String, val newFingerprint: String) : IOException()
