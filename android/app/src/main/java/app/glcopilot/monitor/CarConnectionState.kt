package app.glcopilot.monitor

/**
 * The projection state published by the official `androidx.car.app.connection`
 * provider. Values come from the host:
 * 0 = not connected, 1 = Android Automotive OS, 2 = projected Android Auto.
 */
enum class CarConnectionState(val raw: Int) {
    DISCONNECTED(0),
    NATIVE(1),
    PROJECTION(2),

    /** The provider is missing or unreadable (no Android Auto on the phone). */
    UNKNOWN(-1);

    val isConnected: Boolean
        get() = this == NATIVE || this == PROJECTION

    companion object {
        fun from(raw: Int?): CarConnectionState = when (raw) {
            0 -> DISCONNECTED
            1 -> NATIVE
            2 -> PROJECTION
            else -> UNKNOWN
        }
    }
}
