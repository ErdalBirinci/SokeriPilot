package app.glcopilot.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.glcopilot.notify.GlucoseNotifier

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext)
    val notifier = GlucoseNotifier(appContext, settings)
    val health = HealthConnectBridge(appContext, settings)
    val repository = GlucoseRepository(
        settings = settings,
        notifier = notifier,
        healthWriter = health::maybeWrite
    )

    /** Last ten CGM-looking notifications, for the source-check screen. */
    val diagnostics = DiagnosticsLog()

    private var started = false

    fun start() {
        if (started) return
        started = true
        notifier.ensureChannels()
        scope.launch { repository.runPolling() }
    }

    fun refreshNow() {
        scope.launch { repository.refresh() }
    }
}
