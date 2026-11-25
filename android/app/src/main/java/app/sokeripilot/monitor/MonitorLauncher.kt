package app.sokeripilot.monitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.sokeripilot.data.AppGraph
import app.sokeripilot.data.NotificationAccess

/**
 * Single entry point for starting and stopping the driving monitor service. The car
 * connection watcher, the phone UI and the car screen all use the same behavior.
 */
object MonitorLauncher {

    enum class Result {
        /** Service started. */
        STARTED,

        /** The user turned driving mode off. */
        DISABLED,

        /** No CGM source at all: no xDrip+, no Nightscout, no notification access. */
        NO_SOURCE,

        /** Monitoring is tied to the car and the phone is not connected to one. */
        NOT_CONNECTED,

        /** The system/Android Auto did not allow a foreground service to start. */
        BLOCKED
    }

    fun start(context: Context): Result {
        if (!AppGraph.isInitialized) return Result.BLOCKED
        val settings = AppGraph.container.settings
        if (!settings.monitorWhileDriving()) return Result.DISABLED
        if (!hasSource(context)) return Result.NO_SOURCE

        // Car-bound monitoring: never run while the phone is not projected.
        // UNKNOWN (no Android Auto on this phone) still allows a manual start.
        if (settings.autoStartOnCar() &&
            CarConnectionWatcher.currentState(context) == CarConnectionState.DISCONNECTED
        ) {
            return Result.NOT_CONNECTED
        }

        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlucoseMonitorService::class.java)
            )
            Result.STARTED
        } catch (_: RuntimeException) {
            // Android 12+ background restriction or host rejection.
            Result.BLOCKED
        }
    }

    /** Stops the foreground service and removes its status notification. */
    fun stop(context: Context) {
        try {
            context.stopService(Intent(context, GlucoseMonitorService::class.java))
        } catch (_: RuntimeException) {
            // Service already gone.
        }
        if (AppGraph.isInitialized) {
            AppGraph.container.notifier.cancelStatus()
        }
    }

    /**
     * A source exists when xDrip+ broadcasts are enabled, Nightscout is configured,
     * or the one-time notification access lets SokeriPilot read the CGM app directly.
     */
    fun hasSource(context: Context): Boolean {
        if (!AppGraph.isInitialized) return false
        val settings = AppGraph.container.settings
        if (settings.xDripEnabled() || settings.nightscoutConfig() != null) return true
        return settings.autoReadNotifications() && NotificationAccess.isGranted(context)
    }
}
