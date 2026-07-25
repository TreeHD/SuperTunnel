package me.treexhd.supertunnel.transport.ssh

import android.net.VpnService
import android.net.Network

/** App-owned native Go SSH/SOCKS core, packaged in libtun2socks_jni. */
internal object NativeGoSuperTunnel {
    init { System.loadLibrary("tun2socks_jni") }
    external fun bindVpn(service: VpnService?)
    external fun bindNetwork(network: Network?)
    external fun start(configJson: String): String?
    external fun stop()
    /** True only while the native SSH transport is still usable. */
    external fun isAlive(): Boolean
}
