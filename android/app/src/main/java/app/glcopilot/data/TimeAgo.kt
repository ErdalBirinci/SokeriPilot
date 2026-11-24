package app.glcopilot.data

/** Short text that conveys the reading's age to the driver at a glance. */
object TimeAgo {

    const val STALE_AFTER_MILLIS = 15 * 60_000L

    fun format(nowMillis: Long, timestampMillis: Long): String {
        val diff = nowMillis - timestampMillis
        if (diff <= 0) return "now"
        val minutes = diff / 60_000L
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "$minutes min ago"
            else -> {
                val hours = minutes / 60
                val rest = minutes % 60
                if (rest == 0L) "$hours hr ago" else "$hours hr $rest min ago"
            }
        }
    }

    fun isStale(nowMillis: Long, timestampMillis: Long): Boolean =
        nowMillis - timestampMillis > STALE_AFTER_MILLIS
}
