package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlucoseRangeTest {

    private val range = GlucoseRange(low = 70, high = 180)

    @Test
    fun `urgent low is below 54`() {
        assertEquals(GlucoseBand.URGENT_LOW, range.band(53))
        assertEquals(GlucoseBand.LOW, range.band(54))
    }

    @Test
    fun `low band runs from 54 to 69`() {
        assertEquals(GlucoseBand.LOW, range.band(69))
    }

    @Test
    fun `target range is inclusive at both ends`() {
        assertEquals(GlucoseBand.IN_RANGE, range.band(70))
        assertEquals(GlucoseBand.IN_RANGE, range.band(180))
    }

    @Test
    fun `high band runs to 250`() {
        assertEquals(GlucoseBand.HIGH, range.band(181))
        assertEquals(GlucoseBand.HIGH, range.band(250))
        assertEquals(GlucoseBand.URGENT_HIGH, range.band(251))
    }

    @Test
    fun `default range is valid`() {
        assertNull(range.validate())
    }

    @Test
    fun `inverted range is rejected`() {
        assertNotNull(GlucoseRange(low = 120, high = 100).validate())
        assertNull(GlucoseRange.forTargets(120, 100))
    }

    @Test
    fun `thresholds outside the clinical window are rejected`() {
        assertNotNull(GlucoseRange(low = 40, high = 180).validate())
        assertNotNull(GlucoseRange(low = 70, high = 400).validate())
    }

    @Test
    fun `clinical window boundaries are accepted`() {
        // validate() and the UI text define 55–120 / 120–300 inclusive of the endpoints.
        assertNotNull(GlucoseRange.forTargets(55, 300))
    }

    @Test
    fun `in range percentage ignores out of range values`() {
        val range = GlucoseRange(low = 70, high = 180)
        val readings = listOf(100, 120, 200, 60).mapIndexed { index, value ->
            GlucoseReading(value, 1_000L + index, Trend.FLAT, "test")
        }
        val state = GlucoseState(readings = readings)
        assertEquals(50, state.inRangePercent(range))
        assertEquals(0, GlucoseState().inRangePercent(range))
    }
}
