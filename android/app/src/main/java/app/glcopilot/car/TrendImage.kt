package app.glcopilot.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.ContextCompat
import app.glcopilot.R
import app.glcopilot.data.GlucoseRange
import app.glcopilot.data.GlucoseReading

/**
 * The cockpit's one-glance trend: the last 90 minutes as a line over a soft target
 * band. Drawn at runtime, no bitmap shipped, and only shown when there are at least
 * two readings in the window.
 */
object TrendImage {

    const val WINDOW_MILLIS = 90 * 60_000L
    const val WIDTH = 600
    const val HEIGHT = 220

    fun bitmap(
        context: Context,
        readings: List<GlucoseReading>,
        range: GlucoseRange,
        nowMillis: Long
    ): Bitmap? {
        val window = readings
            .filter { it.timestampMillis in (nowMillis - WINDOW_MILLIS)..nowMillis }
            .sortedBy { it.timestampMillis }
        if (window.size < 2) return null

        val values = window.map { it.mgdl }
        val min = minOf(values.min(), range.low - 20).coerceAtLeast(20)
        val max = maxOf(values.max(), range.high + 20).coerceAtMost(600)
        val span = (max - min).coerceAtLeast(20)
        val firstAt = window.first().timestampMillis
        val lastAt = window.last().timestampMillis
        val timeSpan = (lastAt - firstAt).coerceAtLeast(1L)

        fun y(value: Int): Float = HEIGHT - (value - min) * HEIGHT / span.toFloat()
        fun x(timestamp: Long): Float =
            (timestamp - firstAt) * WIDTH / timeSpan.toFloat()

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val band = Paint().apply {
            color = ContextCompat.getColor(context, R.color.mint_100)
            alpha = 130
        }
        canvas.drawRect(0f, y(range.high), WIDTH.toFloat(), y(range.low), band)

        val line = Paint().apply {
            color = ContextCompat.getColor(context, R.color.mint_600)
            strokeWidth = 10f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        window.forEachIndexed { index, reading ->
            val px = x(reading.timestampMillis)
            val py = y(reading.mgdl)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, line)

        val dot = Paint().apply {
            color = ContextCompat.getColor(context, R.color.mint_600)
            isAntiAlias = true
        }
        val latest = window.last()
        canvas.drawCircle(x(latest.timestampMillis), y(latest.mgdl), 12f, dot)

        return bitmap
    }
}
