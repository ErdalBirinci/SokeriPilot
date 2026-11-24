package app.glcopilot.notify

import app.glcopilot.data.GlucoseBand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    private val policy = AlertPolicy(throttleMillis = 10 * 60 * 1000L)
    private val now = 1_700_000_000_000L

    @Test
    fun `in range readings never alert`() {
        assertFalse(policy.shouldAlert(GlucoseBand.IN_RANGE, now, mutedUntil = 0L))
    }

    @Test
    fun `first out of range reading alerts immediately`() {
        assertTrue(policy.shouldAlert(GlucoseBand.LOW, now, mutedUntil = 0L))
    }

    @Test
    fun `same band is throttled for ten minutes`() {
        policy.markNotified(GlucoseBand.LOW, now)
        assertFalse(policy.shouldAlert(GlucoseBand.LOW, now + 9 * 60 * 1000L, mutedUntil = 0L))
        assertTrue(policy.shouldAlert(GlucoseBand.LOW, now + 10 * 60 * 1000L, mutedUntil = 0L))
    }

    @Test
    fun `a different band alerts without waiting`() {
        policy.markNotified(GlucoseBand.LOW, now)
        assertTrue(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 60_000L, mutedUntil = 0L))
    }

    @Test
    fun `mute window suppresses alerts`() {
        assertFalse(policy.shouldAlert(GlucoseBand.HIGH, now, mutedUntil = now + 30 * 60 * 1000L))
        assertTrue(policy.shouldAlert(GlucoseBand.HIGH, now + 31 * 60 * 1000L, mutedUntil = now + 30 * 60 * 1000L))
    }
}
