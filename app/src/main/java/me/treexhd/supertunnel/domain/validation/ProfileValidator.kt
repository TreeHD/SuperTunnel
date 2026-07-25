package me.treexhd.supertunnel.domain.validation

import me.treexhd.supertunnel.domain.model.*

data class ValidationResult(val errors: List<String>) { val isValid get() = errors.isEmpty() }

object ProfileValidator {
    fun validate(profile: TunnelProfile): ValidationResult = buildList {
        if (profile.mode == TunnelMode.SLIPSTREAM) {
            val config = profile.slipstream
            if (config == null) add("Slipstream configuration is required") else {
                if (config.domain.isBlank() || config.domain.contains(Regex("\\s"))) add("Slipstream domain is required")
                if (config.resolver.isBlank()) add("Slipstream DNS resolver is required")
                if (config.congestionControl !in setOf("bbr", "dcubic")) add("Slipstream congestion control must be bbr or dcubic")
                if (config.keepAliveIntervalMs !in 100..10_000) add("Slipstream keep-alive must be 100..10000 ms")
            }
            if (profile.vpn.allowedApps.isNotEmpty()) add("Slipstream cannot be used with an app allowlist")
            return@buildList
        }
        checkEndpoint("SSH", profile.ssh.host, profile.ssh.port, this)
        if (profile.ssh.username.isBlank()) add("SSH username is required")
        val proxyRequired = profile.mode in setOf(TunnelMode.SSH_PROXY, TunnelMode.SSH_PROXY_PAYLOAD, TunnelMode.SSH_TLS_PROXY, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        val tlsRequired = profile.mode in setOf(TunnelMode.SSH_TLS, TunnelMode.SSH_TLS_PROXY, TunnelMode.SSH_TLS_PAYLOAD, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        val payloadRequired = profile.mode in setOf(TunnelMode.SSH_PAYLOAD, TunnelMode.SSH_PROXY_PAYLOAD, TunnelMode.SSH_TLS_PAYLOAD, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        if (proxyRequired) profile.proxy?.let { checkEndpoint("Proxy", it.host, it.port, this) } ?: add("Proxy is required")
        if (tlsRequired) profile.tls?.let { checkEndpoint("TLS", it.endpointHost, it.endpointPort, this); if (it.sni.isBlank() || it.sni.contains('/') || it.sni.contains("://")) add("TLS SNI must be a hostname") } ?: add("TLS configuration is required")
        if (profile.payload?.webSocket != true) add("WebSocket gateway is required")
        profile.payload?.let { checkEndpoint("WebSocket gateway", it.endpointHost, it.endpointPort, this) }
        if (profile.payload?.raw?.toByteArray()?.size ?: 0 > 65_536) add("Payload is over 64 KiB")
        if (profile.vpn.mtu !in 1280..1500) add("MTU must be 1280..1500")
        if (profile.vpn.allowedApps.isNotEmpty() && profile.vpn.disallowedApps.isNotEmpty()) add("Allowlist and denylist cannot both be used")
        if (!profile.vpn.fullTunnel && profile.vpn.routes.isEmpty()) add("Split tunnel needs at least one route")
    }.let(::ValidationResult)

    private fun checkEndpoint(label: String, host: String, port: Int, errors: MutableList<String>) {
        if (host.isBlank()) errors += "$label host is required"
        if (port !in 1..65535) errors += "$label port must be 1..65535"
    }
}
