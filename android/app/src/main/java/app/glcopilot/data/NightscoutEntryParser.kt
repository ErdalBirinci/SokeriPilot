package app.glcopilot.data

import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONException

/**
 * Parses the Nightscout `/api/v1/entries/sgv.json` response.
 *
 * No field from the network reaches the UI directly: type, value, time and trend
 * are validated here; invalid records are skipped silently.
 */
object NightscoutEntryParser {

    const val MAX_ENTRIES = 48
    private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
    private const val FUTURE_TOLERANCE_MILLIS = 5 * 60 * 1000L

    fun parse(
        json: String,
        nowMillis: Long = System.currentTimeMillis(),
        source: String = "Nightscout"
    ): List<GlucoseReading> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) throw SourceException("Nightscout returned an empty response.")
        if (!trimmed.startsWith("[")) throw SourceException("Nightscout response is not in the expected format.")

        val array = try {
            JSONArray(trimmed)
        } catch (_: JSONException) {
            throw SourceException("Nightscout response could not be read.")
        }

        val readings = ArrayList<GlucoseReading>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            if (!entry.optString("type", "sgv").equals("sgv", ignoreCase = true)) continue

            val sgv = entry.optDouble("sgv", Double.NaN)
            if (!sgv.isFinite()) continue
            val mgdl = sgv.roundToInt()
            if (mgdl !in GlucoseReading.MIN_MGDL..GlucoseReading.MAX_MGDL) continue

            val date = entry.optDouble("date", Double.NaN)
            if (!date.isFinite()) continue
            val timestamp = date.toLong()
            if (timestamp <= 0L) continue
            if (timestamp > nowMillis + FUTURE_TOLERANCE_MILLIS) continue
            if (timestamp < nowMillis - MAX_AGE_MILLIS) continue

            readings += GlucoseReading(
                mgdl = mgdl,
                timestampMillis = timestamp,
                trend = Trend.fromDirection(entry.optString("direction", null)),
                source = source
            )
        }

        return readings
            .sortedByDescending { it.timestampMillis }
            .distinctBy { it.timestampMillis / 20_000L }
            .take(MAX_ENTRIES)
    }
}

/** User-presentable data-source error with no internal connection details. */
class SourceException(message: String) : Exception(message)
