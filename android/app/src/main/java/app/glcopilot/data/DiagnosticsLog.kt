package app.glcopilot.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One notification GlucoPilot looked at, kept only in memory for the phone screen. */
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

    private val events = ArrayList<DiagnosticEvent>(capacity)

    private val _version = MutableStateFlow(0)

    /** Bumped on every change so the phone screen can re-render. */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Synchronized
    fun record(event: DiagnosticEvent) {
        if (events.size == capacity) events.removeAt(0)
        events.add(event)
        _version.update { it + 1 }
    }

    /** Newest first. */
    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = events.asReversed().toList()

    @Synchronized
    fun clear() {
        if (events.isEmpty()) return
        events.clear()
        _version.update { it + 1 }
    }

    companion object {
        const val CAPACITY = 10
    }
}
