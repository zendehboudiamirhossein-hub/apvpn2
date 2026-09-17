package ir.omid.vpnman.data

import ir.omid.vpnman.model.VpnServer
import ir.omid.vpnman.util.ServerEndpointParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

object LatencyTester {
    suspend fun test(server: VpnServer): Int? = withContext(Dispatchers.IO) {
        val endpoint = ServerEndpointParser.parse(server.config) ?: return@withContext null
        runCatching {
            var elapsed = 0L
            Socket().use { socket ->
                elapsed = measureTimeMillis {
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 2200)
                }
            }
            elapsed.coerceAtMost(9999).toInt()
        }.getOrNull()
    }
}
