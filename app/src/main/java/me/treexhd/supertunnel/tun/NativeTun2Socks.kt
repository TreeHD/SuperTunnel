package me.treexhd.supertunnel.tun

/** JNI lifecycle only; native code owns no Activity/Service Java references. */
object NativeTun2Socks {
    init { System.loadLibrary("tun2socks_jni") }
    external fun start(tunFd: Int, socksAddress: String, udpgwAddress: String, mtu: Int): Boolean
    external fun stop()
    external fun isRunning(): Boolean
    /** tx packets/bytes from TUN, then rx packets/bytes back to TUN. */
    external fun stats(): LongArray
}
