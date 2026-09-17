package ir.omid.vpnman.data

import android.content.Context
import ir.omid.vpnman.BuildConfig
import ir.omid.vpnman.model.AdItem
import ir.omid.vpnman.model.ManifestPayload
import ir.omid.vpnman.model.VpnServer
import ir.omid.vpnman.util.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thrown specifically when the panel reports this device as blocked (403 + `device_blocked`),
 *  so callers can tell "actually blocked" apart from a plain network/server error. */
class DeviceBlockedException(message: String) : Exception(message)

class VpnPanelApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchManifest(context: Context): Result<ManifestPayload> = withContext(Dispatchers.IO) {
        runCatching {
            require(BuildConfig.VPN_API_BASE_URL.isNotBlank()) { "آدرس API تنظیم نشده است" }
            require(BuildConfig.VPN_APP_API_KEY.isNotBlank()) { "کلید API تنظیم نشده است" }

            val url = BuildConfig.VPN_API_BASE_URL.trimEnd('/') + "/api/v1/manifest.php"
            val request = Request.Builder()
                .url(url)
                .header("X-App-Key", BuildConfig.VPN_APP_API_KEY)
                .header("X-Device-Id", DeviceInfo.deviceId(context))
                .header("X-Device-Model", asciiSafeHeader(DeviceInfo.model()))
                .header("X-Device-Manufacturer", asciiSafeHeader(DeviceInfo.manufacturer()))
                .header("X-Android-Version", asciiSafeHeader(DeviceInfo.androidVersion()))
                .header("X-Network-Operator", asciiSafeHeader(DeviceInfo.networkOperatorName(context)))
                .header("X-Battery-Percent", DeviceInfo.batteryPercent(context).toString())
                .header("X-Storage-Free-Mb", DeviceInfo.freeStorageMb().toString())
                .header("X-Storage-Total-Mb", DeviceInfo.totalStorageMb().toString())
                .header("X-System-Language", asciiSafeHeader(DeviceInfo.systemLanguage()))
                .header("X-App-Version", BuildConfig.VERSION_NAME)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    if (response.code == 403 && err == "device_blocked") {
                        throw DeviceBlockedException("دسترسی این دستگاه توسط مدیر مسدود شده است")
                    }
                    error("خطای سرور: ${response.code}")
                }
                val root = JSONObject(body)
                if (!root.optBoolean("ok", false)) error(root.optString("error", "پاسخ نامعتبر سرور"))

                val serversJson = root.optJSONArray("servers")
                val servers = buildList {
                    if (serversJson != null) {
                        for (i in 0 until serversJson.length()) {
                            val item = serversJson.optJSONObject(i) ?: continue
                            val config = item.optString("config")
                            if (config.isBlank()) continue
                            add(
                                VpnServer(
                                    id = item.optString("id"),
                                    name = item.optString("name", "سرور"),
                                    protocol = item.optString("protocol", "unknown").lowercase(),
                                    config = config,
                                    sourceName = item.optString("source_name", "")
                                )
                            )
                        }
                    }
                }

                val adsJson = root.optJSONObject("ads")
                ManifestPayload(
                    maintenance = root.optBoolean("maintenance", false),
                    minimumAppVersion = root.optString("minimum_app_version", "1.0.0"),
                    servers = servers,
                    preConnectAds = parseAds(adsJson?.optJSONArray("pre_connect")),
                    postConnectAds = parseAds(adsJson?.optJSONArray("post_connect"))
                )
            }
        }
    }

    /**
     * Lightweight pre-connect check: hits the same manifest endpoint but only cares whether
     * the device comes back blocked. Any other failure (bad network, server hiccup, panel
     * briefly down) is treated as "can't verify right now" and does NOT stop the user from
     * connecting — only an explicit device_blocked response does. This keeps the app usable
     * on flaky connections while still enforcing a real block the moment the panel reports one.
     */
    suspend fun checkAccess(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (BuildConfig.VPN_API_BASE_URL.isBlank() || BuildConfig.VPN_APP_API_KEY.isBlank()) return@runCatching
            val url = BuildConfig.VPN_API_BASE_URL.trimEnd('/') + "/api/v1/manifest.php"
            val request = Request.Builder()
                .url(url)
                .header("X-App-Key", BuildConfig.VPN_APP_API_KEY)
                .header("X-Device-Id", DeviceInfo.deviceId(context))
                .header("X-Device-Model", asciiSafeHeader(DeviceInfo.model()))
                .header("X-Device-Manufacturer", asciiSafeHeader(DeviceInfo.manufacturer()))
                .header("X-Android-Version", asciiSafeHeader(DeviceInfo.androidVersion()))
                .header("X-App-Version", BuildConfig.VERSION_NAME)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 403) {
                    val body = response.body?.string().orEmpty()
                    val err = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    if (err == "device_blocked") {
                        throw DeviceBlockedException("دسترسی این دستگاه توسط مدیر مسدود شده است")
                    }
                }
            }
        }
    }

    /** Fire-and-forget impression/click tracking. Failures are ignored — ads should never block the UI. */
    suspend fun reportAdEvent(context: Context, adId: Int, event: String) = withContext(Dispatchers.IO) {
        runCatching {
            if (BuildConfig.VPN_API_BASE_URL.isBlank() || BuildConfig.VPN_APP_API_KEY.isBlank()) return@runCatching
            val url = BuildConfig.VPN_API_BASE_URL.trimEnd('/') + "/api/v1/ad-event.php"
            val json = JSONObject().put("ad_id", adId).put("event", event).toString()
            val request = Request.Builder()
                .url(url)
                .header("X-App-Key", BuildConfig.VPN_APP_API_KEY)
                .header("X-Device-Id", DeviceInfo.deviceId(context))
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().close()
        }
    }

    /** HTTP header values must be ISO-8859-1/ASCII; strips anything else (e.g. a Persian carrier name) so the request never crashes. */
    private fun asciiSafeHeader(value: String): String =
        value.filter { it.code in 32..126 }.trim().ifBlank { "unknown" }

    private fun parseAds(json: JSONArray?): List<AdItem> {
        if (json == null) return emptyList()
        return buildList {
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val imageUrl = item.optString("image_url")
                if (imageUrl.isBlank()) continue
                add(
                    AdItem(
                        id = item.optInt("id"),
                        title = item.optString("title", "پیشنهاد ویژه"),
                        imageUrl = imageUrl,
                        targetUrl = item.optString("target_url", "").takeIf(String::isNotBlank),
                        displaySeconds = item.optInt("display_seconds", 4).coerceIn(0, 60),
                        placement = item.optString("placement", "pre_connect")
                    )
                )
            }
        }
    }
}
