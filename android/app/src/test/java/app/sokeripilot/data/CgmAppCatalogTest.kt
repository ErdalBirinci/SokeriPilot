package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CgmAppCatalogTest {

    @Test
    fun `Libre TR is detected and readable through its notification`() {
        val apps = CgmAppCatalog.detect(setOf("com.freestylelibre.app.tr"))

        assertEquals(1, apps.size)
        assertEquals("FreeStyle LibreLink (TR)", apps.single().label)
        assertEquals(CgmIntegration.NOTIFICATION, apps.single().integration)
        assertTrue(CgmAppCatalog.broadcastSources(setOf("com.freestylelibre.app.tr")).isEmpty())
    }

    @Test
    fun `Libre 3 US is detected as notification readable`() {
        val app = CgmAppCatalog.detect(setOf("com.freestylelibre3.app.us")).single()

        assertEquals("FreeStyle Libre 3 (US)", app.label)
        assertEquals(CgmIntegration.NOTIFICATION, app.integration)
    }

    @Test
    fun `regional Libre packages are all known`() {
        val packages = setOf(
            "com.freestylelibre.app.tr",
            "com.freestylelibre.app.gb",
            "com.freestylelibre.app.de",
            "com.freestylelibre.app.fr"
        )

        assertEquals(4, CgmAppCatalog.detect(packages).size)
        assertTrue(CgmAppCatalog.detect(packages).all { it.integration == CgmIntegration.NOTIFICATION })
    }

    @Test
    fun `xDrip and Juggluco are automatic broadcast sources`() {
        val sources = CgmAppCatalog.broadcastSources(
            setOf("com.eveningoutpost.dexdrip", "tk.glucodata")
        )

        assertEquals(listOf("xDrip+", "Juggluco"), sources.map { it.label })
        assertTrue(sources.all { it.integration == CgmIntegration.XDRIP_BROADCAST })
    }

    @Test
    fun `Diabox is notification readable but not a verified broadcast source`() {
        val app = CgmAppCatalog.detect(setOf("com.bubblesoft.android.diabox")).single()

        assertEquals(CgmIntegration.NOTIFICATION, app.integration)
        assertTrue(CgmAppCatalog.broadcastSources(setOf("com.bubblesoft.android.diabox")).isEmpty())
    }

    @Test
    fun `Dexcom apps are readable through their notifications`() {
        val apps = CgmAppCatalog.detect(setOf("com.dexcom.g6", "com.dexcom.g7"))

        assertEquals(2, apps.size)
        assertTrue(apps.all { it.integration == CgmIntegration.NOTIFICATION })
    }

    @Test
    fun `labels are resolved by package name`() {
        assertEquals("xDrip+", CgmAppCatalog.labelFor("com.eveningoutpost.dexdrip"))
        assertNull(CgmAppCatalog.labelFor("com.example.cgm"))
    }

    @Test
    fun `unknown packages are ignored`() {
        assertTrue(CgmAppCatalog.detect(setOf("com.example.cgm", "com.other.app")).isEmpty())
    }

    @Test
    fun `detection follows catalog order and ignores duplicates`() {
        val apps = CgmAppCatalog.detect(
            setOf("tk.glucodata", "com.eveningoutpost.dexdrip", "tk.glucodata")
        )

        assertEquals(listOf("xDrip+", "Juggluco"), apps.map { it.label })
    }

    @Test
    fun `hybrid closed loop apps are readable through their notifications`() {
        val apps = CgmAppCatalog.detect(
            setOf("uk.org.cambsdiabetes.camaps", "info.nightscout.androidaps")
        )

        assertEquals(listOf("CamAPS FX", "AndroidAPS"), apps.map { it.label })
        assertTrue(apps.all { it.integration == CgmIntegration.NOTIFICATION })
        assertTrue(CgmAppCatalog.broadcastSources(setOf("uk.org.cambsdiabetes.camaps")).isEmpty())
    }
}
