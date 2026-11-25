package app.sokeripilot.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One notification SokeriPilot looked at, kept only in memory for the phone screen. */
data class DiagnosticEvent(
    val atMillis: Long,
    val packageName: String,
    val appLabel: String?,
    val outcome: Outcome,
    val reason: Rejection? = null,
    val valueMgdl: Int? = null
) {
    enum class Outcome { ACCEPTED, REJECTED }
}

/**
 * The last [CAPACITY] CGM-looking notifications, so a user can see on the phone which
 * app fed the cockpit and why a notification was rejected. Nothing is written to disk
 * and no glucose value is logged.
 */
class DiagnosticsLog(private val capacity: Int = CAPACITY) {

    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())

    /** Newest first; the phone screen renders this list as it changes. */
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    fun record(event: DiagnosticEvent) {
        _events.update { current -> (listOf(event) + current).take(capacity) }
    }

    fun clear() {
        _events.update { current -> if (current.isEmpty()) current else emptyList() }
    }

    companion object {
        const val CAPACITY = 10
    }
}
