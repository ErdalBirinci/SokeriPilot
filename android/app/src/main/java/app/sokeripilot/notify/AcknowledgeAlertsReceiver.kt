package app.sokeripilot.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sokeripilot.data.AppGraph

/**
 * "Got it" on an urgent-low alert: quiets the five-minute repeat for half an hour.
 * The alert itself is removed so the car screen returns to navigation.
 */
class AcknowledgeAlertsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACKNOWLEDGE) return
        if (!AppGraph.isInitialized) return
        val container = AppGraph.container
        container.settings.acknowledgeUrgentLow()
        container.notifier.cancelAlert()
    }

    companion object {
        const val ACTION_ACKNOWLEDGE = "app.sokeripilot.action.ACKNOWLEDGE_ALERT"
    }
}
