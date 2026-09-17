package ir.omid.vpnman.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.omid.vpnman.data.DeviceBlockedException
import ir.omid.vpnman.data.LatencyTester
import ir.omid.vpnman.data.ManifestCache
import ir.omid.vpnman.data.VpnPanelApi
import ir.omid.vpnman.model.AdItem
import ir.omid.vpnman.model.VpnServer
import ir.omid.vpnman.vpn.MyVpnService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val servers: List<VpnServer> = emptyList(),
    val selectedServerId: String? = null,
    val latencies: Map<String, Int?> = emptyMap(),
    val preConnectAds: List<AdItem> = emptyList(),
    val postConnectAds: List<AdItem> = emptyList(),
    val maintenance: Boolean = false,
    val minimumVersion: String = "1.0.0",
    val error: String? = null,
    /** True while showing a cached manifest from a previous launch, before the fresh one lands. */
    val showingCached: Boolean = false,
    /** True once the admin panel has reported this device as blocked. Overrides everything else. */
    val blocked: Boolean = false
) {
    val selectedServer: VpnServer? get() = servers.firstOrNull { it.id == selectedServerId } ?: servers.firstOrNull()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val api = VpnPanelApi()
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()
    private var manuallySelected = false

    init {
        if (ManifestCache.isBlocked(getApplication())) {
            // Cache was already wiped the moment this device was blocked; nothing to load.
            _ui.update { it.copy(loading = false, blocked = true) }
        } else {
            loadCached()
        }
        // Always refresh, even while showing the locked screen: if the admin has since
        // unblocked the device, a successful fetch clears the lock automatically.
        refresh(false)
    }

    /** Renders instantly from the last successful fetch, if any, instead of a blank spinner. */
    private fun loadCached() {
        val cached = ManifestCache.load(getApplication()) ?: return
        val savedSelection = ManifestCache.loadSelectedServer(getApplication())
        if (savedSelection != null) manuallySelected = true
        _ui.update {
            it.copy(
                loading = false,
                servers = cached.servers,
                selectedServerId = savedSelection?.takeIf { id -> cached.servers.any { s -> s.id == id } }
                    ?: cached.servers.firstOrNull()?.id,
                maintenance = cached.maintenance,
                minimumVersion = cached.minimumAppVersion,
                showingCached = true
            )
        }
    }

    fun refresh(userInitiated: Boolean = true) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = !userInitiated && it.servers.isEmpty(), refreshing = userInitiated, error = null) }
            api.fetchManifest(getApplication()).fold(
                onSuccess = { manifest ->
                    unlockIfNeeded()
                    val supported = manifest.servers.filter { it.protocol in setOf("vless", "vmess", "trojan", "ss") }
                    val previousSelection = _ui.value.selectedServerId
                    val stillValid = previousSelection?.takeIf { id -> supported.any { s -> s.id == id } }
                    // If the server the user (or the cache) had picked no longer exists in the
                    // fresh manifest, the fallback below is automatic, not a manual choice —
                    // so latency-based auto-select should be allowed to kick back in.
                    if (previousSelection != null && stillValid == null) manuallySelected = false
                    _ui.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            servers = supported,
                            selectedServerId = stillValid ?: supported.firstOrNull()?.id,
                            preConnectAds = manifest.preConnectAds,
                            postConnectAds = manifest.postConnectAds,
                            maintenance = manifest.maintenance,
                            minimumVersion = manifest.minimumAppVersion,
                            error = if (supported.isEmpty() && !manifest.maintenance) "سرور قابل پشتیبانی پیدا نشد" else null,
                            showingCached = false,
                            blocked = false
                        )
                    }
                    if (supported.isNotEmpty()) ManifestCache.save(getApplication(), manifest.copy(servers = supported))
                    if (!manifest.maintenance) testLatencies()
                },
                onFailure = { e ->
                    if (e is DeviceBlockedException) {
                        lockDevice()
                    } else {
                        _ui.update {
                            // Keep whatever we already have (cached or previous) on screen; only
                            // surface the error if there's truly nothing to show.
                            it.copy(
                                loading = false,
                                refreshing = false,
                                error = if (it.servers.isEmpty()) (e.message ?: "خطا در دریافت سرورها") else null
                            )
                        }
                    }
                }
            )
        }
    }

    /**
     * Quick pre-connect gate: called right before the app actually asks Android to bring the
     * tunnel up. Returns true if the connection should proceed. A confirmed block locks the
     * device (clearing cached configs) and returns false; any other/transient failure fails
     * open so a flaky network never stops a legitimate user from connecting.
     */
    suspend fun canConnect(): Boolean {
        if (_ui.value.blocked) return false
        val result = api.checkAccess(getApplication())
        return result.fold(
            onSuccess = { true },
            onFailure = { e ->
                if (e is DeviceBlockedException) {
                    lockDevice()
                    false
                } else {
                    true
                }
            }
        )
    }

    /** Wipes cached configs, force-disconnects any active tunnel, and shows the locked screen. */
    private fun lockDevice() {
        ManifestCache.clear(getApplication())
        ManifestCache.saveBlocked(getApplication(), true)
        forceDisconnectIfActive()
        _ui.update {
            it.copy(
                blocked = true,
                loading = false,
                refreshing = false,
                servers = emptyList(),
                selectedServerId = null,
                latencies = emptyMap(),
                error = null,
                showingCached = false
            )
        }
    }

    private fun unlockIfNeeded() {
        if (ManifestCache.isBlocked(getApplication())) ManifestCache.saveBlocked(getApplication(), false)
        if (_ui.value.blocked) _ui.update { it.copy(blocked = false) }
    }

    private fun forceDisconnectIfActive() {
        val app = getApplication<Application>()
        runCatching {
            app.startService(Intent(app, MyVpnService::class.java).setAction(MyVpnService.ACTION_DISCONNECT))
        }
    }

    fun select(server: VpnServer) {
        manuallySelected = true
        ManifestCache.saveSelectedServer(getApplication(), server.id)
        _ui.update { it.copy(selectedServerId = server.id) }
    }

    /** Picks one ad at random from the eligible list — rotates between campaigns instead of always the same one. */
    fun pickPreConnectAd(): AdItem? = _ui.value.preConnectAds.randomOrNull()
    fun pickPostConnectAd(): AdItem? = _ui.value.postConnectAds.randomOrNull()

    fun reportAdImpression(ad: AdItem) {
        viewModelScope.launch { api.reportAdEvent(getApplication(), ad.id, "impression") }
    }

    fun reportAdClick(ad: AdItem) {
        viewModelScope.launch { api.reportAdEvent(getApplication(), ad.id, "click") }
    }

    private fun testLatencies() {
        viewModelScope.launch {
            val servers = _ui.value.servers
            val semaphore = Semaphore(6)
            val results = servers.map { server ->
                async {
                    semaphore.withPermit { server.id to LatencyTester.test(server) }
                }
            }.awaitAll().toMap()
            _ui.update { state ->
                val best = results.filterValues { it != null }.minByOrNull { it.value ?: Int.MAX_VALUE }?.key
                state.copy(
                    latencies = results,
                    selectedServerId = if (!manuallySelected && best != null) best else state.selectedServerId
                )
            }
        }
    }
}
