package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {

    @Test
    fun `mgdl is displayed as an integer`() {
        assertEquals("126", Units.MG_DL.format(126))
    }

    @Test
    fun `mmol is displayed with one decimal`() {
        assertEquals("7.0", Units.MMOL_L.format(126))
    }

    @Test
    fun `mmol input converts to mgdl`() {
        assertEquals(126, Units.MMOL_L.parseToMgdl("7.0"))
        assertEquals(126, Units.MMOL_L.parseToMgdl("7,0"))
    }

    @Test
    fun `mgdl input parses plain integers`() {
        assertEquals(126, Units.MG_DL.parseToMgdl("126"))
        assertEquals(126, Units.MG_DL.parseToMgdl(" 126 "))
    }

    @Test
    fun `invalid and out of range input returns null`() {
        assertNull(Units.MG_DL.parseToMgdl("abc"))
        assertNull(Units.MG_DL.parseToMgdl("0"))
        assertNull(Units.MG_DL.parseToMgdl("-5"))
        assertNull(Units.MG_DL.parseToMgdl("601"))
        assertNull(Units.MMOL_L.parseToMgdl("50"))
    }
}
