package app.glcopilot.data

import java.util.Locale
import kotlin.math.roundToInt

/** Display unit. Storage is always in mg/dL. */
enum class Units(val label: String) {
    MG_DL("mg/dL"),
    MMOL_L("mmol/L");

    fun format(mgdl: Int): String = when (this) {
        MG_DL -> mgdl.toString()
        MMOL_L -> String.format(Locale.US, "%.1f", mgdl / GlucoseReading.MGDL_PER_MMOL)
    }

    /** Converts user input to mg/dL; null for non-numeric or out-of-range input. */
    fun parseToMgdl(raw: String): Int? {
        val normalized = raw.trim().replace(',', '.')
        val value = normalized.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val mgdl = when (this) {
            MG_DL -> value.roundToInt()
            MMOL_L -> (value * GlucoseReading.MGDL_PER_MMOL).roundToInt()
        }
        return mgdl.takeIf { it in GlucoseReading.MIN_MGDL..GlucoseReading.MAX_MGDL }
    }
}
