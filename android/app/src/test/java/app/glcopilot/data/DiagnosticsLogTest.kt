package app.glcopilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {

    private fun event(index: Int) = DiagnosticEvent(
        atMillis = index.toLong(),
        packageName = "pkg.$index",
        appLabel = "App $index",
        outcome = DiagnosticEvent.Outcome.ACCEPTED,
        valueMgdl = 120 + index
    )

    @Test
    fun `keeps only the newest ten events`() {
        val log = DiagnosticsLog()
        repeat(12) { log.record(event(it)) }

        val events = log.events.value
        assertEquals(10, events.size)
        assertEquals("App 11", events.first().appLabel)
        assertEquals("App 2", events.last().appLabel)
    }

    @Test
    fun `newest event is first`() {
        val log = DiagnosticsLog()
        log.record(event(1))
        log.record(event(2))

        assertEquals(listOf("App 2", "App 1"), log.events.value.map { it.appLabel })
    }

    @Test
    fun `rejected events keep their reason`() {
        val log = DiagnosticsLog()
        log.record(
            DiagnosticEvent(
                atMillis = 1L,
                packageName = "com.dexcom.g7",
                appLabel = "Dexcom G7",
                outcome = DiagnosticEvent.Outcome.REJECTED,
                reason = Rejection.STALE
            )
        )

        val only = log.events.value.single()
        assertEquals(DiagnosticEvent.Outcome.REJECTED, only.outcome)
        assertEquals(Rejection.STALE, only.reason)
        assertEquals(null, only.valueMgdl)
    }

    @Test
    fun `clear empties the log`() {
        val log = DiagnosticsLog()
        log.record(event(1))
        log.clear()

        assertTrue(log.events.value.isEmpty())
    }
}
