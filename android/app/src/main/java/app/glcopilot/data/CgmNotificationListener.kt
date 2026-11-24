package app.glcopilot.data

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.glcopilot.monitor.CarConnectionWatcher

/**
 * Reads glucose values from whatever CGM app the user already runs (Dexcom, Libre,
 * xDrip+, Juggluco, Diabox, Eversense, CamAPS…). No per-app configuration is needed:
 * the user grants notification access once and every new CGM notification feeds the
 * same repository the car screen reads.
 *
 * The package itself is filtered out so GlucoPilot never eats its own status
 * notification. Every CGM-looking notification is also recorded in
 * [DiagnosticsLog], so the phone can explain why no value reached the car.
 */
class CgmNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // While this bound service keeps the process alive, watch for the car.
        CarConnectionWatcher.start(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // The system drops the binding after an app update or a crash; ask to be rebound.
        try {
            requestRebind(ComponentName(this, CgmNotificationListener::class.java))
        } catch (_: SecurityException) {
            // Rebind permission missing on this build; the system still rebinds on its own.
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        if (!AppGraph.isInitialized) return

        val extras = sbn.notification?.extras ?: return
        val texts = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
        )

        val label = appLabel(sbn.packageName)
        val container = AppGraph.container

        when (val outcome = CgmNotificationParser.evaluate(
            packageName = sbn.packageName,
            appLabel = label,
            texts = texts,
            postTimeMillis = sbn.postTime,
            ongoing = sbn.isOngoing
        )) {
            is ParseOutcome.Accepted -> {
                container.diagnostics.record(
                    DiagnosticEvent(
                        atMillis = System.currentTimeMillis(),
                        packageName = sbn.packageName,
                        appLabel = label,
                        outcome = DiagnosticEvent.Outcome.ACCEPTED,
                        valueMgdl = outcome.reading.mgdl
                    )
                )
                container.repository.onLocalReading(outcome.reading)
            }
            is ParseOutcome.Rejected -> container.diagnostics.record(
                DiagnosticEvent(
                    atMillis = System.currentTimeMillis(),
                    packageName = sbn.packageName,
                    appLabel = label,
                    outcome = DiagnosticEvent.Outcome.REJECTED,
                    reason = outcome.reason
                )
            )
            ParseOutcome.Ignored -> Unit
        }
    }

    private fun appLabel(packageName: String): String? = try {
        val manager = packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        null
    }
}

/** One-time system permission check and the settings screen that grants it. */
object NotificationAccess {

    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
