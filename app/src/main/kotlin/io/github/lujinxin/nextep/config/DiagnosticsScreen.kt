package io.github.lujinxin.nextep.config

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import io.github.lujinxin.nextep.safety.FeatureGate
import io.github.lujinxin.nextep.trigger.TriggerCoordinator
import io.github.lujinxin.nextep.trigger.TriggerSource

object DiagnosticsScreen {
    fun create(context: Context, settings: SettingsRepository): View {
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(text(context, "NeXtep", 28f))
            addView(text(context, "P0-P6 module and device diagnostics", 16f))
            addView(section(context, "Module"))
            addView(text(context, "Package: ${context.packageName}"))
            addView(text(context, "LSPosed state: verify in LSPosed Manager and Logcat"))
            addView(text(context, "After every module APK update: reboot the phone before testing hooks"))
            addView(text(context, "Runtime protocol 34: media/action top bar and mapped shade input"))
            addView(text(context, "Top-right gesture: horizontal-left only; vertical shade pulls pass through"))
            addView(text(context, "Main task: WM Shell leash scale with exact restore and inverse touch mapping"))
            addView(text(context, "Three slots: top-strip drag; tap Ready to park main, Occupied to exchange"))
            addView(text(context, "Slot safety: no buttons, swipe actions, or slot long-press actions"))
            addView(text(context, "P8 recent images: click-only MediaStore previews; Apps remain the only slot drag source"))
            addView(text(context, "Recovery: two-way migration rollback, duplicate suppression, task-exit cleanup"))
            addView(text(context, "Rendering: fresh TextureView consumers prevent stale frames after re-entry"))
            val workspaceStatus = text(context, "Workspace verification state: querying…")
            var lastKnownActive: Boolean? = null
            val updateWorkspaceStatus: (TriggerCoordinator.RequestResult) -> Unit = { result ->
                if (result is TriggerCoordinator.RequestResult.Ready) {
                    lastKnownActive = result.active
                }
                workspaceStatus.text = result.describe()
            }
            val refreshWorkspaceStatus = {
                TriggerCoordinator.queryState(context) { result ->
                    updateWorkspaceStatus(result)
                }
            }
            viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) refreshWorkspaceStatus()
            }
            addView(workspaceStatus)
            addView(Button(context).apply {
                text = "Toggle workspace verification"
                setOnClickListener {
                    val activity = context as? Activity
                    val trigger = Runnable {
                        val callback: (TriggerCoordinator.RequestResult) -> Unit = { result ->
                            updateWorkspaceStatus(result)
                        }
                        if (lastKnownActive == true) {
                            TriggerCoordinator.requestSet(
                                context,
                                false,
                                TriggerSource.SETTINGS,
                                callback,
                            )
                        } else {
                            TriggerCoordinator.requestToggle(context, TriggerSource.SETTINGS, callback)
                        }
                    }
                    if (activity?.moveTaskToBack(true) == true) {
                        Handler(Looper.getMainLooper()).postDelayed(trigger, 250L)
                    } else {
                        trigger.run()
                    }
                }
            })
            addView(Button(context).apply {
                text = "Launch Settings in slot 1"
                setOnClickListener {
                    val activity = context as? Activity
                    val launch = Runnable {
                        TriggerCoordinator.requestLaunchSlotTest(context) { result ->
                            updateWorkspaceStatus(result)
                        }
                    }
                    if (activity?.moveTaskToBack(true) == true) {
                        Handler(Looper.getMainLooper()).postDelayed(launch, 250L)
                    } else {
                        launch.run()
                    }
                }
            })
            refreshWorkspaceStatus()
            addView(section(context, "Device"))
            addView(text(context, "Model: ${Build.MANUFACTURER} ${Build.MODEL}"))
            addView(text(context, "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"))
            addView(text(context, "Build: ${Build.DISPLAY}"))
            addView(text(context, "ABI: ${Build.SUPPORTED_ABIS.joinToString()}"))
            addView(section(context, "Feature gates"))
            FeatureGate.entries.forEach { feature ->
                addView(text(context, "${feature.name}: ${settings.isEnabled(feature)}"))
            }
            addView(section(context, "Next evidence"))
            addView(text(context, "After an APK update, fully reboot before repeating device acceptance."))
        }
    }

    private fun section(context: Context, value: String) = text(context, value, 20f).apply {
        setPadding(0, 28, 0, 8)
    }

    private fun text(context: Context, value: String, size: Float = 15f) = TextView(context).apply {
        text = value
        textSize = size
        setPadding(0, 4, 0, 4)
    }

    private fun TriggerCoordinator.RequestResult.describe(): String = when (this) {
        is TriggerCoordinator.RequestResult.Ready ->
            "Workspace verification: ${if (active) "active" else "inactive"} ($homePackage)" +
                (slotStates?.let { "\nSlots: $it" } ?: "")
        is TriggerCoordinator.RequestResult.NotReady -> "Workspace verification unavailable: $reason"
    }
}
