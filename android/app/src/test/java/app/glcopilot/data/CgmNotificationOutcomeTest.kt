package app.glcopilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CgmNotificationOutcomeTest {

    private val now = 1_700_000_000_000L

    private fun evaluate(
        packageName: String = "com.dexcom.g7",
        label: String? = "Dexcom G7",
        texts: List<String?>,
        postTime: Long = now - 60_000,
        ongoing: Boolean = false
    ) = CgmNotificationParser.evaluate(
        packageName = packageName,
        appLabel = label,
        texts = texts,
        postTimeMillis = postTime,
        ongoing = ongoing,
        nowMillis = now
    )

    private fun rejection(outcome: ParseOutcome): Rejection =
        (outcome as ParseOutcome.Rejected).reason

    @Test
    fun `unrelated notification is ignored`() {
        assertEquals(ParseOutcome.Ignored, evaluate(packageName = "com.example.chat", label = "Chat", texts = listOf("see you at 8")))
    }

    @Test
    fun `unit from an unrelated app is rejected as not cgm`() {
        val outcome = evaluate(packageName = "com.example.chat", label = "Chat", texts = listOf("128 mg/dL"))
        assertEquals(Rejection.NOT_CGM_APP, rejection(outcome))
    }

    @Test
    fun `known cgm app without a value is rejected`() {
        val outcome = evaluate(texts = listOf("Dexcom G7", "Sensor warmup"))
        assertEquals(Rejection.NO_VALUE, rejection(outcome))
    }

    @Test
    fun `implausible value is rejected as out of range`() {
        assertEquals(Rejection.OUT_OF_RANGE, rejection(evaluate(texts = listOf("900 mg/dL"))))
        assertEquals(Rejection.OUT_OF_RANGE, rejection(evaluate(texts = listOf("40 mmol/L"))))
    }

    @Test
    fun `old notification is rejected as stale`() {
        val outcome = evaluate(texts = listOf("128 mg/dL"), postTime = now - 61 * 60_000L)
        assertEquals(Rejection.STALE, rejection(outcome))
    }

    @Test
    fun `accepted reading keeps the source app label`() {
        val outcome = evaluate(texts = listOf("Dexcom G7", "128 mg/dL ↗", "2 min ago"))
        assertTrue(outcome is ParseOutcome.Accepted)
        val reading = (outcome as ParseOutcome.Accepted).reading
        assertEquals(128, reading.mgdl)
        assertEquals("Dexcom G7", reading.source)
    }
}
