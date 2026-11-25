package app.sokeripilot.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import app.sokeripilot.data.AppGraph

/**
 * Watches the official Android Auto connection signal:
 * `androidx.car.app.connection.action.CAR_CONNECTION_UPDATED` plus the
 * `androidx.car.app.connection` provider.
 *
 * Driving monitoring is tied to the connection: it starts when the phone is
 * projected into a head unit and stops again when the connection goes away, so
 * nothing runs in the background while the phone is not in a car.
 */
object CarConnectionWatcher {

    private const val ACTION_CAR_CONNECTION_UPDATED =
        "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    private const val AUTHORITY = "androidx.car.app.connection"
    private const val COLUMN_STATE = "CarConnectionState"

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleState(context.applicationContext)
        }
    }

    /** Registers the receiver only; used by the running service to see disconnects. */
    fun observe(context: Context) {
        register(context.applicationContext)
    }

    /** Registers and reacts to the current state right away. */
    fun start(context: Context) {
        val app = context.applicationContext
        register(app)
        handleState(app)
    }

    /** Current projection state, or [CarConnectionState.UNKNOWN] when unreadable. */
    fun currentState(context: Context): CarConnectionState =
        CarConnectionState.from(queryState(context.applicationContext))

    private fun register(app: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            try {
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter(ACTION_CAR_CONNECTION_UPDATED),
                    ContextCompat.RECEIVER_EXPORTED
                )
                registered = true
            } catch (_: RuntimeException) {
                // No car app host on this phone; there is nothing to watch.
            }
        }
    }

    private fun handleState(context: Context) {
        val state = currentState(context)
        if (state == CarConnectionState.UNKNOWN) return
        if (!AppGraph.isInitialized) return

        val container = AppGraph.container
        if (!container.settings.autoStartOnCar()) return

        if (state.isConnected) {
            MonitorLauncher.start(context)
            container.refreshNow()
        } else {
            // Unplugged or Android Auto closed: no monitoring, no status notification.
            MonitorLauncher.stop(context)
        }
    }

    private fun queryState(context: Context): Int? = try {
        context.contentResolver.query(
            Uri.Builder().scheme("content").authority(AUTHORITY).build(),
            arrayOf(COLUMN_STATE),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
    } catch (_: Exception) {
        null
    }
}
