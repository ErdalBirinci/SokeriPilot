package app.sokeripilot.car

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.sokeripilot.R
import app.sokeripilot.data.GlucoseRepository
import app.sokeripilot.data.SettingsRepository
import app.sokeripilot.data.TimeAgo
import app.sokeripilot.monitor.MonitorLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Driving screen: value, trend, source and target range at a glance.
 * PaneTemplate accepts at most two rows, so the information is compacted.
 */
class GlucoseScreen(
    carContext: CarContext,
    private val repository: GlucoseRepository,
    private val settings: SettingsRepository,
    private val onRefresh: () -> Unit
) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var monitorResult: MonitorLauncher.Result? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Driving monitoring starts here even if the phone app is not open;
                // if the result fails, a warning row is shown in the cockpit.
                monitorResult = MonitorLauncher.start(carContext)
                stateJob?.cancel()
                stateJob = scope.launch {
                    repository.state.collect { invalidate() }
                }
                onRefresh()
                invalidate()
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
        val state = repository.state.value
        val range = settings.glucoseRange()
        val units = settings.units()
        val now = System.currentTimeMillis()
        val latest = state.latest

        val pane = Pane.Builder()
        val warning = notificationWarning()
        if (latest == null) {
            pane.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.label_waiting))
                    .addText(carContext.getString(R.string.car_waiting_body))
                    .build()
            )
            if (warning != null) pane.addRow(warningRow(warning))
            pane.setLoading(state.isRefreshing)
        } else {
            val band = range.band(latest.mgdl)
            pane.addRow(
                Row.Builder()
                    .setTitle("${units.format(latest.mgdl)} ${units.label}  ${latest.trend.arrow}")
                    .addText("${band.label} · ${latest.trend.label}")
                    .build()
            )
            val notes = buildList {
                add("${latest.source} · ${TimeAgo.format(now, latest.timestampMillis)}")
                add(
                    "${range.low}–${range.high} mg/dL · %${state.inRangePercent(range)} in range" +
                        " · this session"
                )
                latest.sensorBatteryPercent?.let {
                    add(carContext.getString(R.string.car_sensor_battery, it))
                }
                if (TimeAgo.isStale(now, latest.timestampMillis)) {
                    add(
                        state.sensorStatus?.label
                            ?: carContext.getString(R.string.car_stale)
                    )
                }
                state.lastError?.let { add(it) }
            }
            TrendImage.bitmap(carContext, state.readings, range, now)?.let { trend ->
                pane.setImage(CarIcon.Builder(IconCompat.createWithBitmap(trend)).build())
            }
            if (warning != null) {
                pane.addRow(warningRow(warning))
            } else {
                pane.addRow(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.car_row_range))
                        .addText(notes.joinToString(separator = "\n"))
                        .build()
                )
            }
            pane.setLoading(state.isRefreshing)
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(buildActionStrip())
            .build()
    }

    /**
     * Warns clearly on the car screen when driving mode is off, no CGM source is
     * configured, notification permission is missing or the service could not start.
     */
    private fun notificationWarning(): String? = when {
        monitorResult == MonitorLauncher.Result.DISABLED ->
            carContext.getString(R.string.car_monitor_off)
        !notificationAllowed() ->
            carContext.getString(R.string.car_notify_permission)
        monitorResult == MonitorLauncher.Result.NO_SOURCE ->
            carContext.getString(R.string.car_source_missing)
        monitorResult == MonitorLauncher.Result.BLOCKED ->
            carContext.getString(R.string.car_service_failed)
        else -> null
    }

    private fun notificationAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                carContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun warningRow(message: String): Row = Row.Builder()
        .setTitle(carContext.getString(R.string.car_warning_title))
        .addText(message)
        .build()

    @Suppress("DEPRECATION")
    private fun buildActionStrip(): ActionStrip {
        val refresh = Action.Builder()
            .setTitle(carContext.getString(R.string.action_refresh))
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_refresh)).build())
            .setOnClickListener { onRefresh() }
            .build()

        val recent = Action.Builder()
            .setTitle(carContext.getString(R.string.action_recent))
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_list)).build())
            .setOnClickListener {
                carContext.getCarService(ScreenManager::class.java).push(
                    TrendScreen(carContext, repository, settings)
                )
            }
            .build()

        return ActionStrip.Builder()
            .addAction(refresh)
            .addAction(recent)
            .build()
    }
}
