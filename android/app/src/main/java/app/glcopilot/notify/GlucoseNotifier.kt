package app.glcopilot.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.glcopilot.R
import app.glcopilot.car.GlucoPilotCarAppService
import app.glcopilot.data.GlucoseBand
import app.glcopilot.data.GlucoseRange
import app.glcopilot.data.GlucoseReading
import app.glcopilot.data.GlucoseState
import app.glcopilot.data.SettingsRepository
import app.glcopilot.data.TimeAgo
import app.glcopilot.data.Units
import app.glcopilot.phone.MainActivity

/**
 * Android Auto does not let third-party apps keep a persistent window on top.
 * CGM therefore stays visible through a two-layer car notification:
 *
 *  - **status** notification: silent, persistent; keeps the latest value/trend
 *    current in the car notification area while navigation or music is open and
 *    offers one-tap access to the cockpit.
 *  - **alert** notification: only out of range, high priority (briefly comes
 *    forward on the car screen), once every 10 minutes, and can be muted for 30 minutes.
 *  - **stale** reminder: once per dead-sensor episode, when no reading arrived for 15 minutes.
 */
class GlucoseNotifier(
    private val context: Context,
    private val settings: SettingsRepository
) {

    private val alertPolicy = AlertPolicy()

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_STATUS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STATUS,
                    context.getString(R.string.notif_status_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notif_status_channel_desc)
                    setShowBadge(false)
                    setSound(null, null)
                }
            )
        }

        if (manager.getNotificationChannel(CHANNEL_ALERTS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notif_channel_desc)
                    setShowBadge(true)
                }
            )
        }
    }

    /** Silent, persistent status notification shown by the foreground service. */
    fun statusNotification(state: GlucoseState): Notification {
        ensureChannels()
        val units = settings.units()
        val range = settings.glucoseRange()
        val latest = state.latest
        val now = System.currentTimeMillis()

        val title = if (latest == null) {
            context.getString(R.string.notif_status_waiting)
        } else {
            context.getString(
                R.string.notif_status_title,
                "${units.format(latest.mgdl)} ${units.label} ${latest.trend.arrow}"
            )
        }

        val text = if (latest == null) {
            state.lastError ?: context.getString(R.string.car_waiting_body)
        } else {
            val stale = TimeAgo.isStale(now, latest.timestampMillis)
            buildList {
                add(range.band(latest.mgdl).label)
                add(TimeAgo.format(now, latest.timestampMillis))
                add(latest.source)
                if (stale) add(context.getString(R.string.notif_stale))
                state.lastError?.let(::add)
            }.joinToString(separator = " · ")
        }

        val carExtender = CarAppExtender.Builder()
            .setImportance(NotificationManager.IMPORTANCE_LOW)
            .setContentTitle(title)
            .setContentText(text)
            .apply { carAppPending(REQUEST_CAR_STATUS)?.let(::setContentIntent) }
            .addAction(
                R.drawable.ic_open_car,
                context.getString(R.string.notif_action_cockpit),
                carAppPending(REQUEST_CAR_ACTION) ?: phonePending()
            )
            .build()

        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(latest?.timestampMillis ?: now)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(phonePending())
            .extend(carExtender)
            .build()
    }

    /** Updates the status notification on every new reading (when driving mode is on). */
    @Synchronized
    fun postStatus(state: GlucoseState) {
        if (!settings.monitorWhileDriving()) return
        if (!hasPermission()) return
        try {
            NotificationManagerCompat.from(context).notify(STATUS_NOTIFICATION_ID, statusNotification(state))
        } catch (_: SecurityException) {
            // Stop silently if permission was revoked; cockpit data keeps working.
        }
    }

    /**
     * Alerts only outside the target range. When the value returns to range, the
     * pending alert notification is removed.
     */
    @Synchronized
    fun maybeNotify(reading: GlucoseReading, range: GlucoseRange, nowMillis: Long) {
        if (!settings.notificationsEnabled()) return

        val band = range.band(reading.mgdl)
        if (band == GlucoseBand.IN_RANGE) {
            // Returning to range resets the throttle; the next excursion alerts immediately.
            alertPolicy.reset()
            cancelAlert()
            return
        }
        if (!alertPolicy.shouldAlert(band, nowMillis, settings.mutedUntil())) return
        if (!hasPermission()) return

        alertPolicy.markNotified(band, nowMillis)
        try {
            NotificationManagerCompat.from(context)
                .notify(ALERT_ID, alertNotification(reading, band))
        } catch (_: SecurityException) {
            // Skip the alert when permission is missing.
        }
    }

    /**
     * One-shot reminder that no new reading arrived for 15 minutes. It appears in the
     * car as a high-priority reminder and stays silent afterwards until fresh data
     * returns, so a dead sensor does not leave a stale number looking current.
     */
    @Synchronized
    fun notifyStale() {
        if (!settings.notificationsEnabled()) return
        if (!hasPermission()) return
        ensureChannels()
        try {
            NotificationManagerCompat.from(context).notify(
                STALE_ID,
                highPriorityNotification(
                    context.getString(R.string.notif_stale_alert_title),
                    context.getString(R.string.notif_stale_alert_body)
                )
            )
        } catch (_: SecurityException) {
            // Stop silently if permission was revoked; the cockpit keeps working.
        }
    }

    fun cancelAlert() = NotificationManagerCompat.from(context).cancel(ALERT_ID)

    fun cancelStale() = NotificationManagerCompat.from(context).cancel(STALE_ID)

    fun cancelStatus() {
        NotificationManagerCompat.from(context).cancel(STATUS_NOTIFICATION_ID)
    }

    private fun alertNotification(reading: GlucoseReading, band: GlucoseBand): Notification {
        ensureChannels()
        val units = settings.units()
        val value = "${units.format(reading.mgdl)} ${units.label} ${reading.trend.arrow}"
        val title = context.getString(R.string.notif_title, value)
        val text = buildString {
            append(band.label)
            append(" · ").append(settings.glucoseRange().let { "${it.low}–${it.high} ${units.label}" })
            append(" · ").append(TimeAgo.format(System.currentTimeMillis(), reading.timestampMillis))
        }
        return highPriorityNotification(title, text, mutePending())
    }

    /**
     * High-priority notification that briefly comes forward on the car screen.
     * [mute] adds the 30-minute mute action; the stale reminder has nothing to mute.
     */
    private fun highPriorityNotification(
        title: String,
        text: String,
        mute: PendingIntent? = null
    ): Notification {
        val cockpit = carAppPending(REQUEST_CAR_ALERT) ?: phonePending()

        val carExtender = CarAppExtender.Builder()
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(cockpit)
            .addAction(R.drawable.ic_open_car, context.getString(R.string.notif_action_cockpit), cockpit)
            .apply {
                mute?.let { addAction(R.drawable.ic_mute, context.getString(R.string.notif_action_mute), it) }
            }
            .build()

        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(phonePending())
            .apply {
                if (mute != null) {
                    addAction(R.drawable.ic_open_car, context.getString(R.string.notif_action_open), phonePending())
                    addAction(R.drawable.ic_mute, context.getString(R.string.notif_action_mute), mute)
                }
            }
            .extend(carExtender)
            .build()
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun phonePending(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_PHONE,
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Opens the GlucoPilot cockpit when the car notification is tapped; the host's
     * Back action returns the user to the navigation or media app they came from.
     */
    private fun carAppPending(requestCode: Int): PendingIntent? = try {
        CarPendingIntent.getCarApp(
            context,
            requestCode,
            Intent(Intent.ACTION_VIEW).setComponent(
                ComponentName(context, GlucoPilotCarAppService::class.java)
            ),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    } catch (_: RuntimeException) {
        // Fall back to the phone if the host does not accept a car app launch.
        null
    }

    private fun mutePending(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_MUTE,
        Intent(context, MuteAlertsReceiver::class.java)
            .setAction(MuteAlertsReceiver.ACTION_MUTE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val CHANNEL_STATUS = "glucose_status"
        private const val CHANNEL_ALERTS = "glucose_alerts"

        /** Persistent status notification ID used by the foreground service. */
        const val STATUS_NOTIFICATION_ID = 4100

        private const val ALERT_ID = 4101
        private const val STALE_ID = 4102
        private const val REQUEST_PHONE = 10
        private const val REQUEST_CAR_STATUS = 11
        private const val REQUEST_CAR_ACTION = 12
        private const val REQUEST_CAR_ALERT = 13
        private const val REQUEST_MUTE = 14
    }
}
