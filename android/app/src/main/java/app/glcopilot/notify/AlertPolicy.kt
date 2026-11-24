package app.glcopilot.notify

import app.glcopilot.data.GlucoseBand

/**
 * Limits alert frequency to avoid tiring the driver:
 * - in-range values never alert,
 * - the same band is not alerted again within [throttleMillis],
 * - no alerts while a user mute is active.
 */
class AlertPolicy(private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS) {

    private var lastBand: GlucoseBand? = null
    private var lastNotifiedAt = 0L

    fun shouldAlert(band: GlucoseBand, nowMillis: Long, mutedUntil: Long): Boolean {
        if (band == GlucoseBand.IN_RANGE) return false
        if (nowMillis < mutedUntil) return false
        if (band == lastBand && nowMillis - lastNotifiedAt < throttleMillis) return false
        return true
    }

    fun markNotified(band: GlucoseBand, nowMillis: Long) {
        lastBand = band
        lastNotifiedAt = nowMillis
    }

    fun reset() {
        lastBand = null
        lastNotifiedAt = 0L
    }

    companion object {
        const val DEFAULT_THROTTLE_MILLIS = 10 * 60 * 1000L
    }
}
