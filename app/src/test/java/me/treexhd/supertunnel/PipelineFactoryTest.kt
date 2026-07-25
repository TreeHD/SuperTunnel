package me.treexhd.supertunnel

import me.treexhd.supertunnel.domain.model.*
import me.treexhd.supertunnel.transport.core.PipelineFactory
import me.treexhd.supertunnel.transport.core.PipelineStage
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineFactoryTest {
    @Test fun tlsProxyPayloadUsesConfiguredPlacement() {
        val p = TunnelProfile(mode = TunnelMode.SSH_TLS_PROXY_PAYLOAD, payload = PayloadConfig(raw = "x", placement = PayloadPlacement.AFTER_TLS))
        assertEquals(listOf(PipelineStage.TCP, PipelineStage.HTTP_CONNECT, PipelineStage.TLS, PipelineStage.PAYLOAD, PipelineStage.SSH), PipelineFactory.stages(p))
    }
}
