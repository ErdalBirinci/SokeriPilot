package app.sokeripilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeAgoTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `recent and future timestamps read as now`() {
        assertEquals("now", TimeAgo.format(now, now - 30_000))
        assertEquals("now", TimeAgo.format(now, now + 5_000))
    }

    @Test
    fun `minutes and hours are formatted`() {
        assertEquals("5 min ago", TimeAgo.format(now, now - 5 * 60_000L))
        assertEquals("1 hr ago", TimeAgo.format(now, now - 60 * 60_000L))
        assertEquals("1 hr 2 min ago", TimeAgo.format(now, now - 62 * 60_000L))
    }

    @Test
    fun `staleness starts after fifteen minutes`() {
        assertFalse(TimeAgo.isStale(now, now - 15 * 60_000L))
        assertTrue(TimeAgo.isStale(now, now - 15 * 60_000L - 1))
    }
}
