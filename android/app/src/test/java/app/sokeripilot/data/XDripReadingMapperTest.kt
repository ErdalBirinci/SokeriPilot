package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class XDripReadingMapperTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `valid broadcast maps to a reading`() {
        val reading = XDripReadingMapper.map(
            bg = 128.0,
            slopePerMs = 0.00002,
            slopeName = "FortyFiveDown",
            timeMillis = now - 60_000,
            batteryPercent = 87,
            nowMillis = now
        )

        assertNotNull(reading)
        assertEquals(128, reading!!.mgdl)
        assertEquals(Trend.FORTY_FIVE_DOWN, reading.trend)
        assertEquals("xDrip+", reading.source)
        assertEquals(87, reading.sensorBatteryPercent)
        assertEquals(now - 60_000, reading.timestampMillis)
    }

    @Test
    fun `slope is converted from per millisecond to per minute`() {
        val reading = XDripReadingMapper.map(
            bg = 120.0,
            slopePerMs = 0.00003,
            slopeName = null,
            timeMillis = now,
            batteryPercent = null,
            nowMillis = now
        )
        assertEquals(Trend.FORTY_FIVE_UP, reading!!.trend)
    }

    @Test
    fun `named slope wins over numeric slope`() {
        val reading = XDripReadingMapper.map(150.0, 0.001, "Flat", now, null, now)
        assertEquals(Trend.FLAT, reading!!.trend)
    }

    @Test
    fun `out of range values are rejected`() {
        assertNull(XDripReadingMapper.map(900.0, null, "Flat", now, null, now))
        assertNull(XDripReadingMapper.map(10.0, null, "Flat", now, null, now))
        assertNull(XDripReadingMapper.map(null, null, "Flat", now, null, now))
    }

    @Test
    fun `stale and future broadcasts are rejected`() {
        assertNull(
            XDripReadingMapper.map(120.0, null, "Flat", now - 31 * 60_000L, null, now)
        )
        assertNull(
            XDripReadingMapper.map(120.0, null, "Flat", now + 6 * 60_000L, null, now)
        )
    }

    @Test
    fun `timestamp falls back to now`() {
        val reading = XDripReadingMapper.map(120.0, null, "Flat", null, null, now)
        assertEquals(now, reading!!.timestampMillis)
    }

    @Test
    fun `implausible battery values are dropped`() {
        assertNull(XDripReadingMapper.map(120.0, null, "Flat", now, 300, now)!!.sensorBatteryPercent)
        assertNull(XDripReadingMapper.map(120.0, null, "Flat", now, -1, now)!!.sensorBatteryPercent)
    }
}
