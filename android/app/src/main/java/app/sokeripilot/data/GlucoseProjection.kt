package app.sokeripilot.data

import kotlin.math.ceil
import kotlin.math.roundToInt

/** "At this rate the threshold is about [minutes] away." */
data class ProjectionWarning(val minutes: Int, val projectedMgdl: Int)

/**
 * Turns the last few readings into an early warning before the value crosses the target
 * range. This is a straight-line projection of the measured slope, not a prediction model
 * and not dosing advice; it is only used while driving and only once per half hour.
 *
 * Guard rails: at least three readings spanning five minutes inside the last fifteen
 * minutes, and a slope of at least 0.5 mg/dL per minute, so a flat or noisy trace never
 * produces a warning.
 */
object GlucoseProjection {

    const val WINDOW_MILLIS = 15 * 60_000L
    const val MIN_READINGS = 3
    const val MIN_SLOPE_MGDL_PER_MINUTE = 0.5
    const val LOW_HORIZON_MILLIS = 30 * 60_000L
    const val HIGH_HORIZON_MILLIS = 45 * 60_000L

    fun lowWarning(readings: List<GlucoseReading>, lowMgdl: Int, nowMillis: Long): ProjectionWarning? =
        warning(readings, lowMgdl, nowMillis, LOW_HORIZON_MILLIS, falling = true)

    fun highWarning(readings: List<GlucoseReading>, highMgdl: Int, nowMillis: Long): ProjectionWarning? =
        warning(readings, highMgdl, nowMillis, HIGH_HORIZON_MILLIS, falling = false)

    /** Least-squares slope in mg/dL per minute, or null when the window is too thin. */
    fun slopePerMinute(readings: List<GlucoseReading>, nowMillis: Long): Double? {
        val recent = readings
            .filter { it.timestampMillis in (nowMillis - WINDOW_MILLIS)..nowMillis }
            .sortedBy { it.timestampMillis }
        if (recent.size < MIN_READINGS) return null
        val first = recent.first().timestampMillis
        if (recent.last().timestampMillis - first < 5 * 60_000L) return null

        val xs = recent.map { (it.timestampMillis - first) / 60_000.0 }
        val ys = recent.map { it.mgdl.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var numerator = 0.0
        var denominator = 0.0
        for (index in xs.indices) {
            numerator += (xs[index] - meanX) * (ys[index] - meanY)
            denominator += (xs[index] - meanX) * (xs[index] - meanX)
        }
        if (denominator == 0.0) return null
        return numerator / denominator
    }

    private fun warning(
        readings: List<GlucoseReading>,
        thresholdMgdl: Int,
        nowMillis: Long,
        horizonMillis: Long,
        falling: Boolean
    ): ProjectionWarning? {
        val latest = readings.firstOrNull() ?: return null
        if (nowMillis - latest.timestampMillis > WINDOW_MILLIS) return null

        val slope = slopePerMinute(readings, nowMillis) ?: return null
        if (falling) {
            if (slope > -MIN_SLOPE_MGDL_PER_MINUTE) return null
            if (latest.mgdl <= thresholdMgdl) return null
        } else {
            if (slope < MIN_SLOPE_MGDL_PER_MINUTE) return null
            if (latest.mgdl >= thresholdMgdl) return null
        }

        val minutes = (thresholdMgdl - latest.mgdl) / slope
        if (!minutes.isFinite() || minutes <= 0.0) return null
        if (minutes * 60_000.0 > horizonMillis) return null

        val projected = (latest.mgdl + slope * minutes).roundToInt()
        return ProjectionWarning(minutes = ceil(minutes).toInt().coerceAtLeast(1), projectedMgdl = projected)
    }
}
