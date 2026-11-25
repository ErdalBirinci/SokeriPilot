package app.glcopilot.phone

import android.widget.Button
import android.widget.TextView
import app.glcopilot.GlucoPilotApp
import app.glcopilot.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensorPauseCountdownTest {

    @Test
    fun `the countdown follows the pause while the screen stays open`() {
        val app = RuntimeEnvironment.getApplication() as GlucoPilotApp
        app.container.settings.pauseStaleReminders(120L)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()

        val label = activity.findViewById<TextView>(R.id.tvSensorPause)
        assertEquals(
            activity.getString(R.string.sensor_change_paused, 120),
            label.text.toString()
        )

        // The pause changes on disk (a shorter one), while this screen never leaves the foreground.
        app.container.settings.pauseStaleReminders(2L)
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS)

        assertEquals(activity.getString(R.string.sensor_change_paused, 2), label.text.toString())
        assertEquals(
            activity.getString(R.string.action_sensor_resume),
            activity.findViewById<Button>(R.id.btnSensorPause).text.toString()
        )
    }
}
