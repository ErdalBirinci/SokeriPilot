package app.sokeripilot.car

import android.graphics.Bitmap
import app.sokeripilot.SokeriPilotApp
import app.sokeripilot.data.GlucoseRange
import app.sokeripilot.data.GlucoseReading
import app.sokeripilot.data.Trend
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
        val app = RuntimeEnvironment.getApplication() as SokeriPilotApp
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

    /**
     * Writes the real bitmap to build/reports/trend-image.png so the README can show the
     * image the car screen actually gets, not a mock-up.
     */
    @Test
    fun `trend image is exported for the docs`() {
        val app = RuntimeEnvironment.getApplication() as SokeriPilotApp
        val bitmap = TrendImage.bitmap(
            app,
            listOf(
                reading(88, 118),
                reading(72, 132),
                reading(58, 148),
                reading(44, 161),
                reading(30, 152),
                reading(16, 138),
                reading(4, 126),
                reading(0, 121)
            ),
            GlucoseRange(),
            now
        )!!

        val out = java.io.File("build/reports/trend-image.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("trend image was not written", out.length() > 1_000)
    }

    @Test
    fun `one reading is not a trend`() {
        val app = RuntimeEnvironment.getApplication() as SokeriPilotApp
        assertNull(TrendImage.bitmap(app, listOf(reading(5, 120)), GlucoseRange(), now))
    }

    @Test
    fun `readings outside the window are ignored`() {
        val app = RuntimeEnvironment.getApplication() as SokeriPilotApp
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
