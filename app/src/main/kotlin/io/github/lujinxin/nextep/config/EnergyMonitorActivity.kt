package io.github.lujinxin.nextep.config

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.Display
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.monitoring.BatterySample
import io.github.lujinxin.nextep.monitoring.WorkspaceEnergyContract
import java.util.Date
import java.util.concurrent.Executors

/** Samples only while this screen is resumed and the physical device is unlocked. */
class EnergyMonitorActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-EnergyMonitor").apply { isDaemon = true }
    }
    private val values = linkedMapOf<Int, TextView>()
    private lateinit var sampleLabel: TextView
    private lateinit var copyButton: MaterialButton
    private var resumed = false
    private var monitoring = false
    private var inFlight = false
    private var generation = 0
    private var sampleCount = 0
    private var initialTemperature: Double? = null
    private var dischargePowerSum = 0.0
    private var dischargeSamples = 0
    private var previousRuntime: WorkspaceEnergyContract.Snapshot? = null
    private val tick = Runnable { sample() }
    private var wakeAttempts = 0
    private val wakeRetry = object : Runnable {
        override fun run() {
            if (!resumed || isFinishing || isDestroyed) return
            if (canSample()) startSampling()
            else if (++wakeAttempts < 8) handler.postDelayed(this, 500L)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopSampling() else scheduleSamplingResume()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !SettingsPalette.isDark(this@EnergyMonitorActivity)
            isAppearanceLightNavigationBars = !SettingsPalette.isDark(this@EnergyMonitorActivity)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(SettingsRowButton.create(this@EnergyMonitorActivity).apply {
                    setText(R.string.energy_back)
                    setOnClickListener { finish() }
                })
                addView(heading(R.string.energy_monitor_title, 24f), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(16)
                })
            }, LinearLayout.LayoutParams(-1, -2))
            addView(paragraph(R.string.energy_monitor_notice), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(16); bottomMargin = dp(16)
            })
            sampleLabel = paragraph(R.string.energy_waiting)
            addView(sampleLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            addView(heading(R.string.energy_battery_heading))
            listOf(R.string.energy_battery_level, R.string.energy_battery_temperature, R.string.energy_temperature_change,
                R.string.energy_charge_state, R.string.energy_voltage, R.string.energy_current,
                R.string.energy_battery_power, R.string.energy_average_discharge,
                R.string.energy_thermal_status, R.string.energy_power_save).forEach { addView(metricRow(it)) }
            addView(heading(R.string.energy_runtime_heading), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(24); bottomMargin = dp(8)
            })
            listOf(R.string.energy_workspace_state, R.string.energy_slots, R.string.energy_slot_frames,
                R.string.energy_task_queries, R.string.energy_preview_captures, R.string.energy_host_cpu)
                .forEach { addView(metricRow(it)) }
            addView(paragraph(R.string.energy_runtime_notice), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12); bottomMargin = dp(16)
            })
            copyButton = SettingsRowButton.create(this@EnergyMonitorActivity).apply {
                setText(R.string.energy_copy_report)
                isEnabled = false
                setOnClickListener {
                    val report = buildString {
                        appendLine(getString(R.string.energy_monitor_title))
                        appendLine(sampleLabel.text)
                        values.forEach { (label, value) -> appendLine("${getString(label)}: ${value.text}") }
                        appendLine(getString(R.string.energy_monitor_notice))
                        append(getString(R.string.energy_runtime_notice))
                    }
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText(getString(R.string.energy_monitor_title), report))
                    Toast.makeText(this@EnergyMonitorActivity, R.string.energy_report_copied, Toast.LENGTH_SHORT).show()
                }
            }
            addView(copyButton, LinearLayout.LayoutParams(-2, -2))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(SettingsPalette.page(this@EnergyMonitorActivity))
            clipToPadding = false
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        scheduleSamplingResume()
    }

    override fun onPause() {
        resumed = false
        stopSampling()
        super.onPause()
    }

    override fun onStop() {
        unregisterReceiver(screenReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun canSample(): Boolean = resumed && !isFinishing && !isDestroyed &&
        // Activity services can stay associated with the previous virtual display
        // after a task move. Gate sampling on the physical device instead.
        applicationContext.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        applicationContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
        applicationContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    private fun scheduleSamplingResume() {
        handler.removeCallbacks(wakeRetry)
        wakeAttempts = 0
        // USER_PRESENT/resume can race the cached keyguard/display state. Retry
        // briefly after that event; never leave a permanent lock-screen poll.
        handler.post(wakeRetry)
    }

    private fun startSampling() {
        if (monitoring || !canSample()) return
        monitoring = true
        generation += 1
        previousRuntime = null
        handler.post(tick)
    }

    private fun stopSampling() {
        monitoring = false
        generation += 1
        previousRuntime = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(wakeRetry)
    }

    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter,
        args: Array<out String>?) {
        super.dump(prefix, fd, writer, args)
        writer.println("${prefix}NeXtep energy: monitoring=$monitoring samples=$sampleCount resumed=$resumed inFlight=$inFlight")
    }

    private fun sample() {
        if (!monitoring) return
        if (!canSample()) { stopSampling(); return }
        if (inFlight) {
            handler.postDelayed(tick, SAMPLE_INTERVAL_MS)
            return
        }
        inFlight = true
        val token = generation
        worker.execute {
            val battery = runCatching { BatterySample.read(applicationContext) }.getOrNull()
            handler.post {
                if (token != generation || !monitoring) {
                    finishSample()
                    return@post
                }
                if (battery != null) renderBattery(battery)
                WorkspaceEnergyContract.query(this) { runtime ->
                    if (token == generation && monitoring) renderRuntime(runtime)
                    finishSample()
                }
            }
        }
    }

    private fun finishSample() {
        inFlight = false
        handler.removeCallbacks(tick)
        if (monitoring) handler.postDelayed(tick, SAMPLE_INTERVAL_MS)
    }

    private fun renderBattery(sample: BatterySample) {
        sampleCount += 1
        val now = android.text.format.DateFormat.getTimeFormat(this).format(Date())
        sampleLabel.text = getString(R.string.energy_sample_count, sampleCount, now)
        copyButton.isEnabled = true
        if (initialTemperature == null) initialTemperature = sample.temperatureC
        setValue(R.string.energy_battery_level, sample.levelPercent?.let { "${format("%.0f", it)}%" })
        setValue(R.string.energy_battery_temperature, sample.temperatureC?.let { "${format("%.1f", it)} °C" })
        setValue(R.string.energy_temperature_change, sample.temperatureC?.let { value ->
            initialTemperature?.let { "${format("%+.1f", value - it)} °C" }
        })
        setValue(R.string.energy_charge_state, getString(when (sample.status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> R.string.energy_charging
            BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.energy_discharging
            BatteryManager.BATTERY_STATUS_FULL -> R.string.energy_full
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.energy_not_charging
            else -> R.string.energy_unavailable
        }))
        setValue(R.string.energy_voltage, sample.voltageV?.let { "${format("%.2f", it)} V" })
        setValue(R.string.energy_current, sample.currentMa?.let { "${format("%+.0f", it)} mA" })
        setValue(R.string.energy_battery_power, sample.batteryPowerW?.let { "${format("%+.2f", it)} W" })
        sample.dischargePowerW?.let { dischargePowerSum += it; dischargeSamples += 1 }
        setValue(R.string.energy_average_discharge,
            if (dischargeSamples >= 2) "${format("%.2f", dischargePowerSum / dischargeSamples)} W" else null)
        setValue(R.string.energy_thermal_status, sample.thermalStatus?.let { status ->
            val labels = intArrayOf(R.string.energy_thermal_none, R.string.energy_thermal_light,
                R.string.energy_thermal_moderate, R.string.energy_thermal_severe, R.string.energy_thermal_critical,
                R.string.energy_thermal_emergency, R.string.energy_thermal_shutdown)
            labels.getOrNull(status)?.let(::getString)
        })
        setValue(R.string.energy_power_save, sample.powerSave?.let {
            getString(if (it) R.string.energy_enabled else R.string.energy_disabled)
        })
    }

    private fun renderRuntime(snapshot: WorkspaceEnergyContract.Snapshot?) {
        val previous = previousRuntime
        previousRuntime = snapshot
        val labels = listOf(R.string.energy_workspace_state, R.string.energy_slots, R.string.energy_slot_frames,
            R.string.energy_task_queries, R.string.energy_preview_captures, R.string.energy_host_cpu)
        if (snapshot == null) {
            labels.forEach { setValue(it, null) }
            setValue(R.string.energy_workspace_state, getString(R.string.energy_runtime_unavailable))
            return
        }
        setValue(R.string.energy_workspace_state, getString(when {
            snapshot.suspended -> R.string.energy_workspace_suspended
            snapshot.active -> R.string.energy_enabled
            else -> R.string.energy_disabled
        }))
        setValue(R.string.energy_slots, snapshot.slotCount.toString())
        val elapsed = if (previous != null && previous.processId == snapshot.processId)
            (snapshot.elapsedMs - previous.elapsedMs).takeIf { it > 0 } else null
        fun rate(current: Long, old: Long?): Double? = if (elapsed != null && old != null && current >= old)
            (current - old) * 1_000.0 / elapsed else null
        setValue(R.string.energy_task_queries, rate(snapshot.taskQueries, previous?.taskQueries)?.let {
            getString(R.string.energy_rate, it)
        })
        setValue(R.string.energy_preview_captures, rate(snapshot.previewCaptures, previous?.previewCaptures)?.let {
            getString(R.string.energy_rate, it)
        })
        setValue(R.string.energy_slot_frames, rate(snapshot.slotFrames, previous?.slotFrames)?.let {
            getString(R.string.energy_rate, it)
        })
        setValue(R.string.energy_host_cpu, rate(snapshot.cpuMs, previous?.cpuMs)?.let { "${format("%.1f", it / 10.0)}%" })
    }

    private fun setValue(label: Int, value: String?) {
        val target = values.getValue(label)
        val text = value ?: getString(R.string.energy_unavailable)
        if (target.text.toString() != text) target.text = text
    }

    private fun metricRow(label: Int) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(0, dp(10), 0, dp(10))
        addView(TextView(context).apply {
            setText(label)
            textSize = 15f
            setTextColor(SettingsPalette.text(context))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        val value = TextView(context).apply {
            setText(R.string.energy_unavailable)
            textSize = 15f
            setTextColor(SettingsPalette.secondary(context))
            gravity = Gravity.END
            maxWidth = dp(160)
        }
        values[label] = value
        addView(value, LinearLayout.LayoutParams(-2, -2))
    }

    private fun heading(label: Int, size: Float = 18f) = TextView(this).apply {
        setText(label)
        textSize = size
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(SettingsPalette.text(context))
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun paragraph(label: Int) = TextView(this).apply {
        setText(label)
        textSize = 13f
        setTextColor(SettingsPalette.secondary(context))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun format(pattern: String, value: Double) = String.format(AppLanguage.locale(this), pattern, value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SAMPLE_INTERVAL_MS = 5_000L
    }
}
