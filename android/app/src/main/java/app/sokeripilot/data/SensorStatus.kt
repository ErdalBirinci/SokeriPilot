package app.sokeripilot.data

import java.util.Locale

/** What the CGM app itself says about the sensor, when it says anything at all. */
enum class SensorStatus(val label: String) {
    WARMING_UP("Sensor warming up"),
    SIGNAL_LOSS("Sensor signal lost"),
    EXPIRED("Sensor finished")
}

/**
 * Reads only the state words a sensor app posts during a change: warming up, signal
 * loss, sensor finished. These notifications carry no glucose value, so they never
 * become a reading; they exist so a gap in the data can be explained instead of
 * looking like a dead pipeline.
 */
object SensorStatusParser {

    private val WARMING_UP = listOf(
        "warming up",
        "warm-up",
        "warmup",
        "sensor is warming",
        "sensor warming",
        "starting sensor",
        "sensor start"
    )

    private val SIGNAL_LOSS = listOf(
        "signal loss",
        "lost signal",
        "no signal",
        "sensor signal"
    )

    private val EXPIRED = listOf(
        "sensor expired",
        "sensor has expired",
        "sensor ended",
        "sensor finished",
        "replace sensor",
        "sensor is over"
    )

    fun detect(texts: List<String?>): SensorStatus? {
        val text = texts.filterNotNull().joinToString(separator = " ").lowercase(Locale.US)
        if (text.isBlank()) return null
        return when {
            EXPIRED.any(text::contains) -> SensorStatus.EXPIRED
            WARMING_UP.any(text::contains) -> SensorStatus.WARMING_UP
            SIGNAL_LOSS.any(text::contains) -> SensorStatus.SIGNAL_LOSS
            else -> null
        }
    }
}
