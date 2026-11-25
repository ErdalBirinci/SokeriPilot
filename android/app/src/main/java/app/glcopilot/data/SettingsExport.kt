package app.glcopilot.data

import org.json.JSONException
import org.json.JSONObject

/** Everything a reinstall needs, without the raw Nightscout key (only its digest). */
data class SettingsSnapshot(
    val nightscoutUrl: String?,
    val nightscoutSecretSha1: String?,
    val low: Int,
    val high: Int,
    val units: String,
    val pollMinutes: Int,
    val xDripEnabled: Boolean,
    val xDripUserChose: Boolean,
    val notificationsEnabled: Boolean,
    val monitorWhileDriving: Boolean,
    val autoStartOnCar: Boolean,
    val autoReadNotifications: Boolean,
    val predictiveAlerts: Boolean,
    val healthConnectEnabled: Boolean
)

object SettingsExport {

    const val FORMAT = "glucopilot-settings"
    const val VERSION = 1

    fun toJson(snapshot: SettingsSnapshot): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("nightscoutUrl", snapshot.nightscoutUrl ?: JSONObject.NULL)
        put("nightscoutSecretSha1", snapshot.nightscoutSecretSha1 ?: JSONObject.NULL)
        put("low", snapshot.low)
        put("high", snapshot.high)
        put("units", snapshot.units)
        put("pollMinutes", snapshot.pollMinutes)
        put("xDripEnabled", snapshot.xDripEnabled)
        put("xDripUserChose", snapshot.xDripUserChose)
        put("notificationsEnabled", snapshot.notificationsEnabled)
        put("monitorWhileDriving", snapshot.monitorWhileDriving)
        put("autoStartOnCar", snapshot.autoStartOnCar)
        put("autoReadNotifications", snapshot.autoReadNotifications)
        put("predictiveAlerts", snapshot.predictiveAlerts)
        put("healthConnectEnabled", snapshot.healthConnectEnabled)
    }.toString(2)

    /** Null for anything that is not a valid export; nothing is applied in that case. */
    fun fromJson(raw: String): SettingsSnapshot? = try {
        val json = JSONObject(raw)
        val low = json.getInt("low")
        val high = json.getInt("high")
        val units = json.getString("units")
        when {
            json.optString("format") != FORMAT -> null
            json.optInt("version") != VERSION -> null
            GlucoseRange.forTargets(low, high) == null -> null
            Units.entries.none { it.name == units } -> null
            else -> SettingsSnapshot(
                nightscoutUrl = json.stringOrNull("nightscoutUrl"),
                nightscoutSecretSha1 = json.stringOrNull("nightscoutSecretSha1"),
                low = low,
                high = high,
                units = units,
                pollMinutes = json.optInt("pollMinutes", 5),
                xDripEnabled = json.optBoolean("xDripEnabled", true),
                xDripUserChose = json.optBoolean("xDripUserChose", false),
                notificationsEnabled = json.optBoolean("notificationsEnabled", true),
                monitorWhileDriving = json.optBoolean("monitorWhileDriving", true),
                autoStartOnCar = json.optBoolean("autoStartOnCar", true),
                autoReadNotifications = json.optBoolean("autoReadNotifications", true),
                predictiveAlerts = json.optBoolean("predictiveAlerts", true),
                healthConnectEnabled = json.optBoolean("healthConnectEnabled", false)
            )
        }
    } catch (_: JSONException) {
        null
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
