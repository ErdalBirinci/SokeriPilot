package app.glcopilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveSummaryTest {

    private val start = 1_700_000_000_000L
    private val end = start + 30 * 60_000L

    private fun reading(minute: Long, mgdl: Int) = GlucoseReading(
        mgdl = mgdl,
        timestampMillis = start + minute * 60_000L,
        trend = Trend.FLAT,
        source = "test"
    )

    @Test
    fun `only readings from the drive are counted`() {
        val summary = DriveSummary.from(
            readings = listOf(
                reading(-10, 300),
                reading(1, 100),
                reading(10, 80),
                reading(20, 64),
                reading(28, 130)
            ),
            range = GlucoseRange(),
            startMillis = start,
            endMillis = end
        )

        assertEquals(75, summary?.inRangePercent)
        assertEquals(64, summary?.lowestMgdl)
        assertEquals(1, summary?.lowCount)
        assertEquals(30, summary?.minutes)
        assertEquals(end, summary?.endedAtMillis)
    }

    @Test
    fun `an empty drive has no summary`() {
        assertNull(
            DriveSummary.from(
                readings = listOf(reading(-5, 120)),
                range = GlucoseRange(),
                startMillis = start,
                endMillis = end
            )
        )
    }
}
