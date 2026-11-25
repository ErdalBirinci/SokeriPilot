package app.glcopilot.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class StaleGuardTest {

    private val now = 1_700_000_000_000L

    /** Monitoring starts at [now]; later readings must be timestamped at or after it. */
    private fun guard() = StaleGuard(startedAtMillis = now)

    @Test
    fun `fresh data stays quiet`() {
        assertEquals(StaleAction.NONE, guard().onCheck(now + 60_000L, now))
    }

    @Test
    fun `exactly fifteen minutes is not stale yet`() {
        assertEquals(StaleAction.NONE, guard().onCheck(now + 15 * 60_000L, now))
    }

    @Test
    fun `data older than fifteen minutes alerts once`() {
        val guard = guard()
        assertEquals(StaleAction.NONE, guard.onCheck(now, now))

        assertEquals(StaleAction.ALERT, guard.onCheck(now + 16 * 60_000L, now))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 17 * 60_000L, now))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 18 * 60_000L, now))
    }

    @Test
    fun `a fresh reading clears the alert and re-arms it`() {
        val guard = guard()
        guard.onCheck(now, now)

        assertEquals(StaleAction.ALERT, guard.onCheck(now + 20 * 60_000L, now))
        assertEquals(
            StaleAction.CLEARED,
            guard.onCheck(now + 21 * 60_000L, now + 21 * 60_000L - 30_000L)
        )
        assertEquals(
            StaleAction.NONE,
            guard.onCheck(now + 22 * 60_000L, now + 22 * 60_000L - 30_000L)
        )
        assertEquals(StaleAction.ALERT, guard.onCheck(now + 40 * 60_000L, now + 22 * 60_000L - 30_000L))
    }

    @Test
    fun `a gap that started before monitoring does not alert`() {
        // Sensor change or warm-up at home: plugging in already shows an hour-old reading.
        val guard = guard()

        assertEquals(StaleAction.NONE, guard.onCheck(now, now - 60 * 60_000L))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 60_000L, now - 61 * 60_000L))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 30 * 60_000L, now - 90 * 60_000L))
    }

    @Test
    fun `a reading that was already on the phone does not count as flowing`() {
        // Five minutes old at plug-in: younger than the stale threshold, but it arrived
        // before monitoring started, so a warming-up sensor must not trigger the alarm
        // fifteen minutes later.
        val guard = guard()

        assertEquals(StaleAction.NONE, guard.onCheck(now, now - 5 * 60_000L))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 11 * 60_000L, now - 5 * 60_000L))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 40 * 60_000L, now - 5 * 60_000L))
    }

    @Test
    fun `a reading that arrives after monitoring starts arms the alert`() {
        val guard = guard()

        // Plug-in carries an old reading from the previous sensor...
        assertEquals(StaleAction.NONE, guard.onCheck(now, now - 40 * 60_000L))
        // ...then the new sensor delivers its first value.
        assertEquals(StaleAction.NONE, guard.onCheck(now + 60_000L, now + 30_000L))
        // A real silence after that is alerted.
        assertEquals(StaleAction.ALERT, guard.onCheck(now + 20 * 60_000L, now + 30_000L))
    }

    @Test
    fun `sensor change pause suppresses the alert`() {
        val guard = guard()
        val pauseUntil = now + 120 * 60_000L
        guard.onCheck(now, now)

        assertEquals(StaleAction.NONE, guard.onCheck(now + 16 * 60_000L, now, pauseUntil))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 60 * 60_000L, now, pauseUntil))
    }

    @Test
    fun `sensor change pause clears an alert that is already showing`() {
        val guard = guard()
        val pauseUntil = now + 120 * 60_000L
        guard.onCheck(now, now)
        assertEquals(StaleAction.ALERT, guard.onCheck(now + 16 * 60_000L, now))

        assertEquals(StaleAction.CLEARED, guard.onCheck(now + 17 * 60_000L, now, pauseUntil))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 18 * 60_000L, now, pauseUntil))
    }

    @Test
    fun `after the pause ends a still silent sensor alerts again`() {
        val guard = guard()
        val pauseUntil = now + 120 * 60_000L
        guard.onCheck(now, now)

        assertEquals(StaleAction.NONE, guard.onCheck(now + 16 * 60_000L, now, pauseUntil))
        assertEquals(StaleAction.ALERT, guard.onCheck(pauseUntil + 60_000L, now, pauseUntil))
    }
}
