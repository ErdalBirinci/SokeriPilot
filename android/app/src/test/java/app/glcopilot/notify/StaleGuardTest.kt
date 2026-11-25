package app.glcopilot.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class StaleGuardTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `fresh data stays quiet`() {
        assertEquals(StaleAction.NONE, StaleGuard().onCheck(now, now - 60_000L))
    }

    @Test
    fun `exactly fifteen minutes is not stale yet`() {
        assertEquals(StaleAction.NONE, StaleGuard().onCheck(now, now - 15 * 60_000L))
    }

    @Test
    fun `data older than fifteen minutes alerts once`() {
        val guard = StaleGuard()

        assertEquals(StaleAction.ALERT, guard.onCheck(now, now - 16 * 60_000L))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 60_000L, now - 17 * 60_000L))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 120_000L, now - 18 * 60_000L))
    }

    @Test
    fun `a fresh reading clears the alert and re-arms it`() {
        val guard = StaleGuard()

        assertEquals(StaleAction.ALERT, guard.onCheck(now, now - 20 * 60_000L))
        assertEquals(StaleAction.CLEARED, guard.onCheck(now + 60_000L, now + 30_000L))
        assertEquals(StaleAction.NONE, guard.onCheck(now + 120_000L, now + 90_000L))
        assertEquals(StaleAction.ALERT, guard.onCheck(now + 20 * 60_000L, now + 90_000L))
    }
}
