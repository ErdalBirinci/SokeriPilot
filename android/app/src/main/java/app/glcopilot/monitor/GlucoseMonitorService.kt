package app.glcopilot.monitor

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.glcopilot.data.AppGraph
import app.glcopilot.notify.GlucoseNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps CGM monitoring alive while driving. The app process stays alive while the
 * user is in Android Auto navigation or media; the status notification updates on
 * every reading and alerts when out of range.
 *
 * For Android 14+, the `specialUse` type declares the CGM monitoring purpose
 * through the subtype description in the manifest.
 */
class GlucoseMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val container = AppGraph.container
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            GlucoseNotifier.STATUS_NOTIFICATION_ID,
            container.notifier.statusNotification(container.repository.state.value),
            type
        )
        scope.launch {
            container.repository.state.collect { state ->
                container.notifier.postStatus(state)
            }
        }

        // Monitoring is tied to the car: stop as soon as Android Auto disconnects.
        CarConnectionWatcher.observe(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // With car-bound monitoring on, the system must not resurrect the service
        // while the phone is unplugged.
        val carBound = AppGraph.isInitialized && AppGraph.container.settings.autoStartOnCar()
        return if (carBound) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
