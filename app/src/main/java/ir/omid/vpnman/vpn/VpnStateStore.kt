package ir.omid.vpnman.vpn

import ir.omid.vpnman.model.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Simple snapshot of live traffic counters, in bytes since the current session started. */
data class TrafficStats(val rxBytes: Long = 0L, val txBytes: Long = 0L)

object VpnStateStore {
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Wall-clock time (System.currentTimeMillis) the current session became CONNECTED, or null. */
    private val _connectedSinceMillis = MutableStateFlow<Long?>(null)
    val connectedSinceMillis: StateFlow<Long?> = _connectedSinceMillis.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficStats())
    val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

    fun update(state: ConnectionState, serverName: String = _serverName.value, error: String? = null) {
        _state.value = state
        _serverName.value = serverName
        _error.value = error
        if (state == ConnectionState.CONNECTED) {
            if (_connectedSinceMillis.value == null) _connectedSinceMillis.value = System.currentTimeMillis()
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            _connectedSinceMillis.value = null
            _traffic.value = TrafficStats()
        }
    }

    fun updateTraffic(rxBytes: Long, txBytes: Long) {
        _traffic.value = TrafficStats(rxBytes, txBytes)
    }
}
