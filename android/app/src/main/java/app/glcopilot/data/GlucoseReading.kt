package app.glcopilot.data

import java.util.Locale

/** Glucose range classes. Thresholds are in mg/dL. */
enum class GlucoseBand(val label: String) {
    URGENT_LOW("Urgent low"),
    LOW("Low"),
    IN_RANGE("In range"),
    HIGH("High"),
    URGENT_HIGH("Urgent high")
}

/** CGM trend arrow; arrow glyphs were chosen to be readable at a glance on the car screen. */
enum class Trend(val arrow: String, val label: String) {
    DOUBLE_UP("↑↑", "Rising fast"),
    SINGLE_UP("↑", "Rising"),
    FORTY_FIVE_UP("↗", "Rising slowly"),
    FLAT("→", "Steady"),
    FORTY_FIVE_DOWN("↘", "Falling slowly"),
    SINGLE_DOWN("↓", "Falling"),
    DOUBLE_DOWN("↓↓", "Falling fast"),
    UNKNOWN("–", "No trend");

    companion object {

        /** @param slopePerMinute change in mg/dL per minute. */
        fun fromSlope(slopePerMinute: Double?): Trend {
            if (slopePerMinute == null || !slopePerMinute.isFinite()) return UNKNOWN
            return when {
                slopePerMinute >= 3.0 -> DOUBLE_UP
                slopePerMinute >= 2.0 -> SINGLE_UP
                slopePerMinute >= 1.0 -> FORTY_FIVE_UP
                slopePerMinute > -1.0 -> FLAT
                slopePerMinute > -2.0 -> FORTY_FIVE_DOWN
                slopePerMinute > -3.0 -> SINGLE_DOWN
                else -> DOUBLE_DOWN
            }
        }

        /** Maps Nightscout and xDrip+ "direction" / "slopeName" strings. */
        fun fromDirection(direction: String?): Trend {
            val key = direction
                ?.trim()
                ?.lowercase(Locale.US)
                ?.replace(" ", "")
                ?.replace("_", "")
                ?: return UNKNOWN
            return when (key) {
                "doubleup" -> DOUBLE_UP
                "singleup" -> SINGLE_UP
                "fortyfiveup" -> FORTY_FIVE_UP
                "flat" -> FLAT
                "fortyfivedown" -> FORTY_FIVE_DOWN
                "singledown" -> SINGLE_DOWN
                "doubledown" -> DOUBLE_DOWN
                else -> UNKNOWN
            }
        }
    }
}

/**
 * A single CGM reading. The value range is validated when the object is created,
 * so malformed data from the network or a broadcast never reaches the UI.
 */
data class GlucoseReading(
    val mgdl: Int,
    val timestampMillis: Long,
    val trend: Trend,
    val source: String,
    val sensorBatteryPercent: Int? = null
) {
    init {
        require(mgdl in MIN_MGDL..MAX_MGDL) { "Glucose value is outside the physiological range" }
    }

    companion object {
        const val MIN_MGDL = 20
        const val MAX_MGDL = 600
        const val MGDL_PER_MMOL = 18.0182
    }
}

/** Shared state for the phone and car screens. */
data class GlucoseState(
    val readings: List<GlucoseReading> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastError: String? = null,
    val configuredSource: String? = null,
    val sensorStatus: SensorStatus? = null,
    val sessionStartedAtMillis: Long? = null
) {
    val latest: GlucoseReading?
        get() = readings.firstOrNull()

    /** Readings seen during the current drive, or all of them while monitoring is off. */
    private fun sessionReadings(): List<GlucoseReading> =
        sessionStartedAtMillis
            ?.let { start -> readings.filter { it.timestampMillis >= start } }
            ?: readings

    fun inRangePercent(range: GlucoseRange): Int {
        val session = sessionReadings()
        if (session.isEmpty()) return 0
        val inRange = session.count { range.band(it.mgdl) == GlucoseBand.IN_RANGE }
        return inRange * 100 / session.size
    }
}
