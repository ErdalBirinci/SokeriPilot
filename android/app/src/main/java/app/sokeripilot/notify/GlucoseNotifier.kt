package app.sokeripilot.notify

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
import app.sokeripilot.R
import app.sokeripilot.car.SokeriPilotCarAppService
import app.sokeripilot.data.GlucoseBand
import app.sokeripilot.data.GlucoseProjection
import app.sokeripilot.data.GlucoseRange
import app.sokeripilot.data.GlucoseReading
import app.sokeripilot.data.GlucoseState
import app.sokeripilot.data.SettingsRepository
import app.sokeripilot.data.TimeAgo
import app.sokeripilot.data.Units
import app.sokeripilot.phone.MainActivity

/**
 * Android Auto does not let third-party apps keep a persistent window on top.
 * CGM therefore stays visible through a two-layer car notification:
 *
 *  - **status** notification: silent, persistent; keeps the latest value/trend
 *    current in the car notification area while navigation or music is open and
 *    offers one-tap access to the cockpit.
 *  - **alert** notification: only out of range, high priority (briefly comes
 *    forward on the car screen), once every 10 minutes, and can be muted for 30 minutes.
 *    An urgent low repeats every 5 minutes at most three times and carries "Got it".
 *  - **projection** notification: "low/high soon" from the current rate of change,
 *    at most once per 30 minutes.
 *  - **stale** reminder: once per dead-sensor episode, when no reading arrived for 15 minutes.
 *  - **battery** notice: once, when the sensor app reports 15% or less.
 */
class GlucoseNotifier(
    private val context: Context,
    private val settings: SettingsRepository
) {

    private val alertPolicy = AlertPolicy()
    private val predictivePolicy = PredictivePolicy()
    private var lowBatteryNotified = false

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
            state.sensorStatus?.label
                ?: state.lastError
                ?: context.getString(R.string.car_waiting_body)
        } else {
            val stale = TimeAgo.isStale(now, latest.timestampMillis)
            buildList {
                add(range.band(latest.mgdl).label)
                add(TimeAgo.format(now, latest.timestampMillis))
                add(latest.source)
                if (stale) {
                    add(state.sensorStatus?.label ?: context.getString(R.string.notif_stale))
                }
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
     * pending alert notification is removed and the repeat counters restart.
     */
    @Synchronized
    fun maybeNotify(reading: GlucoseReading, range: GlucoseRange, nowMillis: Long) {
        if (!settings.notificationsEnabled()) return

        val band = range.band(reading.mgdl)
        if (band == GlucoseBand.IN_RANGE) {
            alertPolicy.reset()
            cancelAlert()
            // A "low soon" that already arrived is no longer useful.
            cancelPrediction()
            return
        }
        if (!alertPolicy.shouldAlert(
                band,
                nowMillis,
                settings.mutedUntil(),
                settings.urgentAcknowledgedUntil()
            )
        ) {
            return
        }
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
     * "Low soon" / "High soon" when the measured rate of change crosses a threshold
     * inside the projection window. Only the newest readings are used, and the same
     * throttle as the other car alerts applies.
     */
    @Synchronized
    fun maybeNotifyPrediction(readings: List<GlucoseReading>, range: GlucoseRange, nowMillis: Long) {
        if (!settings.predictiveAlerts()) return
        if (!settings.notificationsEnabled()) return
        if (!hasPermission()) return

        val latest = readings.firstOrNull() ?: return
        val low = GlucoseProjection.lowWarning(readings, range.low, nowMillis)
        val high = if (low == null) GlucoseProjection.highWarning(readings, range.high, nowMillis) else null
        val warning = low ?: high ?: return
        if (!predictivePolicy.shouldAlert(nowMillis, settings.mutedUntil())) return
        predictivePolicy.markAlerted(nowMillis)

        val units = settings.units()
        val threshold = if (low != null) range.low else range.high
        val title = context.getString(
            if (low != null) R.string.notif_prediction_low_title else R.string.notif_prediction_high_title
        )
        val text = context.getString(
            R.string.notif_prediction_body,
            units.format(threshold),
            units.label,
            warning.minutes,
            units.format(latest.mgdl)
        )
        try {
            NotificationManagerCompat.from(context)
                .notify(PREDICTION_ID, highPriorityNotification(title, text, mute = mutePending()))
        } catch (_: SecurityException) {
            // Skip silently when permission was revoked.
        }
    }

    /** One quiet notice when the sensor app reports a low battery. */
    @Synchronized
    fun maybeNotifySensorBattery(reading: GlucoseReading) {
        val percent = reading.sensorBatteryPercent ?: return
        if (percent > BATTERY_CLEAR_PERCENT) {
            lowBatteryNotified = false
            return
        }
        if (percent > LOW_BATTERY_PERCENT || lowBatteryNotified) return
        if (!settings.notificationsEnabled()) return
        if (!hasPermission()) return

        lowBatteryNotified = true
        try {
            NotificationManagerCompat.from(context).notify(
                BATTERY_ID,
                lowPriorityNotification(
                    context.getString(R.string.notif_battery_title),
                    context.getString(R.string.notif_battery_body, percent)
                )
            )
        } catch (_: SecurityException) {
            // Skip silently when permission was revoked.
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
                    context.getString(R.string.notif_stale_alert_body),
                    pauseSensor = sensorChangePending()
                )
            )
        } catch (_: SecurityException) {
            // Stop silently if permission was revoked; the cockpit keeps working.
        }
    }

    fun cancelAlert() = NotificationManagerCompat.from(context).cancel(ALERT_ID)

    fun cancelPrediction() = NotificationManagerCompat.from(context).cancel(PREDICTION_ID)

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
        val urgent = band == GlucoseBand.URGENT_LOW
        return highPriorityNotification(
            title,
            text,
            mute = if (urgent) null else mutePending(),
            acknowledge = if (urgent) acknowledgePending() else null,
            // Repeats of an urgent low must alert again, not just update silently.
            onlyAlertOnce = !urgent
        )
    }

    /**
     * Notification that briefly comes forward on the car screen. Each optional action is
     * only added when it makes sense: mute for range alerts, "Got it" for an urgent low,
     * and the sensor-change pause on the stale reminder.
     */
    private fun highPriorityNotification(
        title: String,
        text: String,
        mute: PendingIntent? = null,
        acknowledge: PendingIntent? = null,
        pauseSensor: PendingIntent? = null,
        onlyAlertOnce: Boolean = true
    ): Notification {
        val cockpit = carAppPending(REQUEST_CAR_ALERT) ?: phonePending()

        val carExtender = CarAppExtender.Builder()
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(cockpit)
            .addAction(R.drawable.ic_open_car, context.getString(R.string.notif_action_cockpit), cockpit)
            .apply {
                acknowledge?.let {
                    addAction(R.drawable.ic_list, context.getString(R.string.notif_action_ack), it)
                }
                pauseSensor?.let {
                    addAction(R.drawable.ic_mute, context.getString(R.string.notif_action_sensor_change), it)
                }
                mute?.let {
                    addAction(R.drawable.ic_mute, context.getString(R.string.notif_action_mute), it)
                }
            }
            .build()

        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setAutoCancel(true)
            .setContentIntent(phonePending())
            .apply {
                if (acknowledge != null) {
                    addAction(R.drawable.ic_list, context.getString(R.string.notif_action_ack), acknowledge)
                }
                if (mute != null) {
                    addAction(R.drawable.ic_open_car, context.getString(R.string.notif_action_open), phonePending())
                    addAction(R.drawable.ic_mute, context.getString(R.string.notif_action_mute), mute)
                }
                pauseSensor?.let {
                    addAction(
                        R.drawable.ic_mute,
                        context.getString(R.string.notif_action_sensor_change),
                        it
                    )
                }
            }
            .extend(carExtender)
            .build()
    }

    /** Silent, auto-cancelling notice on the low-importance channel. */
    private fun lowPriorityNotification(title: String, text: String): Notification {
        ensureChannels()
        val carExtender = CarAppExtender.Builder()
            .setImportance(NotificationManager.IMPORTANCE_LOW)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(phonePending())
            .build()

        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(phonePending())
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
     * Opens the SokeriPilot cockpit when the car notification is tapped; the host's
     * Back action returns the user to the navigation or media app they came from.
     */
    private fun carAppPending(requestCode: Int): PendingIntent? = try {
        CarPendingIntent.getCarApp(
            context,
            requestCode,
            Intent(Intent.ACTION_VIEW).setComponent(
                ComponentName(context, SokeriPilotCarAppService::class.java)
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

    private fun acknowledgePending(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ACK,
        Intent(context, AcknowledgeAlertsReceiver::class.java)
            .setAction(AcknowledgeAlertsReceiver.ACTION_ACKNOWLEDGE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun sensorChangePending(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_SENSOR_CHANGE,
        Intent(context, SensorChangeReceiver::class.java)
            .setAction(SensorChangeReceiver.ACTION_SENSOR_CHANGE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val CHANNEL_STATUS = "glucose_status"
        private const val CHANNEL_ALERTS = "glucose_alerts"

        /** Persistent status notification ID used by the foreground service. */
        const val STATUS_NOTIFICATION_ID = 4100

        private const val ALERT_ID = 4101
        private const val STALE_ID = 4102
        private const val PREDICTION_ID = 4103
        private const val BATTERY_ID = 4104
        private const val REQUEST_PHONE = 10
        private const val REQUEST_CAR_STATUS = 11
        private const val REQUEST_CAR_ACTION = 12
        private const val REQUEST_CAR_ALERT = 13
        private const val REQUEST_MUTE = 14
        private const val REQUEST_ACK = 15
        private const val REQUEST_SENSOR_CHANGE = 16

        private const val LOW_BATTERY_PERCENT = 15
        private const val BATTERY_CLEAR_PERCENT = 25
    }
}
