package app.glcopilot.data

import app.glcopilot.notify.GlucoseNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/**
 * Single source of truth: readings from any CGM app's notification, the xDrip+
 * broadcast and Nightscout polling all write to the same state.
 */
class GlucoseRepository(
    private val settings: SettingsRepository,
    private val notifier: GlucoseNotifier,
    private val client: NightscoutClient = NightscoutClient(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val _state = MutableStateFlow(GlucoseState())
    val state: StateFlow<GlucoseState> = _state.asStateFlow()

    private val stateLock = Any()
    private val refreshLock = Mutex()

    fun onLocalReading(reading: GlucoseReading) {
        merge(listOf(reading), reading.source)
        notifier.maybeNotify(reading, settings.glucoseRange(), clock())
    }

    suspend fun refresh() {
        if (!refreshLock.tryLock()) return
        try {
            val config = settings.nightscoutConfig()
            if (config == null) {
                _state.update { it.copy(isRefreshing = false) }
                publishStatus()
                return
            }
            _state.update { it.copy(isRefreshing = true) }
            try {
                val readings = client.fetchLatest(config)
                merge(readings, "Nightscout")
                _state.update { it.copy(isRefreshing = false, lastError = null) }
                publishStatus()
                readings.firstOrNull()?.let { latest ->
                    notifier.maybeNotify(latest, settings.glucoseRange(), clock())
                }
            } catch (error: SourceException) {
                _state.update { it.copy(isRefreshing = false, lastError = error.message) }
                publishStatus()
            }
        } finally {
            refreshLock.unlock()
        }
    }

    suspend fun runPolling() {
        while (true) {
            refresh()
            delay(settings.pollMinutes() * 60_000L)
        }
    }

    private fun merge(incoming: List<GlucoseReading>, source: String) {
        if (incoming.isEmpty()) return
        val cutoff = clock() - MAX_AGE_MILLIS
        synchronized(stateLock) {
            val merged = (incoming + _state.value.readings)
                .filter { it.timestampMillis >= cutoff }
                .sortedByDescending { it.timestampMillis }
                .distinctBy { it.timestampMillis / 20_000L }
                .take(MAX_READINGS)
            _state.value = _state.value.copy(
                readings = merged,
                configuredSource = source,
                lastError = null
            )
        }
        publishStatus()
    }

    /** The status notification updates on every reading while driving mode is on. */
    private fun publishStatus() {
        notifier.postStatus(_state.value)
    }

    companion object {
        const val MAX_READINGS = 48
        private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
    }
}
