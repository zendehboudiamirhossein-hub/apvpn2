package ir.omid.vpnman.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ir.omid.vpnman.BuildConfig
import ir.omid.vpnman.model.AdItem
import ir.omid.vpnman.model.ConnectionState
import ir.omid.vpnman.model.VpnServer
import ir.omid.vpnman.util.fa
import ir.omid.vpnman.util.toPersianDigits
import ir.omid.vpnman.vpn.TrafficStats
import ir.omid.vpnman.vpn.VpnStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onConnect: (VpnServer) -> Unit,
    onDisconnect: () -> Unit
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val connection by VpnStateStore.state.collectAsStateWithLifecycle()
    val vpnError by VpnStateStore.error.collectAsStateWithLifecycle()
    val connectedSince by VpnStateStore.connectedSinceMillis.collectAsStateWithLifecycle()
    val traffic by VpnStateStore.traffic.collectAsStateWithLifecycle()
    var showServers by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var pendingAd by remember { mutableStateOf<AdItem?>(null) }
    var adServer by remember { mutableStateOf<VpnServer?>(null) }
    var postConnectAd by remember { mutableStateOf<AdItem?>(null) }
    var postConnectAdDismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun tryConnect(server: VpnServer) {
        // Reflect the tap immediately: canConnect() below does a network round-trip before
        // the VPN service actually starts, and until now the button gave zero feedback during
        // that gap, making a connect tap feel like it "did nothing" for a moment. Disconnect
        // doesn't have this gap since it never waits on a network call.
        VpnStateStore.update(ConnectionState.CONNECTING, server.name)
        scope.launch {
            if (viewModel.canConnect()) {
                onConnect(server)
            } else {
                // Blocked: ui.blocked flips to true and swaps the whole screen to the
                // locked-out state, so just undo the optimistic spinner here.
                VpnStateStore.update(ConnectionState.DISCONNECTED)
            }
        }
    }

    val compact = LocalConfiguration.current.screenHeightDp < 700
    val selected = ui.selectedServer
    val errorText = vpnError ?: ui.error
    val updateRequired = remember(ui.minimumVersion) {
        isNewerVersion(ui.minimumVersion, BuildConfig.VERSION_NAME)
    }

    LaunchedEffect(connection) {
        if (connection == ConnectionState.CONNECTED) {
            if (postConnectAd == null && !postConnectAdDismissed) {
                val ad = viewModel.pickPostConnectAd()
                if (ad != null) {
                    postConnectAd = ad
                    viewModel.reportAdImpression(ad)
                }
            }
        } else {
            postConnectAd = null
            postConnectAdDismissed = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B2440), Color(0xFF243056), Color(0xFF1E2740))
                )
            )
    ) {
        AnimatedBackdrop(connection)

        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Header(
                    loading = ui.refreshing,
                    onRefresh = { viewModel.refresh(true) },
                    onAbout = { showAbout = true }
                )
                if (ui.showingCached && !ui.loading) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "نمایش فهرست قبلی؛ در حال به‌روزرسانی…",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF7A889E)
                    )
                }
                Spacer(Modifier.height(if (compact) 18.dp else 26.dp))

                if (ui.blocked) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(64.dp).background(Color(0x33C93D50), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⛔", fontSize = 30.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("دسترسی این دستگاه مسدود شده است", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        "مدیر سرویس دسترسی این دستگاه را مسدود کرده و کانفیگ‌های ذخیره‌شده پاک شدند",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { viewModel.refresh(true) }, enabled = !ui.refreshing) {
                        Text(if (ui.refreshing) "در حال بررسی…" else "بررسی دوباره")
                    }
                    Spacer(Modifier.weight(1f))
                } else if (ui.loading) {
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(14.dp))
                    Text("در حال دریافت سرورها…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                } else if (updateRequired) {
                    Spacer(Modifier.weight(1f))
                    Text("↑", fontSize = 48.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text("نسخه جدید اپ لازم است", style = MaterialTheme.typography.titleLarge)
                    Text("حداقل نسخه مورد نیاز: ${ui.minimumVersion}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { viewModel.refresh(true) }) { Text("بررسی دوباره") }
                    Spacer(Modifier.weight(1f))
                } else if (ui.maintenance) {
                    Spacer(Modifier.weight(1f))
                    Text("☁", fontSize = 48.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text("سرویس موقتاً در حال بروزرسانی است", style = MaterialTheme.typography.titleLarge)
                    Text("چند دقیقه دیگر دوباره امتحان کنید", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                } else {
                    ConnectionStatus(connection)
                    Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
                    PowerButton(
                        state = connection,
                        enabled = selected != null,
                        compact = compact,
                        onClick = {
                            if (connection == ConnectionState.CONNECTED || connection == ConnectionState.CONNECTING || connection == ConnectionState.RECONNECTING) {
                                onDisconnect()
                            } else if (selected != null) {
                                val ad = viewModel.pickPreConnectAd()
                                if (ad != null) {
                                    adServer = selected
                                    pendingAd = ad
                                    viewModel.reportAdImpression(ad)
                                } else {
                                    tryConnect(selected)
                                }
                            }
                        }
                    )
                    Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                    Text(
                        when (connection) {
                            ConnectionState.CONNECTED -> "اتصال امن برقرار است"
                            ConnectionState.CONNECTING -> "در حال ساخت اتصال امن…"
                            ConnectionState.RECONNECTING -> "اتصال قطع شد، در حال تلاش دوباره…"
                            ConnectionState.DISCONNECTING -> "در حال قطع اتصال…"
                            ConnectionState.ERROR -> "اتصال برقرار نشد"
                            else -> "برای اتصال، دکمه را لمس کنید"
                        },
                        color = if (connection == ConnectionState.ERROR) Color(0xFFFFB2BA)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (connection == ConnectionState.CONNECTED && connectedSince != null) {
                        Spacer(Modifier.height(6.dp))
                        SessionInfo(connectedSince = connectedSince!!, traffic = traffic)
                    }
                    Spacer(Modifier.height(if (compact) 14.dp else 24.dp))
                    ServerCard(selected, ui.latencies[selected?.id], onClick = { showServers = true })
                    Spacer(Modifier.height(14.dp))
                    postConnectAd?.let { ad ->
                        PostConnectAdBanner(
                            ad = ad,
                            onClick = { viewModel.reportAdClick(ad) },
                            onDismiss = { postConnectAd = null; postConnectAdDismissed = true }
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    if (errorText != null) ErrorCard(errorText)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    if (showServers) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showServers = false },
            sheetState = sheetState,
            containerColor = Color(0xFF212C4A)
        ) {
            ServerList(ui.servers, ui.selectedServerId, ui.latencies) { server ->
                viewModel.select(server)
                showServers = false
            }
        }
    }

    pendingAd?.let { ad ->
        PreConnectAdDialog(
            ad = ad,
            onFinished = {
                pendingAd = null
                adServer?.let(::tryConnect)
                adServer = null
            },
            onCancel = {
                pendingAd = null
                adServer = null
            },
            onView = { viewModel.reportAdClick(ad) }
        )
    }

    if (showAbout) {
        Dialog(onDismissRequest = { showAbout = false }) {
            Surface(shape = RoundedCornerShape(26.dp), color = Color(0xFF263252)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("◈", fontSize = 38.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("چوچول VPN", style = MaterialTheme.typography.headlineSmall)
                    Text("نسخه ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = { showAbout = false }, modifier = Modifier.fillMaxWidth()) { Text("بستن") }
                }
            }
        }
    }
}

@Composable
private fun AnimatedBackdrop(state: ConnectionState) {
    val transition = rememberInfiniteTransition(label = "background")
    val drift by transition.animateFloat(
        initialValue = -22f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(5200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    val accent = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF43E8B6)
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFF70A7FF)
        ConnectionState.ERROR -> Color(0xFFFF6375)
        else -> Color(0xFF586BFF)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = (-95f + drift).dp, y = 95.dp)
                .size(285.dp)
                .scale(breathe)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.20f), Color.Transparent)),
                    CircleShape
                )
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (90f - drift).dp, y = 80.dp)
                .size(340.dp)
                .scale(1.05f + (breathe - 1f) * 0.6f)
                .background(
                    Brush.radialGradient(listOf(Color(0xFF7DE3C3).copy(alpha = 0.10f), Color.Transparent)),
                    CircleShape
                )
        )
    }
}

@Composable
private fun Header(loading: Boolean, onRefresh: () -> Unit, onAbout: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("چوچول VPN", style = MaterialTheme.typography.headlineSmall)
            Text("ساده، سریع و امن", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
            } else {
                Text("↻", fontSize = 25.sp, color = Color.White)
            }
        }
        IconButton(onClick = onAbout) {
            Text("ⓘ", fontSize = 22.sp, color = Color.White)
        }
    }
}

@Composable
private fun ConnectionStatus(state: ConnectionState) {
    val statusColor = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF62E6BD)
        ConnectionState.CONNECTING, ConnectionState.DISCONNECTING, ConnectionState.RECONNECTING -> Color(0xFF8EB6FF)
        ConnectionState.ERROR -> Color(0xFFFF8793)
        else -> Color(0xFF8B96AA)
    }
    val statusBackground = when (state) {
        ConnectionState.CONNECTED -> Color(0x2229D6A3)
        ConnectionState.CONNECTING, ConnectionState.DISCONNECTING, ConnectionState.RECONNECTING -> Color(0x222F80ED)
        ConnectionState.ERROR -> Color(0x33C93D50)
        else -> Color(0x16FFFFFF)
    }

    Row(
        Modifier
            .background(statusBackground, RoundedCornerShape(50.dp))
            .border(1.dp, statusColor.copy(alpha = 0.28f), RoundedCornerShape(50.dp))
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(statusColor, CircleShape))
        Spacer(Modifier.width(8.dp))
        AnimatedContent(targetState = state, label = "status") { s ->
            Text(
                when (s) {
                    ConnectionState.CONNECTED -> "متصل"
                    ConnectionState.CONNECTING -> "در حال اتصال"
                    ConnectionState.RECONNECTING -> "اتصال مجدد"
                    ConnectionState.DISCONNECTING -> "در حال قطع"
                    ConnectionState.ERROR -> "خطای اتصال"
                    else -> "آماده اتصال"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (s == ConnectionState.ERROR) Color(0xFFFFDDE1) else Color.White
            )
        }
    }
}

@Composable
private fun PowerButton(state: ConnectionState, enabled: Boolean, compact: Boolean, onClick: () -> Unit) {
    val busy = state == ConnectionState.CONNECTING || state == ConnectionState.DISCONNECTING || state == ConnectionState.RECONNECTING
    val connected = state == ConnectionState.CONNECTED
    val error = state == ConnectionState.ERROR
    val buttonScale by animateFloatAsState(
        targetValue = if (connected) 1.045f else 1f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "powerScale"
    )
    val transition = rememberInfiniteTransition(label = "powerAnim")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1350, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val softPulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "softPulse"
    )

    val outer = if (compact) 164.dp else 205.dp
    val halo = if (compact) 154.dp else 193.dp
    val ring = if (compact) 146.dp else 184.dp
    val inner = if (compact) 126.dp else 156.dp
    val iconSize = if (compact) 50.sp else 62.sp
    val accent = when {
        connected -> Color(0xFF62E6BD)
        busy -> Color(0xFF82AFFF)
        error -> Color(0xFFFF7E8D)
        else -> Color(0xFFB9C6DD)
    }
    val haptics = LocalHapticFeedback.current

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(outer).scale(buttonScale)) {
        Box(
            Modifier
                .size(halo)
                .scale(if (connected || busy) pulse else 1f)
                .background(accent.copy(alpha = if (connected) 0.11f else if (busy) 0.08f else 0.035f), CircleShape)
        )
        Box(
            Modifier
                .size(ring)
                .scale(if (connected) softPulse else 1f)
                .border(1.dp, accent.copy(alpha = if (connected || busy) 0.55f else 0.25f), CircleShape)
        )
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(ring).rotate(rotation),
                strokeWidth = 3.dp,
                color = accent
            )
        }
        Box(
            Modifier
                .size(inner)
                .background(
                    Brush.radialGradient(
                        when {
                            connected -> listOf(Color(0xFF255E4E), Color(0xFF163329))
                            error -> listOf(Color(0xFF522A38), Color(0xFF2A1E27))
                            else -> listOf(Color(0xFF283C60), Color(0xFF1A2438))
                        }
                    ),
                    CircleShape
                )
                .border(1.dp, accent.copy(alpha = 0.55f), CircleShape)
                .clickable(enabled = enabled && !busy) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Text("⏻", fontSize = iconSize, color = accent, fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
private fun SessionInfo(connectedSince: Long, traffic: TrafficStats) {
    var elapsedSeconds by remember(connectedSince) { mutableIntStateOf(0) }
    LaunchedEffect(connectedSince) {
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - connectedSince) / 1000).toInt().coerceAtLeast(0)
            delay(1000)
        }
    }
    val h = elapsedSeconds / 3600
    val m = (elapsedSeconds % 3600) / 60
    val s = elapsedSeconds % 60
    val duration = if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(duration.toPersianDigits(), color = Color(0xFF8FA7FF), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(3.dp).background(Color(0xFF3A455C), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            "↓${humanBytes(traffic.rxBytes)} ↑${humanBytes(traffic.txBytes)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes.fa()}B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exp.toDouble())
    return "%.1f".format(value).toPersianDigits() + units[exp - 1]
}

@Composable
private fun ServerCard(server: VpnServer?, latency: Int?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xD9121A2A),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF26334A))
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(Color(0x247DE3C3), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("⚡", fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(server?.name ?: "سروری انتخاب نشده", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    server?.let {
                        "${protocolLabel(it.protocol)}${if (it.sourceName.isNotBlank()) " • ${it.sourceName}" else ""}"
                    } ?: "لیست سرورها را باز کنید",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (latency != null) {
                Text("${latency.fa()} ms", color = latencyColor(latency), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(7.dp))
            Text("‹", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorCard(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    val friendly = friendlyError(text)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF431D27),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0x88FF7E8D))
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(Color(0xFFFF7E8D), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("!", color = Color(0xFF2A0B12), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("اتصال برقرار نشد", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(friendly, color = Color(0xFFFFDDE1), style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
                Text(if (expanded) "بستن جزئیات" else "جزئیات فنی", color = Color(0xFFFFB7C0))
            }
            if (expanded) {
                Text(
                    text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x66130A0D), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    color = Color(0xFFFFC8CE),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

@Composable
private fun ServerList(
    servers: List<VpnServer>,
    selectedId: String?,
    latencies: Map<String, Int?>,
    onSelect: (VpnServer) -> Unit
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("انتخاب سرور", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "سرور سریع‌تر به‌صورت خودکار انتخاب می‌شود",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text("◉", fontSize = 23.sp, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(color = Color(0xFF34405F))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
            items(servers, key = { it.id }) { server ->
                val selected = server.id == selectedId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(server) }
                        .background(if (selected) Color(0x187DE3C3) else Color.Transparent, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(40.dp).background(Color(0x13FFFFFF), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(protocolShort(server.protocol), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            protocolLabel(server.protocol),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val latency = latencies[server.id]
                    if (latency != null) {
                        Text("${latency.fa()} ms", color = latencyColor(latency), style = MaterialTheme.typography.labelMedium)
                    } else {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
                    }
                    if (selected) {
                        Spacer(Modifier.width(10.dp))
                        Text("✓", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun PreConnectAdDialog(ad: AdItem, onFinished: () -> Unit, onCancel: () -> Unit, onView: () -> Unit) {
    val context = LocalContext.current
    var seconds by remember(ad.id) { mutableIntStateOf(ad.displaySeconds) }
    LaunchedEffect(ad.id) {
        while (seconds > 0) {
            delay(1000)
            seconds--
        }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF263252)
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = ad.imageUrl,
                    contentDescription = ad.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(Color(0xFF18213A), RoundedCornerShape(20.dp))
                )
                Spacer(Modifier.height(16.dp))
                Text(ad.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                if (!ad.targetUrl.isNullOrBlank()) {
                    TextButton(onClick = {
                        onView()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.targetUrl))) }
                    }) {
                        Text("مشاهده پیشنهاد")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onFinished,
                    enabled = seconds <= 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        if (seconds > 0) "اتصال تا ${seconds.fa()} ثانیه دیگر" else "اتصال به وی پی ان",
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                TextButton(onClick = onCancel) { Text("انصراف") }
            }
        }
    }
}

@Composable
private fun PostConnectAdBanner(ad: AdItem, onClick: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !ad.targetUrl.isNullOrBlank()) {
                onClick()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.targetUrl))) }
            },
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF263252),
        border = BorderStroke(1.dp, Color(0xFF34405F))
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ad.imageUrl,
                contentDescription = ad.title,
                modifier = Modifier
                    .size(52.dp)
                    .background(Color(0xFF18213A), RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ad.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (!ad.targetUrl.isNullOrBlank()) {
                    Text("برای مشاهده لمس کنید", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = onDismiss) { Text("✕", color = Color(0xFF78859A)) }
        }
    }
}

private fun friendlyError(text: String): String {
    val v = text.lowercase()
    return when {
        "xray.xudp.basekey" in v -> "هسته اتصال به‌درستی آماده نشده بود؛ این مورد در نسخه ۱.۰.۲ اصلاح شده است."
        "vless without tls" in v -> "این سرور از VLESS قدیمی بدون TLS استفاده می‌کند. سرور دیگری را انتخاب کنید."
        "trojan without tls" in v -> "این سرور Trojan بدون TLS است و توسط هسته جدید پذیرفته نمی‌شود."
        "failed to parse json config" in v || "config error" in v -> "تنظیمات این سرور با هسته فعلی سازگار نیست. سرور دیگری را امتحان کنید."
        "timeout" in v || "timed out" in v -> "پاسخ سرور دیر رسید. یک سرور با پینگ کمتر انتخاب کنید."
        "network" in v || "connection" in v -> "ارتباط با سرور برقرار نشد. اینترنت یا سرور را بررسی کنید."
        else -> "سرور پاسخ مناسب نداد. سرور دیگری را امتحان کنید."
    }
}

private fun protocolLabel(value: String) = when (value.lowercase()) {
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    "ss" -> "Shadowsocks"
    else -> value.uppercase()
}

private fun protocolShort(value: String) = when (value.lowercase()) {
    "vless" -> "VL"
    "vmess" -> "VM"
    "trojan" -> "TR"
    "ss" -> "SS"
    else -> "VPN"
}

private fun latencyColor(ms: Int) = when {
    ms < 120 -> Color(0xFF7DE3C3)
    ms < 250 -> Color(0xFFFFD166)
    else -> Color(0xFFFF7E86)
}

private fun isNewerVersion(required: String, current: String): Boolean {
    val a = required.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val b = current.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val size = maxOf(a.size, b.size)
    for (i in 0 until size) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av > bv
    }
    return false
}
