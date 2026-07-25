package me.treexhd.supertunnel.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import me.treexhd.supertunnel.App
import me.treexhd.supertunnel.data.knownhosts.KnownHostsStore
import me.treexhd.supertunnel.domain.model.TunnelProfile
import me.treexhd.supertunnel.domain.model.TunnelMode
import me.treexhd.supertunnel.domain.state.TunnelFailure
import me.treexhd.supertunnel.domain.state.TunnelStage
import me.treexhd.supertunnel.domain.state.TunnelState
import me.treexhd.supertunnel.domain.state.TunnelTraffic
import me.treexhd.supertunnel.domain.validation.ProfileValidator
import me.treexhd.supertunnel.transport.ssh.ConnectBotSshController
import me.treexhd.supertunnel.transport.ssh.NativeGoSuperTunnel
import me.treexhd.supertunnel.transport.ssh.SshConnectResult
import me.treexhd.supertunnel.transport.slipstream.SlipstreamController
import me.treexhd.supertunnel.tun.NativeTun2Socks
import me.treexhd.supertunnel.tun.VpnInterfaceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the two independent lifetimes in a tunnel:
 *
 * - the Android VPN interface/TUN (kept alive until the user explicitly stops);
 * - the physical WS + SSH transport (rebuilt after a remote/network disconnect).
 *
 * The fixed loopback SOCKS port lets the native tun2socks worker keep its TUN
 * file descriptor and route while only the upstream transport is replaced.
 */
class TunnelOrchestrator(private val service: VpnService, private val state: MutableStateFlow<TunnelState>) {
    private companion object {
        const val STABLE_SOCKS_PORT = 1080
        const val HEALTH_CHECK_MS = 2_000L
        const val MAX_RETRY_DELAY_MS = 30_000L
    }

    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var healthJob: Job? = null
    private var controller: ConnectBotSshController? = null
    private var slipstream: SlipstreamController? = null
    private var tun: android.os.ParcelFileDescriptor? = null
    @Volatile private var activeProfileId: String? = null
    @Volatile private var explicitlyStopped = true

    fun connect(profileId: String) {
        explicitlyStopped = false
        activeProfileId = profileId
        TunnelVpnService.setTraffic(TunnelTraffic())
        healthJob?.cancel()
        connectJob?.cancel()
        connectJob = scope.launch {
            // This is an explicit connect/profile switch, so replacing the VPN is
            // intended. Automatic recovery below never calls closeActive().
            closeActive()
            TunnelLogBook.clear()
            TunnelLogBook.add("Tunnel started")
            stage(TunnelStage.VALIDATING)
            val app = service.application as App
            val profile = app.profiles.get(profileId)
                ?: return@launch fail("PROFILE_INVALID", TunnelStage.VALIDATING, "Selected profile no longer exists")
            ProfileValidator.validate(profile).takeIf { !it.isValid }?.let {
                return@launch fail("PROFILE_INVALID", TunnelStage.VALIDATING, it.errors.first())
            }
            TunnelLogBook.add("profile=${profile.name}; mode=${profile.mode}; ssh=${profile.ssh.host}:${profile.ssh.port}")

            when (val result = openTransport(profile)) {
                is SshConnectResult.Connected -> {
                    if (result.socksPort != STABLE_SOCKS_PORT) {
                        closeTransport()
                        return@launch fail("SOCKS_PORT_UNSTABLE", TunnelStage.STARTING_SOCKS, "Tunnel did not bind its stable SOCKS endpoint")
                    }
                    TunnelLogBook.add("SSH authenticated; local SOCKS=127.0.0.1:${result.socksPort}")
                    stage(TunnelStage.ESTABLISHING_TUN, profile.name)
                    val interfaceFd = VpnInterfaceFactory(service).establish(profile)
                        ?: run {
                            closeTransport()
                            return@launch fail("TUN_ESTABLISH_FAILED", TunnelStage.ESTABLISHING_TUN, "Android refused the VPN interface")
                        }
                    tun = interfaceFd
                    if (!startTun2Socks(profile)) {
                        interfaceFd.close()
                        tun = null
                        closeTransport()
                        return@launch fail("TUN2SOCKS_START_FAILED", TunnelStage.STARTING_TUN2SOCKS, "Native tun2socks failed to start")
                    }
                    connected(profile, reconnected = false)
                }
                is SshConnectResult.HostKeyRequired -> { closeTransport(); fail("SSH_HOST_KEY_UNKNOWN", TunnelStage.VERIFYING_HOST_KEY, "SSH host key needs approval: ${result.fingerprint}") }
                is SshConnectResult.HostKeyChanged -> { closeTransport(); fail("SSH_HOST_KEY_CHANGED", TunnelStage.VERIFYING_HOST_KEY, "Saved SSH host key differs from server") }
                is SshConnectResult.Failed -> { closeTransport(); fail(result.message, TunnelStage.SSH_AUTHENTICATING, "SSH connection failed", result.cause?.let(::safeCause).orEmpty()) }
            }
        }
    }

    fun disconnect() {
        explicitlyStopped = true
        activeProfileId = null
        healthJob?.cancel(); healthJob = null
        connectJob?.cancel(); connectJob = null
        closeActive()
        TunnelVpnService.setTraffic(TunnelTraffic())
        TunnelLogBook.add("Tunnel stopped")
        state.value = TunnelState()
    }

    fun stop() { disconnect(); scope.cancel() }

    private suspend fun openTransport(profile: TunnelProfile): SshConnectResult {
        closeTransport()
        stage(TunnelStage.CONNECTING_TCP, profile.name)
        if (profile.mode == TunnelMode.SLIPSTREAM) {
            val newController = SlipstreamController(service)
            val error = newController.start(requireNotNull(profile.slipstream), STABLE_SOCKS_PORT)
            return if (error == null) {
                slipstream = newController
                TunnelLogBook.add("Slipstream QUIC/DNS client ready on loopback:$STABLE_SOCKS_PORT")
                SshConnectResult.Connected(STABLE_SOCKS_PORT)
            } else SshConnectResult.Failed("SLIPSTREAM_START_FAILED", IllegalStateException(error))
        }
        val app = service.application as App
        val password = profile.ssh.passwordSecretId?.let(app.secrets::get)
        val privateKey = profile.ssh.privateKeySecretId?.let(app.secrets::get)
        val proxyPassword = profile.proxy?.passwordSecretId?.let(app.secrets::get)
        val newController = ConnectBotSshController(service, physicalNetwork(), KnownHostsStore(service))
        controller = newController
        return newController.connect(profile, password, privateKey, proxyPassword, STABLE_SOCKS_PORT)
    }

    private fun connected(profile: TunnelProfile, reconnected: Boolean) {
        if (reconnected) TunnelLogBook.add("Tunnel reconnected; VPN interface was kept alive")
        else TunnelLogBook.add("tun2socks started; tunnel connected")
        TunnelLogBook.add("Tunnel connected")
        state.value = TunnelState(TunnelStage.CONNECTED, profile.name, System.currentTimeMillis())
        startHealthMonitor(profile)
    }

    private fun startHealthMonitor(profile: TunnelProfile) {
        healthJob?.cancel()
        healthJob = scope.launch {
            while (isActive && !explicitlyStopped && activeProfileId == profile.id) {
                delay(HEALTH_CHECK_MS)
                if (!NativeTun2Socks.isRunning()) {
                    TunnelLogBook.add("tun2socks worker stopped unexpectedly; restarting without replacing VPN")
                    if (!startTun2Socks(profile)) {
                        fail("TUN2SOCKS_RECOVERY_FAILED", TunnelStage.RECONNECTING, "Could not restart packet forwarder", recoverable = true)
                        delay(MAX_RETRY_DELAY_MS)
                        continue
                    }
                }
                val counters = NativeTun2Socks.stats()
                if (counters.size == 4) {
                    // Keep traffic visible in the Home UI without polluting the
                    // connection log with one line per health-check interval.
                    TunnelVpnService.setTraffic(TunnelTraffic(
                        uploadedBytes = counters[1].coerceAtLeast(0L),
                        downloadedBytes = counters[3].coerceAtLeast(0L)
                    ))
                }
                if (profile.mode == TunnelMode.SLIPSTREAM) {
                    if (slipstream?.isAlive() != true) reconnectUntilConnected(profile)
                } else if (!NativeGoSuperTunnel.isAlive()) reconnectUntilConnected(profile)
            }
        }
    }

    private suspend fun reconnectUntilConnected(profile: TunnelProfile) {
        if (!profile.reconnect.enabled) {
            fail("TRANSPORT_DISCONNECTED", TunnelStage.RECONNECTING, "WS/SSH transport disconnected", recoverable = true)
            return
        }
        var attempt = 0
        while (scope.isActive && !explicitlyStopped && activeProfileId == profile.id) {
            attempt += 1
            val delayMs = if (attempt == 1) 0L else minOf(MAX_RETRY_DELAY_MS, 1_000L * (1L shl minOf(attempt - 2, 5)))
            stage(TunnelStage.RECONNECTING, profile.name)
            TunnelLogBook.add("WS/SSH disconnected; reconnect attempt=$attempt${if (delayMs > 0) "; waiting=${delayMs}ms" else ""}")
            if (delayMs > 0) delay(delayMs)
            if (explicitlyStopped || activeProfileId != profile.id) return
            try {
                when (val result = openTransport(profile)) {
                    is SshConnectResult.Connected -> {
                        if (result.socksPort == STABLE_SOCKS_PORT) {
                            // tun2socks has stayed attached to its original TUN and
                            // fixed loopback SOCKS endpoint throughout recovery.
                            if (!NativeTun2Socks.isRunning() && !startTun2Socks(profile)) {
                                TunnelLogBook.add("Reconnect transport ready but tun2socks restart failed")
                                closeTransport()
                                continue
                            }
                            connected(profile, reconnected = true)
                            return
                        }
                        closeTransport()
                        TunnelLogBook.add("Reconnect rejected: SOCKS port changed")
                    }
                    is SshConnectResult.Failed -> TunnelLogBook.add("Reconnect failed: ${safeCause(result.cause ?: Exception(result.message))}")
                    is SshConnectResult.HostKeyRequired -> fail("SSH_HOST_KEY_UNKNOWN", TunnelStage.VERIFYING_HOST_KEY, "SSH host key needs approval", recoverable = true)
                    is SshConnectResult.HostKeyChanged -> fail("SSH_HOST_KEY_CHANGED", TunnelStage.VERIFYING_HOST_KEY, "SSH host key changed", recoverable = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                TunnelLogBook.add("Reconnect exception: ${safeCause(error)}")
                closeTransport()
            }
        }
    }

    private fun startTun2Socks(profile: TunnelProfile): Boolean {
        val interfaceFd = tun ?: return false
        stage(TunnelStage.STARTING_TUN2SOCKS, profile.name)
        val udpGw = if (profile.udpgw.enabled) "${profile.udpgw.remoteHost}:${profile.udpgw.remotePort}" else "0.0.0.0:0"
        return NativeTun2Socks.isRunning() || NativeTun2Socks.start(
            interfaceFd.fd,
            "127.0.0.1:$STABLE_SOCKS_PORT",
            udpGw,
            profile.vpn.mtu
        )
    }

    private fun closeTransport() {
        slipstream?.close()
        slipstream = null
        controller?.close()
        controller = null
    }

    private fun closeActive() {
        if (NativeTun2Socks.isRunning()) NativeTun2Socks.stop()
        tun?.close(); tun = null
        closeTransport()
        TunnelVpnService.setTraffic(TunnelTraffic())
    }

    private fun stage(stage: TunnelStage, profileName: String? = null) {
        TunnelLogBook.add("stage=$stage")
        state.value = TunnelState(stage, profileName)
    }

    private fun fail(code: String, stage: TunnelStage, message: String, technical: String = "", recoverable: Boolean = false) {
        TunnelLogBook.add("ERROR code=$code stage=$stage${technical.takeIf { it.isNotBlank() }?.let { "; $it" }.orEmpty()}")
        state.value = TunnelState(stage, failure = TunnelFailure(code, stage, message, technical, recoverable))
    }

    private fun safeCause(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        .replace(Regex("(?i)(password|authorization):?\\s*[^\\s;]+"), "$1=<redacted>")

    /** Never bind the upstream SSH/WebSocket socket to this app's own VPN. */
    private fun physicalNetwork(): Network? {
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
        fun isUsable(network: Network?) = network != null &&
            connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        connectivity.activeNetwork?.takeIf(::isUsable)?.let { return it }
        return connectivity.allNetworks.firstOrNull { network ->
            isUsable(network) && connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: connectivity.allNetworks.firstOrNull(::isUsable)
    }
}
