package me.treexhd.supertunnel.tun

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import me.treexhd.supertunnel.domain.model.TunnelProfile
import me.treexhd.supertunnel.domain.model.TunnelMode

class VpnInterfaceFactory(private val service: VpnService) {
    fun establish(profile: TunnelProfile): ParcelFileDescriptor? = service.Builder().apply {
        setSession(profile.name); setMtu(profile.vpn.mtu); addAddress("10.77.0.2", 24)
        // Do not expose Android's opt-in VPN bypass. Browsers can otherwise
        // bind their own traffic to Wi-Fi and silently evade this full tunnel.
        // Our upstream sockets use VpnService.protect() (or the Slipstream
        // carrier is excluded below), so they do not require allowBypass().
        // Declare the physical network that carries the protected upstream
        // socket. Android 17 otherwise reports this VPN as having no
        // underlying network and can route a protected native fd into a
        // black-hole while the VPN is being established.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = service.getSystemService(ConnectivityManager::class.java)
            val physical = cm.activeNetwork?.takeIf { network ->
                cm.getNetworkCapabilities(network)?.let { caps ->
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } ?: cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.let { caps ->
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            }
            setUnderlyingNetworks(physical?.let { network -> arrayOf(network) })
        }
        if (profile.vpn.fullTunnel) addRoute("0.0.0.0", 0) else profile.vpn.routes.forEach { cidr -> cidr.substringBefore('/').also { addRoute(it, cidr.substringAfter('/').toInt()) } }
        // Do not advertise a DNS server on this VPN until UDP is carried by a
        // real remote udpgw. Advertising 1.1.1.1/8.8.8.8 and then excluding
        // those routes leaves Android's resolver bound to the VPN network with
        // no route to its declared DNS server. With no VPN DNS configured,
        // Android retains the validated DNS resolver from the underlying
        // network while application TCP continues through the TUN/SOCKS path.
        profile.vpn.allowedApps.forEach(::addAllowedApplication); profile.vpn.disallowedApps.forEach(::addDisallowedApplication)
        // Slipstream is a child process and cannot call VpnService.protect on
        // its UDP carrier socket. Exclude this UID to prevent a DNS-to-VPN loop.
        if (profile.mode == TunnelMode.SLIPSTREAM) addDisallowedApplication(service.packageName)
    }.establish()
}
