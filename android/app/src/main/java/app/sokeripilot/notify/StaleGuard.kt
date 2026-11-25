package app.sokeripilot.notify

import app.sokeripilot.data.TimeAgo

/** What a periodic staleness check should do to the driver alert. */
enum class StaleAction { NONE, ALERT, CLEARED }

/**
 * One alert per stale episode, and only for a silence the app actually watched begin.
 *
 * A sensor change or warm-up can leave the phone with an hour-old reading for up to two
 * hours. That is a state, not an alarm: the cockpit and the status notification still show
 * the reading age, and a high-priority alert on top of it only trains the driver to ignore
 * alerts. A reading therefore counts as flowing only when it arrived after monitoring
 * started; a value that was already on the phone when the car connected does not count,
 * even if it is only a few minutes old.
 *
 * Once such a reading has arrived, a later silence alerts exactly once, until fresh data
 * returns.
 *
 * [pausedUntil] is the end of an explicit sensor-change pause and acts as a snooze: an
 * alert already posted is cleared, and the next alert waits for the pause to end.
 */
class StaleGuard(
    private val thresholdMillis: Long = TimeAgo.STALE_AFTER_MILLIS,
    private val startedAtMillis: Long = System.currentTimeMillis()
) {

    private var alerted = false
    private var sawFreshData = false

    fun onCheck(nowMillis: Long, lastReadingMillis: Long, pausedUntil: Long = 0L): StaleAction {
        val fresh = nowMillis - lastReadingMillis <= thresholdMillis

        // Data has to reach the app after monitoring began to prove the pipeline is
        // alive; an older value can come from a sensor that is still warming up.
        if (fresh && lastReadingMillis >= startedAtMillis) sawFreshData = true

        if (fresh) return clearIfAlerted()

        // Nothing has arrived since monitoring started: warm-up or a sensor change,
        // not a pipeline that died under the driver.
        if (!sawFreshData) return StaleAction.NONE

        if (nowMillis < pausedUntil) return clearIfAlerted()

        if (alerted) return StaleAction.NONE
        alerted = true
        return StaleAction.ALERT
    }

    /** Drops a posted alert exactly once, when data returns or the driver pauses it. */
    private fun clearIfAlerted(): StaleAction {
        if (!alerted) return StaleAction.NONE
        alerted = false
        return StaleAction.CLEARED
    }
}
