package app.sokeripilot.data

import app.sokeripilot.notify.GlucoseNotifier
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
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Best-effort sink for enabled Health Connect export; null when unavailable. */
    private val healthWriter: ((GlucoseReading) -> Unit)? = null
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

    /** Marks the start of a driving session so the cockpit percentage is honest. */
    fun startSession(nowMillis: Long = clock()) {
        _state.update { it.copy(sessionStartedAtMillis = nowMillis) }
    }

    /** Ends the session, saves a compact summary for the phone and clears the marker. */
    fun finishSession(range: GlucoseRange, nowMillis: Long = clock()): DriveSummary? {
        val start = _state.value.sessionStartedAtMillis ?: return null
        val summary = DriveSummary.from(_state.value.readings, range, start, nowMillis)
        summary?.let(settings::saveLastDrive)
        _state.update { it.copy(sessionStartedAtMillis = null) }
        return summary
    }

    /** The CGM app announced a warm-up, signal loss or sensor end. */
    fun onSensorStatus(kind: SensorStatus) {
        _state.update { it.copy(sensorStatus = kind) }
        publishStatus()
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
                lastError = null,
                sensorStatus = null
            )
        }
        _state.value.readings.firstOrNull()?.let { latest -> healthWriter?.invoke(latest) }
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
