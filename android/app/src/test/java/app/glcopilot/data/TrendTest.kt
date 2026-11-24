package app.glcopilot.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendTest {

    @Test
    fun `slope thresholds map to dexcom arrows`() {
        assertEquals(Trend.DOUBLE_UP, Trend.fromSlope(3.0))
        assertEquals(Trend.SINGLE_UP, Trend.fromSlope(2.0))
        assertEquals(Trend.FORTY_FIVE_UP, Trend.fromSlope(1.0))
        assertEquals(Trend.FLAT, Trend.fromSlope(0.0))
        assertEquals(Trend.FLAT, Trend.fromSlope(-0.9))
        assertEquals(Trend.FORTY_FIVE_DOWN, Trend.fromSlope(-1.5))
        assertEquals(Trend.SINGLE_DOWN, Trend.fromSlope(-2.5))
        assertEquals(Trend.DOUBLE_DOWN, Trend.fromSlope(-3.0))
    }

    @Test
    fun `missing or invalid slope is unknown`() {
        assertEquals(Trend.UNKNOWN, Trend.fromSlope(null))
        assertEquals(Trend.UNKNOWN, Trend.fromSlope(Double.NaN))
        assertEquals(Trend.UNKNOWN, Trend.fromSlope(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `nightscout directions are mapped`() {
        assertEquals(Trend.DOUBLE_UP, Trend.fromDirection("DoubleUp"))
        assertEquals(Trend.SINGLE_UP, Trend.fromDirection("SingleUp"))
        assertEquals(Trend.FORTY_FIVE_UP, Trend.fromDirection("FortyFiveUp"))
        assertEquals(Trend.FLAT, Trend.fromDirection("Flat"))
        assertEquals(Trend.FORTY_FIVE_DOWN, Trend.fromDirection("fortyfivedown"))
        assertEquals(Trend.SINGLE_DOWN, Trend.fromDirection("Single_Down"))
        assertEquals(Trend.DOUBLE_DOWN, Trend.fromDirection("DoubleDown"))
    }

    @Test
    fun `unknown directions are safe`() {
        assertEquals(Trend.UNKNOWN, Trend.fromDirection(null))
        assertEquals(Trend.UNKNOWN, Trend.fromDirection("NOT COMPUTABLE"))
        assertEquals(Trend.UNKNOWN, Trend.fromDirection("Rate Out Of Range"))
    }
}
