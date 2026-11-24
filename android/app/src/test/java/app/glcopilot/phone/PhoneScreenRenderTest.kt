package app.glcopilot.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import app.glcopilot.GlucoPilotApp
import app.glcopilot.R
import app.glcopilot.data.CgmNotificationListener
import app.glcopilot.data.DiagnosticEvent
import app.glcopilot.data.GlucoseReading
import app.glcopilot.data.Rejection
import app.glcopilot.data.Trend
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real phone screen off-device: a connected device or emulator is not
 * available here, so Robolectric's native graphics draws the actual layout (same
 * resources, same code path) to build/reports/phone-screen.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PhoneScreenRenderTest {

    @Test
    fun `source check card renders a reading and recent events`() {
        val app = RuntimeEnvironment.getApplication() as GlucoPilotApp
        val container = app.container
        val now = System.currentTimeMillis()

        // Pretend the one-time notification-access grant already happened, so the card
        // renders the state a real user sees after setup.
        android.provider.Settings.Secure.putString(
            app.contentResolver,
            "enabled_notification_listeners",
            app.packageName + "/" + CgmNotificationListener::class.java.name
        )

        // Notifications arrive in chronological order, like on a real phone.
        container.diagnostics.record(
            DiagnosticEvent(
                atMillis = now - 15 * 60_000L,
                packageName = "com.dexcom.g7",
                appLabel = "Dexcom G7",
                outcome = DiagnosticEvent.Outcome.REJECTED,
                reason = Rejection.STALE
            )
        )
        container.diagnostics.record(
            DiagnosticEvent(
                atMillis = now - 5 * 60_000L,
                packageName = "com.example.chat",
                appLabel = "Chat",
                outcome = DiagnosticEvent.Outcome.REJECTED,
                reason = Rejection.NOT_CGM_APP
            )
        )
        container.diagnostics.record(
            DiagnosticEvent(
                atMillis = now - 2 * 60_000L,
                packageName = "com.dexcom.g7",
                appLabel = "Dexcom G7",
                outcome = DiagnosticEvent.Outcome.ACCEPTED,
                valueMgdl = 132
            )
        )
        container.repository.onLocalReading(
            GlucoseReading(
                mgdl = 132,
                timestampMillis = now - 2 * 60_000L,
                trend = Trend.FLAT,
                source = "Dexcom G7"
            )
        )

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val scroll = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ScrollView
        val page = scroll.getChildAt(0) as LinearLayout

        val width = activity.resources.displayMetrics.widthPixels
        page.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        page.layout(0, 0, page.measuredWidth, page.measuredHeight)

        val bitmap = Bitmap.createBitmap(page.measuredWidth, page.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(activity, R.color.canvas))
        page.draw(canvas)

        val out = File("build/reports/phone-screen.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val background = ContextCompat.getColor(activity, R.color.canvas)
        var ink = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) != background) ink++
                x += 4
            }
            y += 4
        }

        assertTrue("the phone layout rendered nothing (${ink} sampled pixels)", ink > 500)
        assertTrue(
            "source-check card has no rows",
            (activity.findViewById<View>(R.id.diagList) as ViewGroup).childCount > 0
        )
        assertTrue("screenshot was not written", out.length() > 10_000)
    }
}
