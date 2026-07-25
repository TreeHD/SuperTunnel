package me.treexhd.supertunnel.transport.core

import me.treexhd.supertunnel.domain.model.PayloadPlacement
import me.treexhd.supertunnel.domain.model.TunnelMode
import me.treexhd.supertunnel.domain.model.TunnelProfile

enum class PipelineStage { TCP, HTTP_CONNECT, PAYLOAD, TLS, WEBSOCKET, SSH }

/** Presets are defaults, while the profile's advanced placement remains authoritative. */
object PipelineFactory {
    fun stages(profile: TunnelProfile): List<PipelineStage> {
        val proxy = profile.mode in setOf(TunnelMode.SSH_PROXY, TunnelMode.SSH_PROXY_PAYLOAD, TunnelMode.SSH_TLS_PROXY, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        val tls = profile.mode in setOf(TunnelMode.SSH_TLS, TunnelMode.SSH_TLS_PROXY, TunnelMode.SSH_TLS_PAYLOAD, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        val payload = profile.mode in setOf(TunnelMode.SSH_PAYLOAD, TunnelMode.SSH_PROXY_PAYLOAD, TunnelMode.SSH_TLS_PAYLOAD, TunnelMode.SSH_TLS_PROXY_PAYLOAD)
        return buildList {
            add(PipelineStage.TCP)
            if (proxy) add(PipelineStage.HTTP_CONNECT)
            if (payload && profile.payload?.placement != PayloadPlacement.AFTER_TLS) add(PipelineStage.PAYLOAD)
            if (tls) add(PipelineStage.TLS)
            if (payload && profile.payload?.placement == PayloadPlacement.AFTER_TLS) add(PipelineStage.PAYLOAD)
            add(PipelineStage.SSH)
        }
    }
}
