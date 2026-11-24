package app.glcopilot.data

import android.content.Context

/**
 * Settings live only in a private on-device SharedPreferences file and are excluded
 * from backup. The raw Nightscout key is never written.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun xDripEnabled(): Boolean = prefs.getBoolean(KEY_XDRIP, true)

    /** Did the user touch the switch themselves? Auto-detection never overrides this choice. */
    fun xDripUserChose(): Boolean = prefs.getBoolean(KEY_XDRIP_CHOICE, false)

    fun setXDripEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_XDRIP, enabled)
            .putBoolean(KEY_XDRIP_CHOICE, true)
            .apply()
    }

    /** Enables listening when a compatible CGM app is found, without overriding the user's choice. */
    fun autoEnableXDrip(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_XDRIP, enabled).apply()
    }

    fun notificationsEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFY, true)

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY, enabled).apply()
    }

    fun nightscoutConfig(): NightscoutConfig? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val digest = prefs.getString(KEY_SECRET, null) ?: return null
        val validated = NightscoutSecurity.validateBaseUrl(url)
        if (validated !is UrlValidation.Valid) return null
        if (!NightscoutSecurity.isValidDigest(digest)) return null
        return NightscoutConfig(validated.normalizedBaseUrl, digest)
    }

    /** @return an error to show the user, or null on success. */
    fun saveNightscout(baseUrl: String, apiSecret: String?): String? {
        val validated = NightscoutSecurity.validateBaseUrl(baseUrl)
        if (validated is UrlValidation.Invalid) return validated.reason
        val base = (validated as UrlValidation.Valid).normalizedBaseUrl

        val digest = if (!apiSecret.isNullOrBlank()) {
            NightscoutSecurity.sha1Hex(apiSecret)
        } else {
            prefs.getString(KEY_SECRET, null)
        } ?: return "Nightscout API key is required."

        // Store the SHA-1 digest the protocol requires; never write the raw secret to disk.
        prefs.edit()
            .putString(KEY_URL, base)
            .putString(KEY_SECRET, digest)
            .apply()
        return null
    }

    fun clearNightscout() {
        prefs.edit().remove(KEY_URL).remove(KEY_SECRET).apply()
    }

    fun glucoseRange(): GlucoseRange {
        val low = prefs.getInt(KEY_LOW, GlucoseRange.DEFAULT.low)
        val high = prefs.getInt(KEY_HIGH, GlucoseRange.DEFAULT.high)
        return GlucoseRange.forTargets(low, high) ?: GlucoseRange.DEFAULT
    }

    /** @return an error to show the user, or null on success. */
    fun saveRange(low: Int, high: Int): String? {
        val range = GlucoseRange.forTargets(low, high)
            ?: return "Thresholds are invalid: low must be between 55 and 120, high between 120 and 300 mg/dL, and low must be below high."
        prefs.edit().putInt(KEY_LOW, range.low).putInt(KEY_HIGH, range.high).apply()
        return null
    }

    fun units(): Units = when (prefs.getString(KEY_UNITS, Units.MG_DL.name)) {
        Units.MMOL_L.name -> Units.MMOL_L
        else -> Units.MG_DL
    }

    fun saveUnits(units: Units) {
        prefs.edit().putString(KEY_UNITS, units.name).apply()
    }

    fun pollMinutes(): Long = prefs.getInt(KEY_POLL, 5).coerceIn(2, 15).toLong()

    /** Background monitoring with a car notification while driving. */
    fun monitorWhileDriving(): Boolean = prefs.getBoolean(KEY_MONITOR, true)

    fun setMonitorWhileDriving(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR, enabled).apply()
    }

    /** Starts monitoring the moment the phone is projected into a head unit. */
    fun autoStartOnCar(): Boolean = prefs.getBoolean(KEY_AUTO_CAR, true)

    fun setAutoStartOnCar(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CAR, enabled).apply()
    }

    /** Reads values from other CGM apps' notifications (requires the one-time system grant). */
    fun autoReadNotifications(): Boolean = prefs.getBoolean(KEY_AUTO_NOTIFICATIONS, true)

    fun setAutoReadNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_NOTIFICATIONS, enabled).apply()
    }

    /** Time until which alerts are muted (epoch ms); 0 = no mute. */
    fun mutedUntil(): Long = prefs.getLong(KEY_MUTED_UNTIL, 0L)

    fun muteAlerts(minutes: Long) {
        prefs.edit()
            .putLong(KEY_MUTED_UNTIL, System.currentTimeMillis() + minutes * 60_000L)
            .apply()
    }

    fun sourceLabel(): String? = when {
        prefs.getString(KEY_URL, null) != null -> "Nightscout"
        prefs.getBoolean(KEY_XDRIP, true) -> "xDrip+"
        prefs.getBoolean(KEY_AUTO_NOTIFICATIONS, true) -> "CGM app"
        else -> null
    }

    companion object {
        private const val PREFS_NAME = "glucopilot_settings"
        private const val KEY_URL = "nightscout_url"
        private const val KEY_SECRET = "nightscout_secret_sha1"
        private const val KEY_LOW = "target_low"
        private const val KEY_HIGH = "target_high"
        private const val KEY_UNITS = "display_units"
        private const val KEY_POLL = "poll_minutes"
        private const val KEY_XDRIP = "xdrip_enabled"
        private const val KEY_XDRIP_CHOICE = "xdrip_user_choice"
        private const val KEY_NOTIFY = "notifications_enabled"
        private const val KEY_MONITOR = "monitor_while_driving"
        private const val KEY_AUTO_CAR = "auto_start_on_car"
        private const val KEY_AUTO_NOTIFICATIONS = "read_cgm_notifications"
        private const val KEY_MUTED_UNTIL = "alerts_muted_until"
    }
}
