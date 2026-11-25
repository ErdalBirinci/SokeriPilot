package app.sokeripilot.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.text.format.DateFormat
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.sokeripilot.SokeriPilotApp
import app.sokeripilot.R
import app.sokeripilot.data.AppContainer
import app.sokeripilot.data.CgmAppDetector
import app.sokeripilot.data.DiagnosticEvent
import app.sokeripilot.data.DiagnosticsLog
import app.sokeripilot.data.CgmIntegration
import app.sokeripilot.data.GlucoseBand
import app.sokeripilot.data.GlucoseState
import app.sokeripilot.data.HealthConnectBridge
import app.sokeripilot.data.SettingsExport
import app.sokeripilot.data.NotificationAccess
import app.sokeripilot.data.TimeAgo
import app.sokeripilot.data.Units
import app.sokeripilot.monitor.CarConnectionWatcher
import app.sokeripilot.monitor.GlucoseMonitorService
import app.sokeripilot.monitor.MonitorLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.roundToInt

/** Phone setup and live status screen. The car screen reads these settings. */
class MainActivity : AppCompatActivity() {

    private lateinit var container: AppContainer

    private lateinit var tvGlucose: TextView
    private lateinit var tvTrend: TextView
    private lateinit var tvBand: TextView
    private lateinit var tvMeta: TextView
    private lateinit var switchXdrip: Switch
    private lateinit var switchMonitor: Switch
    private lateinit var switchAutoCar: Switch
    private lateinit var tvCgm: TextView
    private lateinit var tvAccess: TextView
    private lateinit var btnAccess: Button
    private lateinit var btnSensorPause: Button
    private lateinit var tvSensorPause: TextView
    private lateinit var etUrl: EditText
    private lateinit var etSecret: EditText
    private lateinit var etLow: EditText
    private lateinit var etHigh: EditText
    private lateinit var rgUnits: RadioGroup
    private lateinit var tvDiagSummary: TextView
    private lateinit var tvDiagAccess: TextView
    private lateinit var diagList: LinearLayout
    private lateinit var btnDiagClear: Button
    private lateinit var switchPredictive: Switch
    private lateinit var tvLastDrive: TextView
    private lateinit var setupCheckList: LinearLayout
    private lateinit var switchHealthConnect: Switch
    private lateinit var tvHealthConnect: TextView
    private lateinit var btnExportSettings: Button
    private lateinit var btnImportSettings: Button

    /** True while the Health Connect switch is set from code, so it does not ask again. */
    private var settingHealthSwitch = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                container.settings.setNotificationsEnabled(true)
                toast(getString(R.string.notifications_granted))
                if (container.settings.monitorWhileDriving()) startMonitorIfConfigured(quiet = true)
            }
        }

    private val healthPermission = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val ok = granted.contains(HealthConnectBridge.WRITE_PERMISSION)
        container.settings.setHealthConnectEnabled(ok)
        showHealthEnabled(ok)
        toast(
            getString(
                if (ok) R.string.health_permission_granted else R.string.health_permission_denied
            )
        )
        renderHealthConnect()
    }

    private val exportSettingsFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportSettings(uri) }

    private val importSettingsFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importSettings(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = (application as SokeriPilotApp).container
        bindViews()
        loadSettings()
        bindActions()
        renderPeripheral()

        render(container.repository.state.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.repository.state.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.diagnostics.events.collect { renderDiagnostics(it) }
            }
        }
        // Refreshes the sensor-change countdown while the screen stays open.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    renderSensorPause()
                    delay(SENSOR_PAUSE_TICK_MILLIS)
                }
            }
        }
        container.refreshNow()
    }

    private fun bindViews() {
        tvGlucose = findViewById(R.id.tvGlucose)
        tvTrend = findViewById(R.id.tvTrend)
        tvBand = findViewById(R.id.tvBand)
        tvMeta = findViewById(R.id.tvMeta)
        switchXdrip = findViewById(R.id.switchXdrip)
        switchMonitor = findViewById(R.id.switchMonitor)
        switchAutoCar = findViewById(R.id.switchAutoCar)
        tvCgm = findViewById(R.id.tvCgm)
        tvAccess = findViewById(R.id.tvAccess)
        btnAccess = findViewById(R.id.btnNotificationAccess)
        btnSensorPause = findViewById(R.id.btnSensorPause)
        tvSensorPause = findViewById(R.id.tvSensorPause)
        switchPredictive = findViewById(R.id.switchPredictive)
        tvLastDrive = findViewById(R.id.tvLastDrive)
        setupCheckList = findViewById(R.id.setupCheckList)
        switchHealthConnect = findViewById(R.id.switchHealthConnect)
        tvHealthConnect = findViewById(R.id.tvHealthConnect)
        btnExportSettings = findViewById(R.id.btnExportSettings)
        btnImportSettings = findViewById(R.id.btnImportSettings)
        etUrl = findViewById(R.id.etUrl)
        etSecret = findViewById(R.id.etSecret)
        etLow = findViewById(R.id.etLow)
        etHigh = findViewById(R.id.etHigh)
        rgUnits = findViewById(R.id.rgUnits)
        tvDiagSummary = findViewById(R.id.tvDiagSummary)
        tvDiagAccess = findViewById(R.id.tvDiagAccess)
        diagList = findViewById(R.id.diagList)
        btnDiagClear = findViewById(R.id.btnDiagClear)
        btnDiagClear.setOnClickListener { container.diagnostics.clear() }
        findViewById<Button>(R.id.btnSaveSource).setOnClickListener { saveSource() }
        findViewById<Button>(R.id.btnSaveRange).setOnClickListener { saveRange() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshNow() }
        findViewById<Button>(R.id.btnNotifications).setOnClickListener { requestNotifications() }
        btnAccess.setOnClickListener { openNotificationAccess() }
        btnSensorPause.setOnClickListener { toggleSensorPause() }
    }

    private fun loadSettings() {
        switchXdrip.isChecked = container.settings.xDripEnabled()
        switchMonitor.isChecked = container.settings.monitorWhileDriving()
        switchAutoCar.isChecked = container.settings.autoStartOnCar()
        switchPredictive.isChecked = container.settings.predictiveAlerts()
        renderCgmDetection()
        etUrl.setText(container.settings.nightscoutConfig()?.baseUrl.orEmpty())
        etSecret.setText("")
        val range = container.settings.glucoseRange()
        etLow.setText(range.low.toString())
        etHigh.setText(range.high.toString())
        when (container.settings.units()) {
            Units.MG_DL -> rgUnits.check(R.id.rbMgdl)
            Units.MMOL_L -> rgUnits.check(R.id.rbMmol)
        }
    }

    private fun bindActions() {
        switchXdrip.setOnCheckedChangeListener { _, checked ->
            container.settings.setXDripEnabled(checked)
            if (container.settings.monitorWhileDriving()) startMonitorIfConfigured(quiet = true)
        }
        switchMonitor.setOnCheckedChangeListener { _, checked ->
            container.settings.setMonitorWhileDriving(checked)
            if (checked) startMonitorIfConfigured() else stopMonitor()
        }
        switchAutoCar.setOnCheckedChangeListener { _, checked ->
            container.settings.setAutoStartOnCar(checked)
            if (checked) startMonitorIfConfigured(quiet = true)
        }
        rgUnits.setOnCheckedChangeListener { _, checkedId ->
            container.settings.saveUnits(
                if (checkedId == R.id.rbMmol) Units.MMOL_L else Units.MG_DL
            )
            render(container.repository.state.value)
        }
        switchPredictive.setOnCheckedChangeListener { _, checked ->
            container.settings.setPredictiveAlerts(checked)
        }
        switchHealthConnect.setOnCheckedChangeListener { _, checked ->
            if (settingHealthSwitch) return@setOnCheckedChangeListener
            if (checked) {
                requestHealthConnect()
            } else {
                container.settings.setHealthConnectEnabled(false)
                renderHealthConnect()
            }
        }
        btnExportSettings.setOnClickListener { exportSettingsFile.launch(EXPORT_FILE_NAME) }
        btnImportSettings.setOnClickListener {
            importSettingsFile.launch(arrayOf("application/json"))
        }
    }

    private fun saveSource() {
        val error = container.settings.saveNightscout(
            baseUrl = etUrl.text.toString(),
            apiSecret = etSecret.text?.toString()
        )
        if (error != null) {
            toast(error)
            return
        }
        etSecret.setText("")
        hideKeyboard()
        toast(getString(R.string.saved))
        container.refreshNow()
        if (container.settings.monitorWhileDriving()) startMonitorIfConfigured(quiet = true)
    }

    private fun saveRange() {
        val units = container.settings.units()
        val low = units.parseToMgdl(etLow.text.toString())
        val high = units.parseToMgdl(etHigh.text.toString())
        if (low == null || high == null) {
            toast(getString(R.string.error_range))
            return
        }
        val error = container.settings.saveRange(low, high)
        if (error != null) {
            toast(error)
            return
        }
        toast(getString(R.string.saved))
        render(container.repository.state.value)
    }

    override fun onStart() {
        super.onStart()
        if (container.settings.monitorWhileDriving()) startMonitorIfConfigured(quiet = true)
    }

    override fun onResume() {
        super.onResume()
        renderCgmDetection()
        renderDiagnostics(container.diagnostics.events.value)
        renderPeripheral()
        switchXdrip.isChecked = container.settings.xDripEnabled()
        // Returning from the notification-access screen: start right away.
        if (NotificationAccess.isGranted(this) && container.settings.monitorWhileDriving()) {
            startMonitorIfConfigured(quiet = true)
        }
    }

    /** Opens the one system screen that lets SokeriPilot read the CGM app's values. */
    private fun openNotificationAccess() {
        try {
            startActivity(NotificationAccess.settingsIntent())
        } catch (_: RuntimeException) {
            toast(getString(R.string.access_off))
        }
    }

    /**
     * Shows installed CGM apps and the one-time notification access. With access
     * granted, every CGM app feeds the cockpit without any setup inside that app.
     * xDrip+ broadcasts stay enabled automatically when available.
     */
    private fun renderCgmDetection() {
        val detected = CgmAppDetector.detect(this)
        val broadcast = detected.filter { it.integration == CgmIntegration.XDRIP_BROADCAST }
        val access = NotificationAccess.isGranted(this)

        if (broadcast.isNotEmpty() && !container.settings.xDripUserChose()) {
            container.settings.autoEnableXDrip(true)
            switchXdrip.isChecked = true
        }

        val labels = detected.joinToString(", ") { it.label }
            .ifEmpty { getString(R.string.source_cgm_app) }

        tvAccess.text = if (access) {
            getString(R.string.access_on)
        } else {
            getString(R.string.access_off)
        }
        btnAccess.visibility = if (access) View.GONE else View.VISIBLE

        tvCgm.text = when {
            detected.isNotEmpty() && access -> getString(R.string.cgm_auto_on, labels)
            detected.isNotEmpty() -> getString(R.string.cgm_needs_access, labels)
            container.settings.nightscoutConfig() != null -> getString(R.string.source_nightscout)
            else -> getString(R.string.cgm_not_found)
        }
    }

    /**
     * Starts the background service when driving mode is on. Keeps CGM current in
     * the notification while navigation or media is open in Android Auto.
     */
    private fun startMonitorIfConfigured(quiet: Boolean = false) {
        if (!notificationAllowed()) {
            if (!quiet) requestNotifications()
            return
        }
        when (MonitorLauncher.start(this)) {
            MonitorLauncher.Result.STARTED -> Unit
            MonitorLauncher.Result.DISABLED -> stopMonitor()
            MonitorLauncher.Result.NO_SOURCE -> {
                if (quiet) {
                    stopMonitor()
                } else {
                    switchMonitor.isChecked = false
                    container.settings.setMonitorWhileDriving(false)
                    toast(getString(R.string.monitor_requires_source))
                }
            }
            MonitorLauncher.Result.NOT_CONNECTED -> {
                if (!quiet) toast(getString(R.string.monitor_waiting_for_car))
            }
            MonitorLauncher.Result.BLOCKED -> {
                if (!quiet) toast(getString(R.string.monitor_start_failed))
            }
        }
    }

    private fun stopMonitor() {
        stopService(Intent(this, GlucoseMonitorService::class.java))
        container.notifier.cancelStatus()
    }

    private fun notificationAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun refreshNow() {
        if (container.settings.nightscoutConfig() == null) {
            toast(getString(R.string.car_waiting_body))
            return
        }
        container.refreshNow()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            container.settings.setNotificationsEnabled(true)
            toast(getString(R.string.notifications_granted))
        }
    }

    /**
     * A replacement sensor spends up to two hours warming up with no readings. Marking
     * the change here keeps the "No glucose data" reminder quiet instead of alarming
     * mid-drive; the cockpit still shows the age of the last value.
     */
    private fun toggleSensorPause() {
        if (container.settings.staleRemindersPaused()) {
            container.settings.clearStalePause()
        } else {
            container.settings.pauseStaleReminders()
        }
        renderSensorPause()
    }

    private fun renderSensorPause() {
        val remaining = container.settings.sensorChangePauseUntil() - System.currentTimeMillis()
        if (remaining > 0) {
            val minutes = ((remaining + 59_999L) / 60_000L).toInt()
            tvSensorPause.text = getString(R.string.sensor_change_paused, minutes)
            btnSensorPause.setText(R.string.action_sensor_resume)
        } else {
            tvSensorPause.setText(R.string.sensor_change_explained)
            btnSensorPause.setText(R.string.action_sensor_change)
        }
    }

    /** Last drive, setup rows and Health Connect; refreshed whenever the screen resumes. */
    private fun renderPeripheral() {
        renderLastDrive()
        renderSetupCheck()
        renderHealthConnect()
    }

    /** Four rows that show whether the pieces needed for the car are actually working. */
    private fun renderSetupCheck() {
        val latest = container.repository.state.value.latest
        val fresh = latest != null &&
            !TimeAgo.isStale(System.currentTimeMillis(), latest.timestampMillis)
        val items = listOf(
            NotificationAccess.isGranted(this) to getString(R.string.setup_notification_access),
            notificationAllowed() to getString(R.string.setup_notification_permission),
            fresh to getString(R.string.setup_cgm_value),
            CarConnectionWatcher.currentState(this).isConnected to
                getString(R.string.setup_car_connection)
        )
        setupCheckList.removeAllViews()
        items.forEach { (ok, label) ->
            setupCheckList.addView(
                setupLine(getString(if (ok) R.string.setup_ready else R.string.setup_pending, label), ok)
            )
        }
    }

    private fun setupLine(text: String, ok: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(
            ContextCompat.getColor(
                this@MainActivity,
                if (ok) R.color.mint_600 else R.color.ink_muted
            )
        )
        setPadding(0, dp(6), 0, 0)
    }

    private fun renderLastDrive() {
        val summary = container.settings.lastDrive() ?: run {
            tvLastDrive.visibility = View.GONE
            return
        }
        val units = container.settings.units()
        tvLastDrive.visibility = View.VISIBLE
        tvLastDrive.text = getString(
            R.string.last_drive,
            DateFormat.getTimeFormat(this).format(Date(summary.endedAtMillis)),
            summary.minutes,
            summary.inRangePercent,
            units.format(summary.lowestMgdl),
            units.label
        )
    }

    private fun renderHealthConnect() {
        val available = container.health.isAvailable()
        switchHealthConnect.isEnabled = available
        if (!available) {
            showHealthEnabled(false)
            tvHealthConnect.setText(R.string.health_unavailable)
            return
        }
        val enabled = container.settings.healthConnectEnabled()
        showHealthEnabled(enabled)
        if (!enabled) {
            tvHealthConnect.setText(R.string.health_permission_off)
            return
        }
        tvHealthConnect.setText(R.string.health_permission_on)
        lifecycleScope.launch {
            if (!container.health.hasPermission()) {
                container.settings.setHealthConnectEnabled(false)
                showHealthEnabled(false)
                tvHealthConnect.setText(R.string.health_permission_off)
            }
        }
    }

    private fun showHealthEnabled(enabled: Boolean) {
        settingHealthSwitch = true
        switchHealthConnect.isChecked = enabled
        settingHealthSwitch = false
    }

    private fun requestHealthConnect() {
        if (!container.health.isAvailable()) {
            showHealthEnabled(false)
            toast(getString(R.string.health_unavailable))
            renderHealthConnect()
            return
        }
        healthPermission.launch(setOf(HealthConnectBridge.WRITE_PERMISSION))
    }

    private fun exportSettings(uri: Uri) {
        val json = SettingsExport.toJson(container.settings.snapshot())
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            toast(getString(R.string.settings_exported))
        } catch (_: Exception) {
            toast(getString(R.string.settings_export_failed))
        }
    }

    private fun importSettings(uri: Uri) {
        val raw = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
        val snapshot = raw?.let(SettingsExport::fromJson)
        if (snapshot == null) {
            toast(getString(R.string.settings_import_failed))
            return
        }
        container.settings.restore(snapshot)
        loadSettings()
        render(container.repository.state.value)
        renderPeripheral()
        toast(getString(R.string.settings_imported))
    }

    private fun render(state: GlucoseState) {
        val units = container.settings.units()
        val range = container.settings.glucoseRange()
        val latest = state.latest
        val now = System.currentTimeMillis()

        if (latest == null) {
            tvGlucose.text = "--"
            tvTrend.text = ""
            tvMeta.text = state.lastError ?: getString(R.string.car_waiting_body)
            applyBandPill(
                text = getString(R.string.label_waiting),
                background = R.color.canvas_alt,
                foreground = R.color.ink_muted
            )
            return
        }

        val band = range.band(latest.mgdl)
        tvGlucose.text = units.format(latest.mgdl)
        tvTrend.text = latest.trend.arrow
        applyBandPill(
            text = "${band.label} · ${units.label}",
            background = bandBackground(band),
            foreground = bandForeground(band)
        )

        tvMeta.text = buildString {
            append(TimeAgo.format(now, latest.timestampMillis))
            append(" · ").append(latest.source)
            latest.sensorBatteryPercent?.let { append(" · sensor %").append(it) }
            state.sensorStatus?.let { append(" · ").append(it.label) }
            if (TimeAgo.isStale(now, latest.timestampMillis)) append(" · stale data")
            state.lastError?.let { append(" · ").append(it) }
        }
    }

    private fun applyBandPill(text: String, background: Int, foreground: Int) {
        tvBand.text = text
        tvBand.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, background))
        tvBand.setTextColor(ContextCompat.getColor(this, foreground))
    }

    private fun bandBackground(band: GlucoseBand): Int = when (band) {
        GlucoseBand.IN_RANGE -> R.color.mint_100
        GlucoseBand.LOW, GlucoseBand.URGENT_LOW -> R.color.coral_100
        GlucoseBand.HIGH -> R.color.amber_100
        GlucoseBand.URGENT_HIGH -> R.color.coral_100
    }

    private fun bandForeground(band: GlucoseBand): Int = when (band) {
        GlucoseBand.IN_RANGE -> R.color.mint_600
        GlucoseBand.LOW -> R.color.coral_600
        GlucoseBand.URGENT_LOW -> R.color.coral_600
        GlucoseBand.HIGH -> R.color.amber_600
        GlucoseBand.URGENT_HIGH -> R.color.coral_600
    }

    /**
     * Shows what the last CGM notifications actually contained: the latest value with
     * its source app, then the last ten notifications with accepted/rejected reasons.
     * This is how a user diagnoses "no value in the car" without a computer.
     */
    private fun renderDiagnostics(events: List<DiagnosticEvent>) {
        val access = NotificationAccess.isGranted(this)
        val units = container.settings.units()
        val latest = container.repository.state.value.latest
        val now = System.currentTimeMillis()

        tvDiagAccess.text = if (access) {
            getString(R.string.diag_checked, DiagnosticsLog.CAPACITY)
        } else {
            getString(R.string.diag_no_access)
        }

        tvDiagSummary.text = if (latest == null) {
            getString(R.string.diag_last_none)
        } else {
            val value = getString(R.string.diag_value, units.format(latest.mgdl), units.label)
            val age = TimeAgo.format(now, latest.timestampMillis)
            getString(R.string.diag_last_reading, "$value · ${latest.source} · $age")
        }

        diagList.removeAllViews()
        if (events.isEmpty()) {
            diagList.addView(diagnosticLine(getString(R.string.diag_empty)))
        } else {
            val timeFormat = DateFormat.getTimeFormat(this)
            events.forEach { event ->
                val time = timeFormat.format(Date(event.atMillis))
                val app = event.appLabel ?: event.packageName
                val line = when (event.outcome) {
                    DiagnosticEvent.Outcome.ACCEPTED -> getString(
                        R.string.diag_row_accepted,
                        time,
                        app,
                        getString(R.string.diag_value, units.format(event.valueMgdl ?: 0), units.label)
                    )
                    DiagnosticEvent.Outcome.REJECTED -> getString(
                        R.string.diag_row_rejected,
                        time,
                        app,
                        event.reason?.label ?: "unknown reason"
                    )
                }
                diagList.addView(diagnosticLine(line))
            }
        }
        btnDiagClear.isEnabled = events.isNotEmpty()
    }

    private fun diagnosticLine(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_muted))
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(7), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(etSecret.windowToken, View.GONE)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** The countdown is shown in whole minutes; half a minute keeps it accurate. */
        const val SENSOR_PAUSE_TICK_MILLIS = 30_000L
        const val EXPORT_FILE_NAME = "sokeripilot-settings.json"
    }
}
