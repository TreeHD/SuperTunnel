package me.treexhd.supertunnel.transport.ssh

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * NIO round-robin front end for multiple sshlib DynamicPortForwarders.
 *
 * A complete SOCKS conversation stays on one backend. Separate TCP flows are
 * distributed across independent SSH transports, but every local relay shares
 * one selector thread instead of consuming two blocking threads per flow.
 */
internal class SocksConnectionBalancer(
    listenPort: Int = 0,
    private val backendPorts: List<Int>,
) : AutoCloseable {
    private class Endpoint(val channel: SocketChannel) {
        lateinit var peer: Endpoint
        lateinit var key: SelectionKey
        val pending = ArrayDeque<ByteBuffer>()
    }

    private val loopbackV4 = InetAddress.getByName("127.0.0.1")
    private val running = AtomicBoolean(true)
    private val next = AtomicInteger()
    private val selector = Selector.open()
    private val server = ServerSocketChannel.open().apply {
        configureBlocking(false)
        setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true)
        bind(InetSocketAddress(loopbackV4, listenPort))
        register(selector, SelectionKey.OP_ACCEPT)
    }
    val localPort: Int
        get() = (server.localAddress as InetSocketAddress).port
    private val ioBuffer = ByteBuffer.allocateDirect(128 * 1024)
    private val eventThread = Thread(::eventLoop, "ssh-socks-balancer").apply {
        isDaemon = true
        start()
    }

    private fun eventLoop() {
        while (running.get()) {
            try {
                selector.select()
                val iterator = selector.selectedKeys().iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()
                    if (!key.isValid) continue
                    when {
                        key.isAcceptable -> acceptAll()
                        else -> {
                            val endpoint = key.attachment() as Endpoint
                            if (key.isReadable) read(endpoint)
                            if (key.isValid && key.isWritable) write(endpoint)
                        }
                    }
                }
            } catch (_: Exception) {
                // A failed flow is closed in its handler. Keep the shared
                // selector alive for all other tunnel connections.
            }
        }
    }

    private fun acceptAll() {
        while (true) {
            val client = server.accept() ?: return
            try {
                tune(client)
                val backendPort = backendPorts[
                    Math.floorMod(next.getAndIncrement(), backendPorts.size)
                ]
                val backend = SocketChannel.open().apply {
                    configureBlocking(true)
                    tune(this)
                    connect(InetSocketAddress(loopbackV4, backendPort))
                    configureBlocking(false)
                }
                client.configureBlocking(false)
                val clientEndpoint = Endpoint(client)
                val backendEndpoint = Endpoint(backend)
                clientEndpoint.peer = backendEndpoint
                backendEndpoint.peer = clientEndpoint
                clientEndpoint.key = client.register(selector, SelectionKey.OP_READ, clientEndpoint)
                backendEndpoint.key = backend.register(selector, SelectionKey.OP_READ, backendEndpoint)
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun read(source: Endpoint) {
        try {
            ioBuffer.clear()
            val count = source.channel.read(ioBuffer)
            if (count < 0) return closePair(source)
            if (count == 0) return
            ioBuffer.flip()
            val destination = source.peer

            // Local loopback normally accepts the entire chunk immediately.
            // Allocate only when kernel backpressure leaves a partial write.
            if (destination.pending.isEmpty()) {
                destination.channel.write(ioBuffer)
            }
            if (ioBuffer.hasRemaining()) {
                val queued = ByteBuffer.allocate(ioBuffer.remaining())
                queued.put(ioBuffer).flip()
                destination.pending.addLast(queued)
                destination.key.interestOps(destination.key.interestOps() or SelectionKey.OP_WRITE)
                source.key.interestOps(source.key.interestOps() and SelectionKey.OP_READ.inv())
            }
        } catch (_: Exception) {
            closePair(source)
        }
    }

    private fun write(destination: Endpoint) {
        try {
            while (destination.pending.isNotEmpty()) {
                val buffer = destination.pending.first()
                destination.channel.write(buffer)
                if (buffer.hasRemaining()) return
                destination.pending.removeFirst()
            }
            destination.key.interestOps(destination.key.interestOps() and SelectionKey.OP_WRITE.inv())
            val source = destination.peer
            if (source.key.isValid) {
                source.key.interestOps(source.key.interestOps() or SelectionKey.OP_READ)
            }
        } catch (_: Exception) {
            closePair(destination)
        }
    }

    private fun tune(channel: SocketChannel) {
        channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true)
        channel.setOption(java.net.StandardSocketOptions.SO_KEEPALIVE, true)
        channel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, 1024 * 1024)
        channel.setOption(java.net.StandardSocketOptions.SO_SNDBUF, 1024 * 1024)
    }

    private fun closePair(endpoint: Endpoint) {
        listOf(endpoint, endpoint.peer).forEach {
            runCatching { it.key.cancel() }
            runCatching { it.channel.close() }
            it.pending.clear()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        selector.wakeup()
        runCatching { server.close() }
        selector.keys().toList().forEach { key ->
            runCatching { key.channel().close() }
            runCatching { key.cancel() }
        }
        runCatching { eventThread.join(2_000) }
        runCatching { selector.close() }
    }
}
