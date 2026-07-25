package me.treexhd.supertunnel

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import me.treexhd.supertunnel.domain.model.*
import me.treexhd.supertunnel.data.room.ProfileSaveResult
import me.treexhd.supertunnel.domain.state.TunnelStage
import me.treexhd.supertunnel.service.TunnelVpnService
import me.treexhd.supertunnel.service.TunnelLogBook
import me.treexhd.supertunnel.ui.theme.TunnelTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import me.treexhd.supertunnel.domain.state.TunnelTraffic

class MainActivity : ComponentActivity() {
    private var selectedProfileId: String? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) startTunnel() }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { TunnelApp(onProfileSelected = { selectedProfileId = it }, onConnect = ::requestTunnel, onDisconnect = ::stopTunnel) } }
    private fun requestTunnel() { VpnService.prepare(this)?.let(vpnPermission::launch) ?: startTunnel() }
    private fun startTunnel() { selectedProfileId?.let { ContextCompat.startForegroundService(this, TunnelVpnService.connectIntent(this, it)) } }
    private fun stopTunnel() { ContextCompat.startForegroundService(this, TunnelVpnService.disconnectIntent(this)) }
}

private data class BottomDestination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val bottomDestinations = listOf(
    BottomDestination("Home", Icons.Filled.Home, Icons.Outlined.Home),
    BottomDestination("Configs", Icons.Filled.Storage, Icons.Outlined.Storage),
    BottomDestination("Logs", Icons.AutoMirrored.Filled.ReceiptLong, Icons.AutoMirrored.Outlined.ReceiptLong),
    BottomDestination("Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@Composable private fun TunnelApp(onProfileSelected: (String) -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as App
    val profiles by app.profiles.observeAll().collectAsStateWithLifecycle(emptyList())
    val serviceState by TunnelVpnService.state.collectAsStateWithLifecycle()
    val traffic by TunnelVpnService.traffic.collectAsStateWithLifecycle()
    val logLines by TunnelLogBook.lines.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(profiles) { if (selected !in profiles.map { it.id }) selected = profiles.firstOrNull()?.id; selected?.let(onProfileSelected) }
    TunnelTheme { Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                bottomDestinations.forEachIndexed { i, destination ->
                    val selectedTab = tab == i
                    NavigationBarItem(
                        selected = selectedTab,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> Home(Modifier.padding(padding).padding(20.dp), profiles, selected, { id -> selected = id; onProfileSelected(id) }, serviceState.stage, serviceState.failure?.code, serviceState.failure?.userMessage, serviceState.failure?.technicalMessage, traffic, onConnect, onDisconnect)
            1 -> Configs(Modifier.padding(padding), profiles, selected, { selected = it; onProfileSelected(it) }, app)
            2 -> LogScreen(Modifier.padding(padding).padding(20.dp), serviceState.stage, logLines, TunnelLogBook::clear)
            else -> SettingsScreen(Modifier.padding(padding).padding(20.dp))
        }
    } }
}

@Composable private fun SettingsScreen(modifier: Modifier) = Column(modifier) {
    Text("Settings", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text("Appearance", style = MaterialTheme.typography.titleMedium)
    Text("Theme follows the Android light or dark appearance setting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
    Text("Security", style = MaterialTheme.typography.titleMedium)
    Text("Secrets are protected by Android Keystore. Local SOCKS is always loopback-only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun TunnelProfile.summary() = if (mode == TunnelMode.SLIPSTREAM) "$name · ${slipstream?.domain.orEmpty()} · SLIPSTREAM" else "$name · ${payload?.endpointHost?.ifBlank { ssh.host } ?: ssh.host}:${payload?.endpointPort?.takeIf { it in 1..65535 } ?: ssh.port} · ${mode.name}"

@Composable private fun Home(modifier: Modifier, profiles: List<TunnelProfile>, selectedId: String?, select: (String) -> Unit, stage: TunnelStage, code: String?, message: String?, technical: String?, traffic: TunnelTraffic, onConnect: () -> Unit, onDisconnect: () -> Unit) = Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
    val profile = profiles.firstOrNull { it.id == selectedId }
    var pickerOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var ipv4 by rememberSaveable { mutableStateOf("Not checked") }
    var ipv6 by rememberSaveable { mutableStateOf("Not checked") }
    var checkingExit by remember { mutableStateOf(false) }
    Text("SuperTunnel", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    val isConnected = stage == TunnelStage.CONNECTED
    val isActive = stage != TunnelStage.IDLE
    Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Button(
            onClick = if (isActive) onDisconnect else onConnect,
            enabled = isActive || profile != null,
            modifier = Modifier.size(165.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) androidx.compose.ui.graphics.Color(0xFF35B96B) else MaterialTheme.colorScheme.background,
                contentColor = if (isConnected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = if (isActive) "Stop tunnel" else "Start tunnel", modifier = Modifier.size(69.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(if (isConnected) "CONNECTED" else if (isActive) stage.name.replace('_', ' ') else "NOT CONNECTED", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, color = if (isConnected) androidx.compose.ui.graphics.Color(0xFF55D68A) else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    Spacer(Modifier.height(18.dp))
    Box {
        Button(enabled = profiles.isNotEmpty(), onClick = { pickerOpen = true }, shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
            Text(profile?.summary() ?: "Select a profile")
        }
        DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }, modifier = Modifier.fillMaxWidth(.92f)) {
            profiles.forEach { candidate -> DropdownMenuItem(
                text = { Column { Text(candidate.name, style = MaterialTheme.typography.titleSmall); Text(candidate.summary().substringAfter(" · "), style = MaterialTheme.typography.bodySmall) } },
                onClick = { select(candidate.id); pickerOpen = false; if (stage == TunnelStage.CONNECTED) onConnect() }
            ) }
        }
    }
    Spacer(Modifier.height(8.dp)); Text(profile?.let { if (it.mode == TunnelMode.SLIPSTREAM) "Profile: ${it.name}\nDomain: ${it.slipstream?.domain.orEmpty()}\nResolver: ${it.slipstream?.resolver.orEmpty()}\nMode: SLIPSTREAM" else "Profile: ${it.name}\nGateway: ${it.payload?.endpointHost?.ifBlank { it.ssh.host } ?: it.ssh.host}:${it.payload?.endpointPort?.takeIf { port -> port in 1..65535 } ?: it.ssh.port}\nMode: ${it.mode.name}" } ?: "Create a profile in Configs")
    if (message != null) Text("${code ?: "ERROR"}: $message", color = MaterialTheme.colorScheme.error)
    if (!technical.isNullOrBlank()) Text(technical, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Traffic", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Download\n${formatKilobytes(traffic.downloadedBytes)}", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text("Upload\n${formatKilobytes(traffic.uploadedBytes)}", Modifier.weight(1f), color = androidx.compose.ui.graphics.Color(0xFF55D6B4), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Text("Connectivity test", style = MaterialTheme.typography.titleMedium)
            Button(enabled = isConnected && !checkingExit, shape = RectangleShape, onClick = {
                checkingExit = true; ipv4 = "Checking…"; ipv6 = "Checking…"; TunnelLogBook.add("Tunnel exit check started")
                scope.launch {
                    try {
                        val (v4, v6) = tunnelExitIps()
                        ipv4 = v4; ipv6 = v6
                        TunnelLogBook.add("Tunnel exit check completed: IPv4=$v4 IPv6=$v6")
                    } catch (cancelled: CancellationException) {
                        // Leaving Home cancels the UI request; this is not a tunnel error.
                        throw cancelled
                    } catch (error: Exception) {
                        ipv4 = "Failed"; ipv6 = "Failed"
                        TunnelLogBook.add("ERROR tunnel exit check: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                    } finally { checkingExit = false }
                }
            }) { Text(if (checkingExit) "Checking…" else "Check tunnel Exit IP") }
            Text("IPv4: $ipv4", style = MaterialTheme.typography.bodyMedium)
            Text("IPv6: $ipv6", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatKilobytes(bytes: Long): String = String.format(Locale.US, "%.1f KB", bytes.coerceAtLeast(0L) / 1024.0)

/** Uses the local SOCKS endpoint explicitly and forces each address family. */
private suspend fun tunnelExitIps(): Pair<String, String> = withContext(Dispatchers.IO) {
    coroutineScope {
        val v4 = async { probeExitIp("https://api4.ipify.org") }
        val v6 = async { probeExitIp("https://api6.ipify.org") }
        (v4.await() to v6.await())
    }
}

private suspend fun probeExitIp(url: String): String = withContext(Dispatchers.IO) {
    withTimeoutOrNull(3_500L) { runCatching { openTunnelUrl(url).trim() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Unavailable" } ?: "Timeout"
}

private fun openTunnelUrl(url: String): String {
    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080))
    val connection = (URL(url).openConnection(proxy) as java.net.HttpURLConnection).apply {
        connectTimeout = 3_000; readTimeout = 3_000; setRequestProperty("Connection", "close")
    }
    return try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
}
@Composable private fun LogScreen(modifier: Modifier, stage: TunnelStage, lines: List<String>, clear: () -> Unit) = Column(modifier) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("Connection logs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); Button(onClick = clear, enabled = lines.isNotEmpty(), shape = RectangleShape) { Text("Clear logs") } }
    Text("Current state: $stage")
    Spacer(Modifier.height(12.dp))
    if (lines.isEmpty()) Text("No connection attempt yet.") else LazyColumn { items(lines) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) } }
}

@Composable private fun Configs(modifier: Modifier, profiles: List<TunnelProfile>, selected: String?, select: (String) -> Unit, app: App) {
    var editing by remember { mutableStateOf<TunnelProfile?>(null) }
    if (editing != null) { ProfileEditor(modifier, editing!!, app, { saved -> app.profiles.save(saved) }, { result, saved -> if (result == ProfileSaveResult.Saved) { editing = null; select(saved.id) } }, { editing = null }); return }
    Column(modifier.padding(16.dp)) { Row { Text("Configs", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall); Button(onClick = { editing = TunnelProfile() }) { Text("Add") } }; Spacer(Modifier.height(8.dp))
        LazyColumn { items(profiles, key = { it.id }) { p -> ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text(p.summary().substringAfter(" · ")) }, modifier = Modifier.fillMaxWidth().clickable { select(p.id) }, trailingContent = { TextButton(onClick = { editing = p }) { Text("Edit") } }); HorizontalDivider() } }
    }
}

@Composable private fun ProfileEditor(modifier: Modifier, initial: TunnelProfile, app: App, save: suspend (TunnelProfile) -> ProfileSaveResult, saved: (ProfileSaveResult, TunnelProfile) -> Unit, cancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var mode by remember(initial.id) { mutableStateOf(initial.mode) }
    var modesOpen by remember { mutableStateOf(false) }
    var user by remember(initial.id) { mutableStateOf(initial.ssh.username) }
    var password by remember(initial.id) { mutableStateOf("") }
    var payloadHost by remember { mutableStateOf(initial.payload?.endpointHost.orEmpty()) }
    var payloadPort by remember { mutableStateOf(initial.payload?.endpointPort?.toString() ?: "80") }
    var rawPayload by remember { mutableStateOf(initial.payload?.raw.orEmpty()) }
    var wsPath by remember { mutableStateOf(initial.payload?.webSocketPath ?: "/") }
    var slipDomain by remember(initial.id) { mutableStateOf(initial.slipstream?.domain.orEmpty()) }
    var slipResolver by remember(initial.id) { mutableStateOf(initial.slipstream?.resolver.orEmpty().ifBlank { "1.1.1.1" }) }
    var slipCongestion by remember(initial.id) { mutableStateOf(initial.slipstream?.congestionControl ?: "bbr") }
    var slipKeepAlive by remember(initial.id) { mutableStateOf(initial.slipstream?.keepAliveIntervalMs?.toString() ?: "400") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var resolverOpen by remember { mutableStateOf(false) }
    val slipstream = mode == TunnelMode.SLIPSTREAM
    LazyColumn(modifier.padding(20.dp)) { item {
        Text(if (slipstream) "Slipstream profile" else "SSH profile", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp)); Field("Name", name) { name = it }
        Box { OutlinedButton(onClick = { modesOpen = true }, Modifier.fillMaxWidth()) { Text("Mode: ${mode.name}") }
            DropdownMenu(modesOpen, { modesOpen = false }) { TunnelMode.entries.forEach { value -> DropdownMenuItem({ Text(value.name) }, { mode = value; modesOpen = false }) } }
        }
        if (slipstream) {
            Text("QUIC over DNS — no SSH, WebSocket, TLS endpoint, or Payload.", style = MaterialTheme.typography.bodySmall)
            Field("Tunnel domain", slipDomain) { slipDomain = it }
            OutlinedButton(onClick = { resolverOpen = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RectangleShape) { Text("DNS resolver: $slipResolver") }
            DropdownMenu(resolverOpen, { resolverOpen = false }) {
                listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "94.140.14.14").forEach { resolver -> DropdownMenuItem({ Text(resolver) }, { slipResolver = resolver; resolverOpen = false }) }
                HorizontalDivider(); DropdownMenuItem({ Text("Custom resolver") }, { resolverOpen = false })
            }
            Field("Custom DNS resolver (IP or host:port)", slipResolver) { slipResolver = it }
            Text("Tunnel type: SOCKS5 (default)", style = MaterialTheme.typography.titleSmall)
            Text("Your Slipstream server must expose a SOCKS5 backend. SSH/WebSocket settings are not used.", style = MaterialTheme.typography.bodySmall)
            Text("Resolver transport: UDP DNS on port 53. DoT/DoH URLs cannot be used directly by this native Slipstream client; use Android Private DNS or a local DoT/DoH-to-UDP resolver bridge, then enter that bridge's IP:port here.", style = MaterialTheme.typography.bodySmall)
            Field("Congestion control (bbr / dcubic)", slipCongestion) { slipCongestion = it.lowercase() }
            Field("Keep-alive ms", slipKeepAlive) { slipKeepAlive = it }
        } else {
            Text("SSH target: 127.0.0.1:22 (behind the WebSocket gateway)", style = MaterialTheme.typography.bodySmall)
            Field("Username", user) { user = it }; Field("Password (leave blank to keep existing)", password) { password = it }
            Text("WebSocket gateway", style = MaterialTheme.typography.titleMedium)
            Field("WebSocket gateway host", payloadHost) { payloadHost = it }; Field("WebSocket gateway port", payloadPort) { payloadPort = it }
            PayloadField(rawPayload) { rawPayload = it }; Field("WebSocket path", wsPath) { wsPath = it }
        }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row { Button(shape = RectangleShape, onClick = {
            val candidate = if (slipstream) initial.copy(name = name.trim(), mode = mode, slipstream = SlipstreamConfig(slipDomain.trim(), slipResolver.trim(), slipCongestion.trim(), slipKeepAlive.toIntOrNull() ?: 400), updatedAt = System.currentTimeMillis()) else {
                val secret = if (password.isBlank()) initial.ssh.passwordSecretId else app.secrets.put(password.toCharArray())
                val port = payloadPort.toIntOrNull() ?: 80
                initial.copy(name = name.trim(), mode = mode, ssh = SshConfig("127.0.0.1", 22, user.trim(), passwordSecretId = secret), payload = PayloadConfig(raw = rawPayload, endpointHost = payloadHost.trim(), endpointPort = port, webSocket = true, webSocketPath = wsPath), updatedAt = System.currentTimeMillis())
            }
            if (candidate.name.isBlank()) saveError = "Profile Name cannot be empty" else scope.launch { when (val result = save(candidate)) { ProfileSaveResult.DuplicateName -> saveError = "Profile Name already exists"; ProfileSaveResult.InvalidName -> saveError = "Profile Name cannot be empty"; ProfileSaveResult.Saved -> saved(result, candidate) } }
        }) { Text("Save") }; Spacer(Modifier.width(12.dp)); TextButton(onClick = cancel) { Text("Cancel") } }
    } }
}
@Composable private fun Field(label: String, value: String, update: (String) -> Unit) { OutlinedTextField(value, update, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) }, singleLine = true) }
@Composable private fun PayloadField(value: String, update: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = update,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        label = { Text("Raw payload (optional)") },
        minLines = 4,
        maxLines = 10
    )
}
