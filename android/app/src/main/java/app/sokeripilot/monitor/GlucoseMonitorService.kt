package app.sokeripilot.monitor

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.sokeripilot.data.AppGraph
import app.sokeripilot.notify.GlucoseNotifier
import app.sokeripilot.notify.StaleAction
import app.sokeripilot.notify.StaleGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

        // The cockpit percentage and the end-of-drive summary both belong to this
        // session, not to whatever happened to be in memory before plugging in.
        container.repository.startSession()

        scope.launch {
            container.repository.state.collect { state ->
                container.notifier.postStatus(state)
            }
        }

        // Projections and the sensor battery notice are car-only: they are evaluated
        // when state changes while the driving monitor runs.
        scope.launch {
            container.repository.state.collect { state ->
                val now = System.currentTimeMillis()
                container.notifier.maybeNotifyPrediction(
                    state.readings,
                    container.settings.glucoseRange(),
                    now
                )
                state.latest?.let(container.notifier::maybeNotifySensorBattery)
            }
        }

        // A silent sensor must not leave a stale number looking current: tell the
        // driver once per stale episode, then stay quiet until fresh data returns.
        scope.launch {
            val staleGuard = StaleGuard()
            while (isActive) {
                container.repository.state.value.latest?.let { latest ->
                    val action = staleGuard.onCheck(
                        System.currentTimeMillis(),
                        latest.timestampMillis,
                        container.settings.sensorChangePauseUntil()
                    )
                    when (action) {
                        StaleAction.ALERT -> container.notifier.notifyStale()
                        StaleAction.CLEARED -> container.notifier.cancelStale()
                        StaleAction.NONE -> Unit
                    }
                }
                delay(STALE_CHECK_MILLIS)
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
        if (AppGraph.isInitialized) {
            AppGraph.container.repository.finishSession(
                AppGraph.container.settings.glucoseRange()
            )
        }
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val STALE_CHECK_MILLIS = 60_000L
    }
}
