package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlucoseProjectionTest {

    private val now = 1_700_000_000_000L

    private fun reading(minutesAgo: Long, mgdl: Int) =
        GlucoseReading(
            mgdl = mgdl,
            timestampMillis = now - minutesAgo * 60_000L,
            trend = Trend.UNKNOWN,
            source = "test"
        )

    @Test
    fun `a steady fall warns before the low threshold`() {
        val readings = listOf(reading(0, 110), reading(5, 118), reading(10, 125))

        val warning = GlucoseProjection.lowWarning(readings, lowMgdl = 70, nowMillis = now)

        assertEquals(27, warning?.minutes)
        assertEquals(70, warning?.projectedMgdl)
    }

    @Test
    fun `a flat trace never warns`() {
        val readings = listOf(reading(0, 110), reading(5, 111), reading(10, 110))

        assertNull(GlucoseProjection.lowWarning(readings, lowMgdl = 70, nowMillis = now))
    }

    @Test
    fun `a slow fall beyond the window does not warn`() {
        // 0.2 mg/dL per minute: below the minimum slope.
        val readings = listOf(reading(0, 160), reading(5, 161), reading(10, 162))

        assertNull(GlucoseProjection.lowWarning(readings, lowMgdl = 70, nowMillis = now))
    }

    @Test
    fun `a value already below the threshold is not a projection`() {
        val readings = listOf(reading(0, 65), reading(5, 75), reading(10, 85))

        assertNull(GlucoseProjection.lowWarning(readings, lowMgdl = 70, nowMillis = now))
    }

    @Test
    fun `a fast rise warns before the high threshold`() {
        val readings = listOf(reading(0, 160), reading(5, 150), reading(10, 140))

        val warning = GlucoseProjection.highWarning(readings, highMgdl = 180, nowMillis = now)

        assertEquals(10, warning?.minutes)
        assertEquals(180, warning?.projectedMgdl)
    }

    @Test
    fun `too few readings produce no projection`() {
        assertNull(GlucoseProjection.lowWarning(listOf(reading(0, 110)), 70, now))
    }

    @Test
    fun `an old trace is ignored`() {
        val readings = listOf(reading(20, 110), reading(25, 120), reading(30, 130))
        assertNull(GlucoseProjection.lowWarning(readings, lowMgdl = 70, nowMillis = now))
    }

    @Test
    fun `the slope is mgdl per minute`() {
        val slope = GlucoseProjection.slopePerMinute(
            listOf(reading(0, 110), reading(5, 118), reading(10, 125)),
            now
        )
        assertEquals(-1.5, slope ?: 0.0, 0.05)
    }
}
