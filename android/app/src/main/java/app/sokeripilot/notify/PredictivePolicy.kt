package app.sokeripilot.notify

/**
 * At most one "low/high soon" projection per half hour: the slope moves slowly and a
 * repeated warning would only add noise next to the range alerts.
 */
class PredictivePolicy(private val throttleMillis: Long = THROTTLE_MILLIS) {

    private var lastAlertAt = 0L

    fun shouldAlert(nowMillis: Long, mutedUntil: Long): Boolean =
        nowMillis >= mutedUntil && nowMillis - lastAlertAt >= throttleMillis

    fun markAlerted(nowMillis: Long) {
        lastAlertAt = nowMillis
    }

    companion object {
        const val THROTTLE_MILLIS = 30 * 60_000L
    }
}
