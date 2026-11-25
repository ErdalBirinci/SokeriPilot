package app.sokeripilot.notify

import app.sokeripilot.data.GlucoseBand
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

    @Test
    fun `an urgent low repeats three times every five minutes`() {
        assertTrue(policy.shouldAlert(GlucoseBand.URGENT_LOW, now, mutedUntil = 0L))
        policy.markNotified(GlucoseBand.URGENT_LOW, now)

        assertFalse(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 4 * 60_000L, 0L))
        assertTrue(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 5 * 60_000L, 0L))
        policy.markNotified(GlucoseBand.URGENT_LOW, now + 5 * 60_000L)
        assertTrue(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 10 * 60_000L, 0L))
        policy.markNotified(GlucoseBand.URGENT_LOW, now + 10 * 60_000L)
        assertTrue(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 15 * 60_000L, 0L))
        policy.markNotified(GlucoseBand.URGENT_LOW, now + 15 * 60_000L)

        assertFalse(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 20 * 60_000L, 0L))
        assertFalse(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 60 * 60_000L, 0L))
    }

    @Test
    fun `acknowledging an urgent low quiets the repeat`() {
        assertTrue(policy.shouldAlert(GlucoseBand.URGENT_LOW, now, mutedUntil = 0L))
        policy.markNotified(GlucoseBand.URGENT_LOW, now)

        val acknowledged = now + 30 * 60_000L
        assertFalse(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 5 * 60_000L, 0L, acknowledged))
        assertTrue(
            "the alert stands again after the acknowledgement window",
            policy.shouldAlert(GlucoseBand.URGENT_LOW, acknowledged + 60_000L, 0L, acknowledged)
        )
    }

    @Test
    fun `returning in range clears the urgent repeat count`() {
        policy.markNotified(GlucoseBand.URGENT_LOW, now)
        policy.markNotified(GlucoseBand.URGENT_LOW, now + 5 * 60_000L)
        policy.markNotified(GlucoseBand.URGENT_LOW, now + 10 * 60_000L)
        policy.markNotified(GlucoseBand.URGENT_LOW, now + 15 * 60_000L)
        assertFalse(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 20 * 60_000L, 0L))

        policy.reset()
        assertTrue(policy.shouldAlert(GlucoseBand.URGENT_LOW, now + 25 * 60_000L, 0L))
    }
}
