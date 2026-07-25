package me.treexhd.supertunnel.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class TunnelMode { SSH_DIRECT, SSH_PROXY, SSH_PAYLOAD, SSH_PROXY_PAYLOAD, SSH_TLS, SSH_TLS_PROXY, SSH_TLS_PAYLOAD, SSH_TLS_PROXY_PAYLOAD, SLIPSTREAM }
@Serializable enum class AuthMethod { PASSWORD, PRIVATE_KEY }
@Serializable enum class PayloadPlacement { BEFORE_TLS, AFTER_TLS }
@Serializable enum class ProxyHandshake { HTTP_CONNECT, RAW_PAYLOAD }
@Serializable enum class Ipv6Mode { BLOCK, BYPASS, EXPERIMENTAL }

@Serializable data class Endpoint(val host: String, val port: Int)
@Serializable data class SshConfig(val host: String = "", val port: Int = 22, val username: String = "", val authMethod: AuthMethod = AuthMethod.PASSWORD, val passwordSecretId: String? = null, val privateKeySecretId: String? = null)
@Serializable data class HttpProxyConfig(val host: String = "", val port: Int = 8080, val username: String? = null, val passwordSecretId: String? = null, val connectTimeoutMs: Long = 15_000, val responseTimeoutMs: Long = 15_000)
@Serializable data class TlsConfig(val endpointHost: String = "", val endpointPort: Int = 443, val sni: String = "", val insecure: Boolean = true)
@Serializable data class PayloadConfig(
    val raw: String = "",
    /** Public WebSocket gateway; SSH itself runs at 127.0.0.1 behind this gateway. */
    val endpointHost: String = "", val endpointPort: Int = 0,
    val placement: PayloadPlacement = PayloadPlacement.BEFORE_TLS,
    val proxyHandshake: ProxyHandshake = ProxyHandshake.HTTP_CONNECT,
    val waitForResponse: Boolean = true, val continueOnAnyStatus: Boolean = false,
    val webSocket: Boolean = false, val webSocketPath: String = "/", val rawUpgradeMode: Boolean = false
)
@Serializable data class UdpGwConfig(val enabled: Boolean = true, val remoteHost: String = "127.0.0.1", val remotePort: Int = 7300, val maxConnections: Int = 256, val transparentDns: Boolean = true)
@Serializable data class DnsConfig(val primary: String = "1.1.1.1", val secondary: String = "8.8.8.8")
@Serializable data class VpnConfig(val mtu: Int = 1400, val fullTunnel: Boolean = true, val routes: List<String> = emptyList(), val allowedApps: List<String> = emptyList(), val disallowedApps: List<String> = emptyList(), val ipv6Mode: Ipv6Mode = Ipv6Mode.BLOCK)
@Serializable data class ReconnectConfig(val enabled: Boolean = true, val keepAliveSeconds: Int = 30)
/** QUIC-over-DNS transport. It does not use SSH, TLS, or WebSocket payloads. */
@Serializable data class SlipstreamConfig(val domain: String = "", val resolver: String = "1.1.1.1", val congestionControl: String = "bbr", val keepAliveIntervalMs: Int = 400, val gso: Boolean = false)
@Serializable data class TunnelProfile(
    val schemaVersion: Int = 1, val id: String = UUID.randomUUID().toString(), val name: String = "New tunnel", val mode: TunnelMode = TunnelMode.SSH_DIRECT,
    val ssh: SshConfig = SshConfig(), val proxy: HttpProxyConfig? = null, val tls: TlsConfig? = null, val payload: PayloadConfig? = null,
    val udpgw: UdpGwConfig = UdpGwConfig(), val dns: DnsConfig = DnsConfig(), val slipstream: SlipstreamConfig? = null, val vpn: VpnConfig = VpnConfig(), val reconnect: ReconnectConfig = ReconnectConfig(),
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis()
)
