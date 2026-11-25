package app.sokeripilot.data

import kotlin.math.roundToInt

/**
 * Contract for the xDrip+ local broadcast (com.eveningoutpost.dexdrip.BgEstimate).
 * No source code was copied; only the general-purpose broadcast fields documented
 * by xDrip are used.
 */
object XDripReadingMapper {

    const val ACTION_BG_ESTIMATE = "com.eveningoutpost.dexdrip.BgEstimate"
    const val EXTRA_BG_ESTIMATE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    const val EXTRA_BG_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    const val EXTRA_BG_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    const val EXTRA_TIMESTAMP = "com.eveningoutpost.dexdrip.Extras.Time"
    const val EXTRA_SENSOR_BATTERY = "com.eveningoutpost.dexdrip.Extras.SensorBattery"
    const val SOURCE = "xDrip+"

    private const val FUTURE_TOLERANCE_MILLIS = 5 * 60 * 1000L
    private const val STALE_TOLERANCE_MILLIS = 30 * 60 * 1000L
    private const val MAX_ABS_SLOPE_PER_MINUTE = 30.0

    /**
     * @param slopePerMs slope unit documented by xDrip: mg/dL / millisecond.
     * @return a validated reading, or null when the data is unusable.
     */
    fun map(
        bg: Double?,
        slopePerMs: Double?,
        slopeName: String?,
        timeMillis: Long?,
        batteryPercent: Int?,
        nowMillis: Long = System.currentTimeMillis()
    ): GlucoseReading? {
        if (bg == null || !bg.isFinite()) return null
        val mgdl = bg.roundToInt()
        if (mgdl !in GlucoseReading.MIN_MGDL..GlucoseReading.MAX_MGDL) return null

        val timestamp = timeMillis ?: nowMillis
        if (timestamp > nowMillis + FUTURE_TOLERANCE_MILLIS) return null
        if (timestamp < nowMillis - STALE_TOLERANCE_MILLIS) return null

        val named = Trend.fromDirection(slopeName)
        val trend = if (named != Trend.UNKNOWN) named else trendFromSlope(slopePerMs)

        return GlucoseReading(
            mgdl = mgdl,
            timestampMillis = timestamp,
            trend = trend,
            source = SOURCE,
            sensorBatteryPercent = batteryPercent?.takeIf { it in 0..100 }
        )
    }

    private fun trendFromSlope(slopePerMs: Double?): Trend {
        if (slopePerMs == null || !slopePerMs.isFinite()) return Trend.UNKNOWN
        val perMinute = (slopePerMs * 60_000.0)
            .coerceIn(-MAX_ABS_SLOPE_PER_MINUTE, MAX_ABS_SLOPE_PER_MINUTE)
        return Trend.fromSlope(perMinute)
    }
}
