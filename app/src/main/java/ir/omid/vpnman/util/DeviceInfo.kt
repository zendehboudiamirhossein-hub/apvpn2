package ir.omid.vpnman.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.UUID

/**
 * Generates and persists a stable per-install device identifier, and collects
 * basic device/app info sent to the panel so the admin can see connected devices
 * (model, manufacturer, Android/app version, carrier name, battery, free storage,
 * system language) and their IP on check-in.
 *
 * No personal data is collected — only hardware/software info of the kind visible
 * in any app's crash/analytics report. Deliberately excludes IMEI, phone number,
 * and any other identifier tied to the person rather than the install/device.
 */
object DeviceInfo {
    private const val PREFS = "vpn_man_device"
    private const val KEY_DEVICE_ID = "device_id"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun model(): String = Build.MODEL ?: "unknown"

    fun manufacturer(): String = Build.MANUFACTURER ?: "unknown"

    fun androidVersion(): String = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    /** Carrier/network name (e.g. "Irancell"), not the phone number or SIM serial. No permission required. */
    fun networkOperatorName(context: Context): String = runCatching {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")

    /** Battery charge percentage (0-100), read via a sticky broadcast — no permission required. */
    fun batteryPercent(context: Context): Int = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }.getOrDefault(-1)

    /** Free internal storage, in whole megabytes. */
    fun freeStorageMb(): Long = runCatching {
        val stat = StatFs(android.os.Environment.getDataDirectory().path)
        (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
    }.getOrDefault(-1L)

    /** Total internal storage, in whole megabytes. */
    fun totalStorageMb(): Long = runCatching {
        val stat = StatFs(android.os.Environment.getDataDirectory().path)
        (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024)
    }.getOrDefault(-1L)

    /** System UI language, e.g. "fa" or "en". */
    fun systemLanguage(): String = Locale.getDefault().language ?: "unknown"
}
