package me.treexhd.supertunnel.transport.slipstream

import android.content.Context
import me.treexhd.supertunnel.domain.model.SlipstreamConfig
import me.treexhd.supertunnel.service.TunnelLogBook
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the upstream Slipstream QUIC-over-DNS client with a loopback SOCKS endpoint. */
class SlipstreamController(private val context: Context) : AutoCloseable {
    private var process: Process? = null
    private val alive = AtomicBoolean(false)

    fun start(config: SlipstreamConfig, socksPort: Int): String? {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libslipstream_client.so")
        if (!binary.canExecute()) return "Slipstream native client is unavailable on this CPU architecture"
        val resolver = config.resolver.trim().let { if (it.count { char -> char == ':' } == 1 || it.startsWith("[")) it else "$it:53" }
        val args = listOf(binary.absolutePath, "--tcp-listen-host", "127.0.0.1", "--tcp-listen-port", socksPort.toString(), "--domain", config.domain.trim(), "--resolver", resolver, "--congestion-control", config.congestionControl, "--keep-alive-interval", config.keepAliveIntervalMs.toString()) + if (config.gso) listOf("--gso") else emptyList()
        return try {
            process = ProcessBuilder(args).start()
            Thread { drain(process!!.errorStream) }.apply { isDaemon = true; start() }
            Thread { drain(process!!.inputStream) }.apply { isDaemon = true; start() }
            repeat(20) {
                if (process?.isAlive != true) return "Slipstream client exited during startup"
                if (isSocksListening(socksPort)) { alive.set(true); return null }
                Thread.sleep(100)
            }
            close()
            "Slipstream did not open its local SOCKS5 endpoint"
        } catch (error: Exception) { close(); error.message ?: error.javaClass.simpleName }
    }
    private fun isSocksListening(port: Int): Boolean = runCatching { Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 150) }; true }.getOrDefault(false)
    private fun drain(stream: java.io.InputStream) = runCatching { BufferedReader(InputStreamReader(stream)).useLines { lines -> lines.forEach { TunnelLogBook.add("Slipstream: $it") } } }
    fun isAlive(): Boolean = alive.get() && process?.isAlive == true
    override fun close() { alive.set(false); process?.destroy(); process?.let { if (it.isAlive) it.destroyForcibly() }; process = null }
}
