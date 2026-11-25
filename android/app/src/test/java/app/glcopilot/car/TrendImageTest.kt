package app.glcopilot.car

import android.graphics.Bitmap
import app.glcopilot.GlucoPilotApp
import app.glcopilot.data.GlucoseRange
import app.glcopilot.data.GlucoseReading
import app.glcopilot.data.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TrendImageTest {

    private val now = 1_700_000_000_000L

    private fun reading(minutesAgo: Long, mgdl: Int) = GlucoseReading(
        mgdl = mgdl,
        timestampMillis = now - minutesAgo * 60_000L,
        trend = Trend.FLAT,
        source = "test"
    )

    @Test
    fun `a window with readings draws a trend`() {
        val app = RuntimeEnvironment.getApplication() as GlucoPilotApp
        val bitmap = TrendImage.bitmap(
            app,
            listOf(reading(60, 120), reading(30, 140), reading(0, 96)),
            GlucoseRange(),
            now
        )

        assertNotNull(bitmap)
        assertEquals(TrendImage.WIDTH, bitmap!!.width)
        assertEquals(TrendImage.HEIGHT, bitmap.height)
        assertTrue("no pixel was drawn", hasInk(bitmap))
    }

    @Test
    fun `one reading is not a trend`() {
        val app = RuntimeEnvironment.getApplication() as GlucoPilotApp
        assertNull(TrendImage.bitmap(app, listOf(reading(5, 120)), GlucoseRange(), now))
    }

    @Test
    fun `readings outside the window are ignored`() {
        val app = RuntimeEnvironment.getApplication() as GlucoPilotApp
        assertNull(
            TrendImage.bitmap(
                app,
                listOf(reading(200, 120), reading(150, 140)),
                GlucoseRange(),
                now
            )
        )
    }

    private fun hasInk(bitmap: Bitmap): Boolean {
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) != 0) return true
                x += 8
            }
            y += 8
        }
        return false
    }
}
