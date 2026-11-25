package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutEntryParserTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `newest reading first and invalid entries dropped`() {
        val json = """
            [
              {"type":"sgv","sgv":120,"date":${now - 600_000},"direction":"Flat"},
              {"type":"mbg","sgv":999,"date":${now - 500_000}},
              {"type":"sgv","sgv":140,"date":${now - 300_000},"direction":"SingleUp"},
              {"type":"sgv","sgv":900,"date":${now - 200_000}},
              {"type":"sgv","sgv":160,"date":${now + 600_000}}
            ]
        """.trimIndent()

        val readings = NightscoutEntryParser.parse(json, now)

        assertEquals(2, readings.size)
        assertEquals(140, readings[0].mgdl)
        assertEquals(Trend.SINGLE_UP, readings[0].trend)
        assertEquals(120, readings[1].mgdl)
    }

    @Test
    fun `missing optional fields do not crash`() {
        val json = """[{"sgv":95,"date":${now - 60_000}}]"""
        val readings = NightscoutEntryParser.parse(json, now)
        assertEquals(1, readings.size)
        assertEquals(Trend.UNKNOWN, readings[0].trend)
    }

    @Test
    fun `fractional values are rounded`() {
        val json = """[{"type":"sgv","sgv":126.6,"date":${now - 60_000},"direction":"Flat"}]"""
        assertEquals(127, NightscoutEntryParser.parse(json, now).first().mgdl)
    }

    @Test
    fun `non array payload is rejected`() {
        assertThrows(SourceException::class.java) {
            NightscoutEntryParser.parse("""{"status":500}""", now)
        }
    }

    @Test
    fun `blank payload is rejected`() {
        assertThrows(SourceException::class.java) {
            NightscoutEntryParser.parse("   ", now)
        }
    }

    @Test
    fun `malformed payload is rejected`() {
        assertThrows(SourceException::class.java) {
            NightscoutEntryParser.parse("[{\"sgv\":", now)
        }
    }

    @Test
    fun `long histories are capped`() {
        val entries = (0 until 120).joinToString(separator = ",") { index ->
            """{"type":"sgv","sgv":${100 + index % 60},"date":${now - index * 60_000L}}"""
        }
        val readings = NightscoutEntryParser.parse("[$entries]", now)
        assertTrue(readings.size <= NightscoutEntryParser.MAX_ENTRIES)
        assertEquals(NightscoutEntryParser.MAX_ENTRIES, readings.size)
    }
}
