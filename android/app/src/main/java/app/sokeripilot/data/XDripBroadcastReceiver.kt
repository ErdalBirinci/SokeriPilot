package app.sokeripilot.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the xDrip+ local broadcast. Because any app can send the broadcast, all
 * fields are validated for range and time in XDripReadingMapper.
 */
class XDripBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != XDripReadingMapper.ACTION_BG_ESTIMATE) return
        if (!AppGraph.isInitialized) return
        if (!AppGraph.container.settings.xDripEnabled()) return

        val extras = intent.extras ?: return
        val reading = XDripReadingMapper.map(
            bg = (extras.get(XDripReadingMapper.EXTRA_BG_ESTIMATE) as? Number)?.toDouble(),
            slopePerMs = (extras.get(XDripReadingMapper.EXTRA_BG_SLOPE) as? Number)?.toDouble(),
            slopeName = extras.getString(XDripReadingMapper.EXTRA_BG_SLOPE_NAME),
            timeMillis = (extras.get(XDripReadingMapper.EXTRA_TIMESTAMP) as? Number)?.toLong(),
            batteryPercent = (extras.get(XDripReadingMapper.EXTRA_SENSOR_BATTERY) as? Number)?.toInt()
        ) ?: return

        AppGraph.container.repository.onLocalReading(reading)
    }
}
