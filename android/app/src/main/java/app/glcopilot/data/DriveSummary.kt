package app.glcopilot.data

/** The numbers for one driving session, kept only as four small values on the phone. */
data class DriveSummary(
    val endedAtMillis: Long,
    val minutes: Int,
    val inRangePercent: Int,
    val lowestMgdl: Int,
    val lowCount: Int
) {
    companion object {

        fun from(
            readings: List<GlucoseReading>,
            range: GlucoseRange,
            startMillis: Long,
            endMillis: Long
        ): DriveSummary? {
            val session = readings.filter { it.timestampMillis >= startMillis }
            if (session.isEmpty()) return null
            val inRange = session.count { range.band(it.mgdl) == GlucoseBand.IN_RANGE }
            return DriveSummary(
                endedAtMillis = endMillis,
                minutes = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(1),
                inRangePercent = inRange * 100 / session.size,
                lowestMgdl = session.minOf { it.mgdl },
                lowCount = session.count {
                    val band = range.band(it.mgdl)
                    band == GlucoseBand.LOW || band == GlucoseBand.URGENT_LOW
                }
            )
        }
    }
}
