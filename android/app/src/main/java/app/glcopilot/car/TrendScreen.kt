package app.glcopilot.car

import android.text.format.DateFormat
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.glcopilot.R
import app.glcopilot.data.GlucoseRepository
import app.glcopilot.data.SettingsRepository
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Recent readings list; one tap gives the driver history. */
class TrendScreen(
    carContext: CarContext,
    private val repository: GlucoseRepository,
    private val settings: SettingsRepository
) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                stateJob?.cancel()
                stateJob = scope.launch {
                    repository.state.collect { invalidate() }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                stateJob?.cancel()
                stateJob = null
            }

            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val units = settings.units()
        val timeFormat = DateFormat.getTimeFormat(carContext)
        val readings = repository.state.value.readings.take(MAX_ROWS)

        val list = ItemList.Builder()
        if (readings.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(R.string.car_no_items))
        } else {
            readings.forEach { reading ->
                list.addItem(
                    Row.Builder()
                        .setTitle("${units.format(reading.mgdl)} ${units.label}  ${reading.trend.arrow}")
                        .addText("${timeFormat.format(Date(reading.timestampMillis))} · ${reading.source}")
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_title_recent))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    companion object {
        private const val MAX_ROWS = 12
    }
}
