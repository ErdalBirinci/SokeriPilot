package app.glcopilot.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.glcopilot.data.AppGraph

/** "Mute 30 min" action in the car notification; temporarily silences alerts. */
class MuteAlertsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MUTE) return
        if (!AppGraph.isInitialized) return
        val container = AppGraph.container
        container.settings.muteAlerts(MUTE_MINUTES)
        container.notifier.cancelAlert()
    }

    companion object {
        const val ACTION_MUTE = "app.glcopilot.action.MUTE_ALERTS"
        const val MUTE_MINUTES = 30L
    }
}
