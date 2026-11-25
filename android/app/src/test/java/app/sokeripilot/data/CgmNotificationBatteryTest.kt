package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CgmNotificationBatteryTest {

    @Test
    fun `battery before the percentage is read`() {
        assertEquals(82, CgmNotificationParser.batteryPercent("Dexcom G7 · battery 82%"))
    }

    @Test
    fun `battery after the percentage is read`() {
        assertEquals(12, CgmNotificationParser.batteryPercent("Sensor 12% battery left"))
    }

    @Test
    fun `glucose percentages are not battery`() {
        assertNull(CgmNotificationParser.batteryPercent("128 mg/dL · 87% in range"))
    }

    @Test
    fun `an impossible percentage is dropped`() {
        assertNull(CgmNotificationParser.batteryPercent("battery 145%"))
    }

    @Test
    fun `a reading carries the battery when the app states it`() {
        val reading = CgmNotificationParser.parse(
            packageName = "com.dexcom.g7",
            appLabel = "Dexcom G7",
            texts = listOf("Dexcom G7", "128 mg/dL", "Steady", "battery 64%"),
            postTimeMillis = 1_700_000_000_000L,
            nowMillis = 1_700_000_000_000L
        )
        assertEquals(64, reading?.sensorBatteryPercent)
    }
}
