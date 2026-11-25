package app.glcopilot.notify

import app.glcopilot.data.TimeAgo

/** What a periodic staleness check should do to the driver alert. */
enum class StaleAction { NONE, ALERT, CLEARED }

/**
 * One alert per stale episode: once the driver has been told that data stopped,
 * the guard stays quiet until a fresh reading arrives and the data goes stale again.
 */
class StaleGuard(private val thresholdMillis: Long = TimeAgo.STALE_AFTER_MILLIS) {

    private var alerted = false

    fun onCheck(nowMillis: Long, lastReadingMillis: Long): StaleAction {
        if (nowMillis - lastReadingMillis <= thresholdMillis) {
            if (!alerted) return StaleAction.NONE
            alerted = false
            return StaleAction.CLEARED
        }
        if (alerted) return StaleAction.NONE
        alerted = true
        return StaleAction.ALERT
    }
}
