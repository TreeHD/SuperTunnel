package me.treexhd.supertunnel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import androidx.core.app.NotificationCompat
import me.treexhd.supertunnel.R
import me.treexhd.supertunnel.domain.state.TunnelFailure
import me.treexhd.supertunnel.domain.state.TunnelStage
import me.treexhd.supertunnel.domain.state.TunnelState
import me.treexhd.supertunnel.domain.state.TunnelTraffic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns all tunnel lifetime. It deliberately does not establish a TUN until an SSH engine is authenticated. */
class TunnelVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "me.treexhd.supertunnel.CONNECT"; const val ACTION_DISCONNECT = "me.treexhd.supertunnel.DISCONNECT"
        private const val CHANNEL_ID = "tunnel_status"; private const val NOTIFICATION_ID = 1001
        private val mutableState = MutableStateFlow(TunnelState())
        val state: StateFlow<TunnelState> = mutableState
        private val mutableTraffic = MutableStateFlow(TunnelTraffic())
        val traffic: StateFlow<TunnelTraffic> = mutableTraffic
        fun setTraffic(value: TunnelTraffic) { mutableTraffic.value = value }
        const val EXTRA_PROFILE_ID = "profile_id"
        const val ACTION_TRUST_HOST = "me.treexhd.supertunnel.TRUST_HOST"
        fun connectIntent(context: Context, profileId: String) = Intent(context, TunnelVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_PROFILE_ID, profileId)
        fun disconnectIntent(context: Context) = Intent(context, TunnelVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else null
    private lateinit var orchestrator: TunnelOrchestrator
    override fun onCreate() { super.onCreate(); orchestrator = TunnelOrchestrator(this, mutableState) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent.getStringExtra(EXTRA_PROFILE_ID))
            ACTION_TRUST_HOST -> trustHost(intent.getStringExtra(EXTRA_PROFILE_ID))
            ACTION_DISCONNECT -> stopTunnel()
        }
        return START_NOT_STICKY
    }
    private fun connect(profileId: String?) {
        createChannel(); startForeground(NOTIFICATION_ID, notification("Preparing secure tunnel"))
        if (profileId == null) { mutableState.value = TunnelState(TunnelStage.ERROR, failure = TunnelFailure("PROFILE_INVALID", TunnelStage.VALIDATING, "No profile selected")); return }
        // A new profile always replaces the old connection instead of leaving two TUN/SOCKS stacks alive.
        orchestrator.disconnect()
        orchestrator.connect(profileId)
    }
    private fun trustHost(profileId: String?) {
        if (profileId == null) return
        val app = application as me.treexhd.supertunnel.App
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val profile = app.profiles.get(profileId) ?: return@launch
            if (me.treexhd.supertunnel.data.knownhosts.KnownHostsStore(this@TunnelVpnService).trustPending(profile.ssh.host, profile.ssh.port)) connect(profileId)
        }
    }
    private fun stopTunnel() { mutableState.value = TunnelState(TunnelStage.STOPPING); orchestrator.stop(); mutableTraffic.value = TunnelTraffic(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); mutableState.value = TunnelState() }
    override fun onRevoke() { mutableState.value = TunnelState(TunnelStage.ERROR, failure = TunnelFailure("SERVICE_REVOKED", TunnelStage.ERROR, "VPN permission was revoked")); stopTunnel() }
    private fun createChannel() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tunnel status", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("SuperTunnel").setContentText(text).setOngoing(true).addAction(0, "Disconnect", Intent(this, TunnelVpnService::class.java).setAction(ACTION_DISCONNECT).let { android.app.PendingIntent.getService(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE) }).build()
}
