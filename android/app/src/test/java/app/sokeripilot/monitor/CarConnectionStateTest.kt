package app.sokeripilot.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarConnectionStateTest {

    @Test
    fun `host provider values are mapped`() {
        assertEquals(CarConnectionState.DISCONNECTED, CarConnectionState.from(0))
        assertEquals(CarConnectionState.NATIVE, CarConnectionState.from(1))
        assertEquals(CarConnectionState.PROJECTION, CarConnectionState.from(2))
    }

    @Test
    fun `unreadable or unknown values stay unknown`() {
        assertEquals(CarConnectionState.UNKNOWN, CarConnectionState.from(null))
        assertEquals(CarConnectionState.UNKNOWN, CarConnectionState.from(-1))
        assertEquals(CarConnectionState.UNKNOWN, CarConnectionState.from(9))
    }

    @Test
    fun `only a connected head unit counts as connected`() {
        assertTrue(CarConnectionState.PROJECTION.isConnected)
        assertTrue(CarConnectionState.NATIVE.isConnected)
        assertFalse(CarConnectionState.DISCONNECTED.isConnected)
        assertFalse(CarConnectionState.UNKNOWN.isConnected)
    }
}
