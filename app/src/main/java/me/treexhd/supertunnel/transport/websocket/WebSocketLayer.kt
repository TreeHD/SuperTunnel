package me.treexhd.supertunnel.transport.websocket

import me.treexhd.supertunnel.transport.core.DuplexConnection
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 6455 binary stream adapter; outgoing frames are always client-masked. */
object WebSocketLayer {
    fun upgrade(connection: DuplexConnection, host: String, path: String, rawUpgradeMode: Boolean = false): Pair<InputStream, OutputStream> {
        val keyBytes = ByteArray(16).also(SecureRandom()::nextBytes); val key = Base64.getEncoder().encodeToString(keyBytes)
        val request = "GET ${path.ifBlank { "/" }} HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        connection.output.write(request.toByteArray(Charsets.US_ASCII)); connection.output.flush()
        val header = readHeader(connection.input)
        return attach(connection, header, key, rawUpgradeMode)
    }

    /**
     * Adapts a connection after a user payload already performed the HTTP Upgrade.
     * This is the SuperTunnel-compatible path: the custom payload replaces, rather
     * than precedes, the built-in Upgrade request.
     */
    fun attach(
        connection: DuplexConnection,
        responseHeader: String,
        requestKey: String?,
        rawUpgradeMode: Boolean = false
    ): Pair<InputStream, OutputStream> {
        val lines = responseHeader.split("\r\n")
        require(lines.firstOrNull()?.contains(" 101 ") == true) { "WEBSOCKET_HANDSHAKE_FAILED: status" }
        if (rawUpgradeMode) return connection.input to connection.output
        val values = lines.drop(1).mapNotNull { it.substringBefore(':', "").lowercase().takeIf { k -> k.isNotBlank() }?.let { k -> k to it.substringAfter(':').trim() } }.toMap()
        require(values["upgrade"]?.equals("websocket", true) == true && values["connection"]?.contains("upgrade", true) == true) { "WEBSOCKET_HANDSHAKE_FAILED: upgrade headers" }
        if (requestKey != null) {
            val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((requestKey + GUID).toByteArray(Charsets.US_ASCII)))
            require(values["sec-websocket-accept"] == expected) { "WEBSOCKET_HANDSHAKE_FAILED: accept" }
        }
        return FrameInput(connection.input, connection.output) to FrameOutput(connection.output)
    }
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private fun readHeader(input: InputStream): String { val out = ArrayList<Byte>(); var m = 0; val end = "\r\n\r\n"; while (out.size < 65536) { val b = input.read(); require(b >= 0) { "WEBSOCKET_HANDSHAKE_FAILED: eof" }; out += b.toByte(); m = if (b == end[m].code) m + 1 else if (b == '\r'.code) 1 else 0; if (m == 4) return out.toByteArray().toString(Charsets.ISO_8859_1) }; error("WEBSOCKET_HANDSHAKE_FAILED: header too large") }
}
private class FrameOutput(private val output: OutputStream) : OutputStream() {
    private val random = SecureRandom()
    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
    override fun write(bytes: ByteArray, off: Int, len: Int) {
        var pos = off
        var left = len
        do {
            val count = minOf(left, 64 * 1024)
            send(bytes, pos, count, final = left == count, first = pos == off)
            pos += count
            left -= count
        } while (left > 0)
    }
    private fun send(bytes: ByteArray, off: Int, len: Int, final: Boolean, first: Boolean) {
        val opcode = if (first) 2 else 0
        output.write((if (final) 0x80 else 0) or opcode)
        when {
            len < 126 -> output.write(0x80 or len)
            len <= 65535 -> {
                output.write(0x80 or 126)
                output.write(len ushr 8)
                output.write(len)
            }
            else -> error("WebSocket frame too large")
        }
        val mask = ByteArray(4).also(random::nextBytes)
        output.write(mask)
        val masked = ByteArray(len)
        repeat(len) { masked[it] = (bytes[off + it].toInt() xor mask[it and 3].toInt()).toByte() }
        output.write(masked)
        output.flush()
    }
}
private class FrameInput(private val input: InputStream, private val controlOutput: OutputStream) : InputStream() {
    private var payload = ByteArray(0); private var index = 0; private var closed = false
    override fun read(): Int { while (index >= payload.size) { if (closed) return -1; nextFrame() }; return payload[index++].toInt() and 0xff }
    override fun read(bytes: ByteArray, off: Int, len: Int): Int { if (len == 0) return 0; val first = read(); if (first < 0) return -1; bytes[off] = first.toByte(); var used = 1; while (used < len && index < payload.size) bytes[off + used++] = payload[index++]; return used }
    private fun nextFrame() {
        while (true) {
            val head = input.read()
            if (head < 0) { closed = true; return }
            val opcode = head and 0x0f
            val second = input.read()
            require(second >= 0) { "WEBSOCKET_EOF" }
            var size = (second and 0x7f).toLong()
            if (size == 126L) {
                size = ((readByte().toLong() shl 8) or readByte().toLong())
            } else if (size == 127L) {
                size = 0
                repeat(8) { size = (size shl 8) or readByte().toLong() }
            }
            require(size in 0..16L * 1024 * 1024) { "WEBSOCKET_FRAME_TOO_LARGE" }
            val mask = if ((second and 0x80) != 0) ByteArray(4).also(::readFully) else null
            val data = ByteArray(size.toInt())
            readFully(data)
            if (mask != null) data.indices.forEach {
                data[it] = (data[it].toInt() xor mask[it and 3].toInt()).toByte()
            }
            when (opcode) {
                2, 0 -> { payload = data; index = 0; return }
                8 -> { closed = true; return }
                9 -> {
                    controlOutput.write(0x8a)
                    controlOutput.write(data.size)
                    controlOutput.write(data)
                    controlOutput.flush()
                }
            }
        }
    }
    private fun readByte(): Int = input.read().also { require(it >= 0) { "WEBSOCKET_EOF" } }
    private fun readFully(bytes: ByteArray) {
        var position = 0
        while (position < bytes.size) {
            val count = input.read(bytes, position, bytes.size - position)
            require(count > 0) { "WEBSOCKET_EOF" }
            position += count
        }
    }
}
