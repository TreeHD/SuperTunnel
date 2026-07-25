package me.treexhd.supertunnel

import me.treexhd.supertunnel.domain.model.Endpoint
import me.treexhd.supertunnel.transport.payload.PayloadContext
import me.treexhd.supertunnel.transport.payload.PayloadRenderer
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PayloadRendererTest {
    @Test fun rendersCrLfAndEndpointTokens() {
        val parts = PayloadRenderer.render("CONNECT [ssh_host]:[ssh_port] HTTP/1.1[crlf][crlf]", PayloadContext(ssh = Endpoint("ssh.example", 22)))
        assertEquals("CONNECT ssh.example:22 HTTP/1.1\r\n\r\n", parts.single().bytes.toString(Charsets.US_ASCII))
    }
    @Test fun splitIsPlannedWithoutLeakingMarker() {
        val parts = PayloadRenderer.render("one[split]two[delay_split:10]three", PayloadContext(Endpoint("x", 22)))
        assertEquals(listOf("one", "two", "three"), parts.map { it.bytes.toString(Charsets.US_ASCII) })
        assertEquals(listOf(0L, 0L, 10L), parts.map { it.delayBeforeMs })
    }

    @Test fun rendersCoreTokens() {
        val context = PayloadContext(
            ssh = Endpoint("127.0.0.1", 22),
            proxy = Endpoint("proxy.example", 8080),
            proxyUsername = "user",
            proxyPassword = "pass".toCharArray()
        )
        val payload = "[method] [host_port] [protocol][crlf]User-Agent: [ua][crlf]Proxy: [proxy_host]:[proxy_port][crlf]Proxy-Authorization: [auth][crlf][crlf]"
        val rendered = PayloadRenderer.render(payload, context).single().bytes.toString(Charsets.US_ASCII)
        assertTrue(rendered.startsWith("CONNECT 127.0.0.1:22 HTTP/1.1\r\n"))
        assertTrue(rendered.contains("Proxy: proxy.example:8080\r\n"))
        assertTrue(rendered.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"))
    }

    @Test fun supportsAllSplitAliasesAndAppliesDelayBeforeNextWrite() {
        val parts = PayloadRenderer.render(
            "a[instant_split]b[split]c[delay_split]d[split_delay]e[split=25]f[delay_split:30]g",
            PayloadContext(Endpoint("x", 22))
        )
        assertEquals(listOf(0L, 0L, 0L, 1500L, 1500L, 25L, 30L), parts.map { it.delayBeforeMs })
    }

    @Test fun netDataRawAndLineBreakTokensMatchForm() {
        val parts = PayloadRenderer.render(
            "[netData][crlf][raw][real_raw][cr][lf][lfcr]",
            PayloadContext(Endpoint("ssh.example", 2222))
        )
        assertEquals(
            "CONNECT ssh.example:2222 HTTP/1.1\r\n" +
                "CONNECT ssh.example:2222 HTTP/1.0\r\n\r\n" +
                "CONNECT ssh.example:2222 HTTP/1.0\r\n\r\n\r\n\n\r",
            parts.single().bytes.toString(Charsets.US_ASCII)
        )
    }

    @Test fun rotateIsStableAndDoesNotRecursivelyExpandChosenText() {
        PayloadRenderer.resetRotationForTests()
        val context = PayloadContext(Endpoint("x", 22))
        val template = "[rotate=one;[host];three]"
        assertEquals("one", PayloadRenderer.render(template, context).single().bytes.toString(Charsets.US_ASCII))
        assertEquals("[host]", PayloadRenderer.render(template, context).single().bytes.toString(Charsets.US_ASCII))
        assertEquals("three", PayloadRenderer.render(template, context).single().bytes.toString(Charsets.US_ASCII))
        assertEquals("one", PayloadRenderer.render(template, context).single().bytes.toString(Charsets.US_ASCII))
    }

    @Test fun randomAndWebSocketKeyAreRendered() {
        val context = PayloadContext(Endpoint("x", 22))
        val first = PayloadRenderer.render("[random=a;b;c]-[ws_key]", context).single().bytes.toString(Charsets.US_ASCII)
        assertTrue(first.substringBefore('-') in setOf("a", "b", "c"))
        assertEquals(context.webSocketKey, first.substringAfter('-'))
        assertNotEquals("", context.webSocketKey)
    }
}
