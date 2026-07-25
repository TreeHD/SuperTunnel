package me.treexhd.supertunnel.transport.core

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

interface DuplexConnection : Closeable {
    val input: InputStream
    val output: OutputStream
    val remoteDescription: String
}

class StreamDuplexConnection(
    override val input: InputStream,
    override val output: OutputStream,
    override val remoteDescription: String,
    private val closeAction: () -> Unit
) : DuplexConnection { override fun close() = closeAction() }
