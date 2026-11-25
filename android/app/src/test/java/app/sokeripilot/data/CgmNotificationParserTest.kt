package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CgmNotificationParserTest {

    private val now = 1_700_000_000_000L

    private fun parse(
        packageName: String = "com.dexcom.g7",
        label: String? = "Dexcom G7",
        texts: List<String?>,
        postTime: Long = now - 60_000
    ) = CgmNotificationParser.parse(
        packageName = packageName,
        appLabel = label,
        texts = texts,
        postTimeMillis = postTime,
        nowMillis = now
    )

    @Test
    fun `mgdl notification with an arrow becomes a reading`() {
        val reading = parse(texts = listOf("Dexcom G7", "128 mg/dL ↗", "2 min ago"))

        assertNotNull(reading)
        assertEquals(128, reading!!.mgdl)
        assertEquals(Trend.FORTY_FIVE_UP, reading.trend)
        assertEquals("Dexcom G7", reading.source)
        assertEquals(now - 60_000, reading.timestampMillis)
    }

    @Test
    fun `comma mmol values are converted to mgdl`() {
        val reading = parse(
            packageName = "com.freestylelibre3.app.us",
            label = "FreeStyle Libre 3",
            texts = listOf("6,4 mmol/L →")
        )

        assertEquals(115, reading!!.mgdl)
        assertEquals(Trend.FLAT, reading.trend)
    }

    @Test
    fun `mmol without a space is accepted`() {
        val reading = parse(
            packageName = "com.eversense",
            label = null,
            texts = listOf("Glucose 5.5mmol/l")
        )

        assertEquals(99, reading!!.mgdl)
    }

    @Test
    fun `word trends are mapped`() {
        val reading = parse(texts = listOf("Glucose 140 mg/dL, falling fast"))
        assertEquals(Trend.DOUBLE_DOWN, reading!!.trend)
    }

    @Test
    fun `unitless value is accepted from a known CGM package`() {
        val reading = parse(
            packageName = "com.eveningoutpost.dexdrip",
            label = null,
            texts = listOf("xDrip+", "145")
        )

        assertEquals(145, reading!!.mgdl)
        assertEquals("xDrip+", reading.source)
    }

    @Test
    fun `unit value from an unrelated app is rejected`() {
        assertNull(parse(packageName = "com.example.chat", label = "Chat", texts = listOf("128 mg/dL")))
        assertNull(
            CgmNotificationParser.parse(
                "com.example.chat",
                "Chat",
                listOf("128 mg/dL"),
                now - 60_000,
                ongoing = false,
                nowMillis = now
            )
        )
    }

    @Test
    fun `ongoing sensor notification is accepted even from an unknown app`() {
        val reading = CgmNotificationParser.parse(
            "com.example.sensor",
            "Sensor",
            listOf("7.0 mmol/L ↘"),
            now - 30_000,
            ongoing = true,
            nowMillis = now
        )

        assertEquals(126, reading!!.mgdl)
        assertEquals(Trend.FORTY_FIVE_DOWN, reading.trend)
        assertEquals("Sensor", reading.source)
    }

    @Test
    fun `unitless value from an unrelated app is rejected`() {
        assertNull(parse(packageName = "com.example.notes", label = "Notes", texts = listOf("145")))
    }

    @Test
    fun `implausible values are rejected`() {
        assertNull(parse(texts = listOf("900 mg/dL")))
        assertNull(parse(texts = listOf("40 mmol/L")))
        assertNull(parse(texts = listOf("12 mg/dL")))
    }

    @Test
    fun `old notifications are rejected`() {
        assertNull(parse(texts = listOf("128 mg/dL"), postTime = now - 61 * 60_000L))
    }

    @Test
    fun `future post times are clamped to the tolerance window`() {
        val reading = parse(texts = listOf("128 mg/dL"), postTime = now + 10 * 60_000L)
        assertEquals(now + 5 * 60_000L, reading!!.timestampMillis)
    }

    @Test
    fun `empty text is ignored`() {
        assertNull(parse(texts = listOf(null, "", "  ")))
    }
}
