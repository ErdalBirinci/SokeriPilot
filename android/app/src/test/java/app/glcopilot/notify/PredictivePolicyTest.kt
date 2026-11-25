package app.glcopilot.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictivePolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `the first projection alerts`() {
        assertTrue(PredictivePolicy().shouldAlert(now, mutedUntil = 0L))
    }

    @Test
    fun `a second projection waits half an hour`() {
        val policy = PredictivePolicy()
        policy.markAlerted(now)

        assertFalse(policy.shouldAlert(now + 29 * 60_000L, 0L))
        assertTrue(policy.shouldAlert(now + 30 * 60_000L, 0L))
    }

    @Test
    fun `mute suppresses the projection`() {
        assertFalse(PredictivePolicy().shouldAlert(now, mutedUntil = now + 30 * 60_000L))
        assertTrue(PredictivePolicy().shouldAlert(now + 31 * 60_000L, mutedUntil = now + 30 * 60_000L))
    }
}
