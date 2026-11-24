package app.glcopilot.data

/** The user's target range. Urgent thresholds are fixed, clinically common values. */
data class GlucoseRange(
    val low: Int = 70,
    val high: Int = 180
) {

    fun band(mgdl: Int): GlucoseBand = when {
        mgdl < URGENT_LOW_MGDL -> GlucoseBand.URGENT_LOW
        mgdl < low -> GlucoseBand.LOW
        mgdl <= high -> GlucoseBand.IN_RANGE
        mgdl <= URGENT_HIGH_MGDL -> GlucoseBand.HIGH
        else -> GlucoseBand.URGENT_HIGH
    }

    /** @return an error to show the user, or null when valid. */
    fun validate(): String? = when {
        low !in 55..120 -> "Low threshold must be between 55 and 120 mg/dL."
        high !in 120..300 -> "High threshold must be between 120 and 300 mg/dL."
        low >= high -> "Low threshold must be lower than the high threshold."
        else -> null
    }

    companion object {
        const val URGENT_LOW_MGDL = 54
        const val URGENT_HIGH_MGDL = 250

        val DEFAULT = GlucoseRange()

        /** Returns null for invalid input; the caller shows an error to the user. */
        fun forTargets(low: Int, high: Int): GlucoseRange? =
            GlucoseRange(low = low, high = high).takeIf { it.validate() == null }
    }
}
