package app.glcopilot.notify

import app.glcopilot.data.GlucoseBand

/**
 * Limits alert frequency to avoid tiring the driver:
 * - in-range values never alert,
 * - the same band is not alerted again within [throttleMillis],
 * - an urgent low repeats every five minutes, at most three times, because it is the
 *   one value that should not be missed; acknowledging it quiets the repeat,
 * - no alerts while a user mute is active.
 */
class AlertPolicy(
    private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
    private val urgentRepeatMillis: Long = URGENT_REPEAT_MILLIS,
    private val maxUrgentRepeats: Int = MAX_URGENT_REPEATS
) {

    private var lastBand: GlucoseBand? = null
    private var lastNotifiedAt = 0L
    private var urgentRepeats = 0

    fun shouldAlert(
        band: GlucoseBand,
        nowMillis: Long,
        mutedUntil: Long,
        acknowledgedUntil: Long = 0L
    ): Boolean {
        if (band == GlucoseBand.IN_RANGE) return false
        if (nowMillis < mutedUntil) return false

        if (band == GlucoseBand.URGENT_LOW) {
            if (nowMillis < acknowledgedUntil) return false
            if (lastBand == GlucoseBand.URGENT_LOW) {
                if (urgentRepeats >= maxUrgentRepeats) return false
                return nowMillis - lastNotifiedAt >= urgentRepeatMillis
            }
            return true
        }

        if (band == lastBand && nowMillis - lastNotifiedAt < throttleMillis) return false
        return true
    }

    fun markNotified(band: GlucoseBand, nowMillis: Long) {
        if (band == GlucoseBand.URGENT_LOW && lastBand == GlucoseBand.URGENT_LOW) {
            urgentRepeats += 1
        } else {
            urgentRepeats = 0
        }
        lastBand = band
        lastNotifiedAt = nowMillis
    }

    fun reset() {
        lastBand = null
        lastNotifiedAt = 0L
        urgentRepeats = 0
    }

    companion object {
        const val DEFAULT_THROTTLE_MILLIS = 10 * 60 * 1000L
        const val URGENT_REPEAT_MILLIS = 5 * 60 * 1000L
        const val MAX_URGENT_REPEATS = 3
    }
}
