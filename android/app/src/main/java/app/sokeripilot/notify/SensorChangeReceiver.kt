package app.sokeripilot.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sokeripilot.data.AppGraph

/** "Sensor change" on the stale reminder: same two-hour pause as the phone button. */
class SensorChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENSOR_CHANGE) return
        if (!AppGraph.isInitialized) return
        val container = AppGraph.container
        container.settings.pauseStaleReminders()
        container.notifier.cancelStale()
    }

    companion object {
        const val ACTION_SENSOR_CHANGE = "app.sokeripilot.action.SENSOR_CHANGE"
    }
}
