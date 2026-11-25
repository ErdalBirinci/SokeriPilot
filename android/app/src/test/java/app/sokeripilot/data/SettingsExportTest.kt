package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsExportTest {

    private fun snapshot() = SettingsSnapshot(
        nightscoutUrl = "https://nightscout.example",
        nightscoutSecretSha1 = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
        low = 80,
        high = 190,
        units = Units.MMOL_L.name,
        pollMinutes = 5,
        xDripEnabled = true,
        xDripUserChose = true,
        notificationsEnabled = true,
        monitorWhileDriving = true,
        autoStartOnCar = false,
        autoReadNotifications = true,
        predictiveAlerts = false,
        healthConnectEnabled = true
    )

    @Test
    fun `settings survive a round trip`() {
        val restored = SettingsExport.fromJson(SettingsExport.toJson(snapshot()))
        assertEquals(snapshot(), restored)
    }

    @Test
    fun `a missing nightscout is preserved as null`() {
        val restored = SettingsExport.fromJson(
            SettingsExport.toJson(snapshot().copy(nightscoutUrl = null, nightscoutSecretSha1 = null))
        )
        assertNull(restored?.nightscoutUrl)
        assertNull(restored?.nightscoutSecretSha1)
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(SettingsExport.fromJson("not json"))
        assertNull(SettingsExport.fromJson("{}"))
    }

    @Test
    fun `an invalid target range is rejected`() {
        assertNull(SettingsExport.fromJson(SettingsExport.toJson(snapshot().copy(low = 300, high = 100))))
    }

    @Test
    fun `an unknown unit is rejected`() {
        assertNull(SettingsExport.fromJson(SettingsExport.toJson(snapshot().copy(units = "STONES"))))
    }
}
