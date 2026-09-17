package ir.omid.vpnman.data

import android.content.Context
import ir.omid.vpnman.model.AdItem
import ir.omid.vpnman.model.ManifestPayload
import ir.omid.vpnman.model.VpnServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last successfully-fetched manifest and the user's server choice so the app
 * can render a usable screen the instant it opens (previous servers + last pick) while the
 * real network refresh happens in the background, instead of showing a blank spinner on
 * every single launch.
 */
object ManifestCache {
    private const val PREFS = "vpn_man_cache"
    private const val KEY_MANIFEST = "last_manifest"
    private const val KEY_SELECTED_SERVER = "selected_server_id"
    private const val KEY_BLOCKED = "device_blocked"

    fun save(context: Context, manifest: ManifestPayload) {
        val root = JSONObject()
        root.put("maintenance", manifest.maintenance)
        root.put("minimum_app_version", manifest.minimumAppVersion)
        root.put("servers", JSONArray().apply {
            manifest.servers.forEach {
                put(
                    JSONObject()
                        .put("id", it.id)
                        .put("name", it.name)
                        .put("protocol", it.protocol)
                        .put("config", it.config)
                        .put("source_name", it.sourceName)
                )
            }
        })
        // Ads are always fresh/time-boxed campaigns; not worth persisting stale ones.
        prefs(context).edit().putString(KEY_MANIFEST, root.toString()).apply()
    }

    fun load(context: Context): ManifestPayload? = runCatching {
        val raw = prefs(context).getString(KEY_MANIFEST, null) ?: return null
        val root = JSONObject(raw)
        val serversJson = root.optJSONArray("servers") ?: JSONArray()
        val servers = buildList {
            for (i in 0 until serversJson.length()) {
                val item = serversJson.optJSONObject(i) ?: continue
                add(
                    VpnServer(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        protocol = item.optString("protocol"),
                        config = item.optString("config"),
                        sourceName = item.optString("source_name", "")
                    )
                )
            }
        }
        if (servers.isEmpty()) return null
        ManifestPayload(
            maintenance = root.optBoolean("maintenance", false),
            minimumAppVersion = root.optString("minimum_app_version", "1.0.0"),
            servers = servers,
            preConnectAds = emptyList<AdItem>(),
            postConnectAds = emptyList<AdItem>()
        )
    }.getOrNull()

    fun saveSelectedServer(context: Context, serverId: String?) {
        prefs(context).edit().putString(KEY_SELECTED_SERVER, serverId).apply()
    }

    fun loadSelectedServer(context: Context): String? = prefs(context).getString(KEY_SELECTED_SERVER, null)

    /** Wipes the cached server list and selection — the configs (UUIDs/passwords) they
     *  contain must not survive on a device the admin has just blocked. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_MANIFEST).remove(KEY_SELECTED_SERVER).apply()
    }

    fun saveBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCKED, blocked).apply()
    }

    fun isBlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_BLOCKED, false)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
