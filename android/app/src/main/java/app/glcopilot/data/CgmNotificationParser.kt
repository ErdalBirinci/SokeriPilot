package app.glcopilot.data

import kotlin.math.roundToInt

/** Why a notification that looked related did not become a reading. */
enum class Rejection(val label: String) {
    NO_TEXT("no text in the notification"),
    NO_VALUE("no glucose value found"),
    NOT_CGM_APP("not recognised as a CGM app"),
    OUT_OF_RANGE("value outside 20-600 mg/dL"),
    STALE("posted more than 60 minutes ago"),
    INVALID_TIME("no valid post time")
}

/** What the parser made of a single notification. */
sealed class ParseOutcome {

    data class Accepted(val reading: GlucoseReading) : ParseOutcome()

    data class Rejected(val reason: Rejection) : ParseOutcome()

    /** Clearly unrelated (a chat message, a media title); not worth recording. */
    object Ignored : ParseOutcome()
}

private sealed class ValueResult {

    data class Value(val mgdl: Int) : ValueResult()

    /** [reason] is null when the notification is not glucose-related at all. */
    data class Failed(val reason: Rejection?) : ValueResult()
}

/**
 * Reads a glucose value out of another CGM app's notification text.
 *
 * This is the only platform-sanctioned way to read an arbitrary CGM app without
 * asking the user to configure that app. Values are taken from the notification
 * the CGM app already posts, validated against the physiological range and only
 * kept on the device.
 *
 * Text without a unit is accepted only when the notification clearly comes from a
 * CGM app (known package, or a package/label containing a CGM brand keyword).
 */
object CgmNotificationParser {

    private val UNIT_PATTERN = Regex(
        """(\d{1,3}(?:[.,]\d{1,2})?)\s*(mg\s*/\s*d\s*l|mgdl|mmol\s*/\s*l|mmol)(?![a-z])""",
        RegexOption.IGNORE_CASE
    )

    private val BARE_VALUE = Regex("""(?<![\d.,])(\d{2,3})(?![\d.,])""")

    private val ARROWS: List<Pair<String, Trend>> = listOf(
        "\u21C8" to Trend.DOUBLE_UP,
        "\u2191\u2191" to Trend.DOUBLE_UP,
        "\u2191" to Trend.SINGLE_UP,
        "\u2197" to Trend.FORTY_FIVE_UP,
        "\u2192" to Trend.FLAT,
        "\u27A1" to Trend.FLAT,
        "\u2198" to Trend.FORTY_FIVE_DOWN,
        "\u2193" to Trend.SINGLE_DOWN,
        "\u2193\u2193" to Trend.DOUBLE_DOWN,
        "\u21CA" to Trend.DOUBLE_DOWN
    )

    private val WORD_TRENDS: List<Pair<String, Trend>> = listOf(
        "rising fast" to Trend.DOUBLE_UP,
        "falling fast" to Trend.DOUBLE_DOWN,
        "rising slowly" to Trend.FORTY_FIVE_UP,
        "falling slowly" to Trend.FORTY_FIVE_DOWN,
        "rising" to Trend.SINGLE_UP,
        "falling" to Trend.SINGLE_DOWN,
        "steady" to Trend.FLAT,
        "stable" to Trend.FLAT,
        "flat" to Trend.FLAT
    )

    private val CGM_HINTS = listOf(
        "dexcom",
        "libre",
        "glucose",
        "glucodata",
        "diabox",
        "xdrip",
        "eversense",
        "camaps",
        "nightscout",
        "sugar",
        "medtronic",
        "guardian",
        "carelink",
        "omnipod",
        "gluroo",
        "sugarmate",
        "aaps"
    )

    /** "battery 82%" or "82% battery" in a sensor notification. */
    private val BATTERY_PREFIX = Regex("""(?i)(?:battery|batt|pil)[^0-9%]{0,12}(\d{1,3})\s*%""")
    private val BATTERY_SUFFIX = Regex("""(?i)(\d{1,3})\s*%\s*(?:battery|remaining)""")

    private const val FUTURE_TOLERANCE_MILLIS = 5 * 60 * 1000L
    private const val MAX_AGE_MILLIS = 60 * 60 * 1000L
    private const val MIN_MMOL = 1.1
    private const val MAX_MMOL = 33.3

    /**
     * @param texts every text field of the notification (title, text, big text, sub text, lines).
     * @param postTimeMillis the notification's post time; the only timestamp we can trust.
     * @return a validated reading, or null when the notification is not a glucose reading.
     */
    fun parse(
        packageName: String,
        appLabel: String?,
        texts: List<String?>,
        postTimeMillis: Long,
        ongoing: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): GlucoseReading? =
        (evaluate(packageName, appLabel, texts, postTimeMillis, ongoing, nowMillis) as? ParseOutcome.Accepted)
            ?.reading

    /**
     * Same rules as [parse], but reports why a CGM-looking notification was rejected so
     * the phone can show it in the source-check screen.
     */
    fun evaluate(
        packageName: String,
        appLabel: String?,
        texts: List<String?>,
        postTimeMillis: Long,
        ongoing: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): ParseOutcome {
        val text = texts
            .filterNotNull()
            .joinToString(separator = " ")
            .replace('\u00A0', ' ')
            .replace('\n', ' ')
            .trim()
        if (text.isEmpty()) {
            return if (isCgmApp(packageName, appLabel)) {
                ParseOutcome.Rejected(Rejection.NO_TEXT)
            } else {
                ParseOutcome.Ignored
            }
        }

        val mgdl = when (val value = valueFromText(text, packageName, appLabel, ongoing)) {
            is ValueResult.Value -> value.mgdl
            is ValueResult.Failed -> return value.reason
                ?.let { ParseOutcome.Rejected(it) }
                ?: ParseOutcome.Ignored
        }

        val timestamp = postTimeMillis.coerceAtMost(nowMillis + FUTURE_TOLERANCE_MILLIS)
        if (timestamp <= 0L) return ParseOutcome.Rejected(Rejection.INVALID_TIME)
        if (timestamp < nowMillis - MAX_AGE_MILLIS) return ParseOutcome.Rejected(Rejection.STALE)

        return ParseOutcome.Accepted(
            GlucoseReading(
                mgdl = mgdl,
                timestampMillis = timestamp,
                trend = trendFrom(text),
                source = sourceLabel(packageName, appLabel),
                sensorBatteryPercent = batteryPercent(text)
            )
        )
    }

    private fun valueFromText(
        text: String,
        packageName: String,
        appLabel: String?,
        ongoing: Boolean
    ): ValueResult {
        UNIT_PATTERN.find(text)?.let { match ->
            // A bare "128 mg/dL" is not enough: accept it only from a CGM-looking app
            // or as an ongoing (sensor-style) notification, so a chat message never
            // becomes a reading.
            if (!ongoing && !isCgmApp(packageName, appLabel)) {
                return ValueResult.Failed(Rejection.NOT_CGM_APP)
            }

            val number = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                ?: return ValueResult.Failed(null)
            val unit = match.groupValues[2].lowercase().replace(" ", "")
            val mgdl = if (unit.contains("mmol")) {
                if (number !in MIN_MMOL..MAX_MMOL) return ValueResult.Failed(Rejection.OUT_OF_RANGE)
                (number * GlucoseReading.MGDL_PER_MMOL).roundToInt()
            } else {
                number.roundToInt()
            }
            return if (mgdl in GlucoseReading.MIN_MGDL..GlucoseReading.MAX_MGDL) {
                ValueResult.Value(mgdl)
            } else {
                ValueResult.Failed(Rejection.OUT_OF_RANGE)
            }
        }

        if (!isCgmApp(packageName, appLabel)) return ValueResult.Failed(null)
        val bare = BARE_VALUE.find(text)?.value?.toIntOrNull()
            ?: return ValueResult.Failed(Rejection.NO_VALUE)
        return if (bare in GlucoseReading.MIN_MGDL..GlucoseReading.MAX_MGDL) {
            ValueResult.Value(bare)
        } else {
            ValueResult.Failed(Rejection.OUT_OF_RANGE)
        }
    }

    /** Battery percentage from the sensor app's own wording; null when it is not stated. */
    fun batteryPercent(text: String): Int? {
        val match = BATTERY_PREFIX.find(text) ?: BATTERY_SUFFIX.find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 0..100 }
    }

    /** True when the notification comes from a known or clearly CGM-branded app. */
    fun isCgmApp(packageName: String, appLabel: String?): Boolean {
        if (CgmAppCatalog.labelFor(packageName) != null) return true
        val haystack = "$packageName ${appLabel.orEmpty()}".lowercase()
        return CGM_HINTS.any { haystack.contains(it) }
    }

    private fun trendFrom(text: String): Trend {
        ARROWS.firstOrNull { text.contains(it.first) }?.let { return it.second }
        val lowered = text.lowercase()
        WORD_TRENDS.firstOrNull { lowered.contains(it.first) }?.let { return it.second }
        return Trend.UNKNOWN
    }

    private fun sourceLabel(packageName: String, appLabel: String?): String =
        appLabel?.takeIf { it.isNotBlank() }
            ?: CgmAppCatalog.labelFor(packageName)
            ?: "CGM app"
}
