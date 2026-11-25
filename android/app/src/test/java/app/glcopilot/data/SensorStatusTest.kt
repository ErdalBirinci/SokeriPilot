package app.glcopilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorStatusTest {

    @Test
    fun `libre warm-up text is recognised`() {
        assertEquals(
            SensorStatus.WARMING_UP,
            SensorStatusParser.detect(listOf("FreeStyle Libre", "Sensor is warming up. Ready in 60 minutes."))
        )
    }

    @Test
    fun `dexcom sensor start is recognised`() {
        assertEquals(
            SensorStatus.WARMING_UP,
            SensorStatusParser.detect(listOf("Dexcom G7", "Sensor warmup"))
        )
    }

    @Test
    fun `signal loss is recognised`() {
        assertEquals(
            SensorStatus.SIGNAL_LOSS,
            SensorStatusParser.detect(listOf("Sensor signal loss", "Move closer to your phone"))
        )
    }

    @Test
    fun `an expired sensor is recognised`() {
        assertEquals(
            SensorStatus.EXPIRED,
            SensorStatusParser.detect(listOf("Sensor expired", "Replace your sensor"))
        )
    }

    @Test
    fun `a normal reading is not a status`() {
        assertNull(SensorStatusParser.detect(listOf("Dexcom G7", "128 mg/dL", "Steady")))
    }

    @Test
    fun `empty text is not a status`() {
        assertNull(SensorStatusParser.detect(listOf(null, " ")))
    }
}
