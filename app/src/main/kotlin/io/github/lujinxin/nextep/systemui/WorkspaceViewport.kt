package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.WorkspaceNavigationInsets
import io.github.lujinxin.nextep.workspace.WorkspaceLayoutProfile
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import kotlin.math.roundToInt

/** SystemUI records the physical viewport once per display configuration/manual mode. */
internal class WorkspaceViewport(
    private val context: Context,
    private val onChanged: () -> Unit,
) {
    private data class Snapshot(
        val width: Int,
        val height: Int,
        val density: Float,
        val navigationInsets: WorkspaceNavigationInsets,
        val displayRotation: Int,
    ) {
        companion object {
            fun decode(encoded: String?, buttonsEnabled: Boolean, displayRotation: Int): Snapshot? = runCatching {
                val parts = encoded?.split(',') ?: return@runCatching null
                if (parts.size != 7) return@runCatching null
                val snapshot = Snapshot(parts[0].toInt(), parts[1].toInt(), parts[2].toFloat(),
                    WorkspaceNavigationInsets(parts[3].toInt(), parts[4].toInt(),
                        parts[5].toInt(), parts[6].toInt()), displayRotation)
                val insets = snapshot.navigationInsets
                check(snapshot.width > 1 && snapshot.height > 1)
                check(snapshot.density.isFinite() && snapshot.density > 0f)
                check(listOf(insets.left, insets.top, insets.right, insets.bottom).all { it >= 0 })
                check(insets.left.toLong() + insets.right < snapshot.width - 1L)
                check(insets.top.toLong() + insets.bottom < snapshot.height - 1L)
                check(buttonsEnabled || insets == WorkspaceNavigationInsets())
                snapshot
            }.getOrNull()
        }
    }

    // This cache belongs to the injected SystemUI process. Device-protected storage
    // lets a restarted SystemUI retain the selected mode before the first unlock.
    private val preferences = runCatching {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("nextep_viewport_profiles_v1", Context.MODE_PRIVATE)
    }.onFailure { NeXtepLog.warn("workspace_viewport", "Viewport storage unavailable", it) }.getOrNull()
    private val handler = Handler(Looper.getMainLooper())
    private var navigationButtonsEnabled = preferences?.getBoolean(MODE_KEY, false) ?: false
    private var cached: Snapshot? = null
    private var cachedKey: String? = null
    private var profile: WorkspaceLayoutProfile? = null
    private var pendingRotation: Int? = null
    private var pendingRotationUntil = 0L
    private val notifyChanged = Runnable { onChanged() }
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    init {
        // Opposite landscape rotations have identical width/height and may not
        // produce an orientation configuration change. Observe display changes too.
        runCatching { displayManager?.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || cached == null) return
                val previous = cached
                refresh()
                if (cached != previous) {
                    handler.removeCallbacks(notifyChanged)
                    handler.post(notifyChanged)
                }
            }
        }, handler) }.onFailure {
            NeXtepLog.warn("workspace_viewport", "Display rotation listener unavailable", it)
        }
    }

    fun geometry(side: SidebarSide, refreshMetrics: Boolean = false): WorkspaceGeometry {
        return layout(refreshMetrics).geometry(side)
    }

    fun layout(refreshMetrics: Boolean = false): WorkspaceLayoutProfile {
        if (refreshMetrics || cached == null) refresh()
        return checkNotNull(profile)
    }

    fun projectRotation(rotation: Int) {
        val projected = layout().rotatedTo(rotation)
        pendingRotation = rotation
        pendingRotationUntil = SystemClock.uptimeMillis() + 1_000L
        val g = projected.geometry
        updateSnapshot(Snapshot(g.screenWidth, g.screenHeight, projected.densityDpi / 160f,
            g.navigationInsets, rotation), null, projected)
    }

    fun setNavigationButtonsEnabled(enabled: Boolean) {
        if (navigationButtonsEnabled == enabled) return
        val previous = cached
        navigationButtonsEnabled = enabled
        runCatching {
            preferences?.edit()?.apply {
                putBoolean(MODE_KEY, enabled)
                // Re-enabling is the explicit recalibration after changing system
                // navigation. App switches and config refreshes never resample it.
                if (enabled) preferences?.all?.keys
                    ?.filter { it.startsWith(BUTTONS_PREFIX) }?.forEach(::remove)
            }?.apply()
        }.onFailure { NeXtepLog.warn("workspace_viewport", "Unable to save navigation mode", it) }
        cachedKey = null
        refresh(forceMeasure = true)
        if (previous != cached) {
            handler.removeCallbacks(notifyChanged)
            handler.post(notifyChanged)
        }
    }

    @Suppress("DEPRECATION")
    private fun refresh(forceMeasure: Boolean = false) {
        val metrics = context.resources.displayMetrics
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val observedRotation = display?.rotation ?: 0
        if (pendingRotation == observedRotation || SystemClock.uptimeMillis() >= pendingRotationUntil) {
            pendingRotation = null
        }
        val rotation = pendingRotation ?: observedRotation
        val size = Point(metrics.widthPixels, metrics.heightPixels)
        runCatching { display?.getRealSize(size) }
        val width = size.x
        val height = size.y
        val prefix = if (navigationButtonsEnabled) BUTTONS_PREFIX else FULL_PREFIX
        val previous = cached
        val recorded = profile
        if (!forceMeasure && previous != null && recorded != null &&
            previous.density == metrics.density &&
            minOf(previous.width, previous.height) == minOf(width, height) &&
            maxOf(previous.width, previous.height) == maxOf(width, height)
        ) {
            // Display rotation arrives before some WindowMetrics/Resources updates.
            // Reuse the complete recorded layout instead of sampling an intermediate
            // viewport and resizing the rail/control area for a few frames.
            val projected = recorded.rotatedTo(rotation)
            val g = projected.geometry
            updateSnapshot(Snapshot(g.screenWidth, g.screenHeight, previous.density,
                g.navigationInsets, rotation),
                "$prefix${g.screenWidth}:${g.screenHeight}:${metrics.densityDpi}:$rotation", projected)
            return
        }
        val key = "$prefix$width:$height:" +
            "${metrics.densityDpi}:$rotation"
        if (cachedKey == key && cached != null) return
        val encoded = runCatching { preferences?.getString(key, null) }.getOrNull()
        val savedProfile = WorkspaceLayoutProfile.decode(encoded)?.takeIf {
            it.geometry.screenWidth == width && it.geometry.screenHeight == height &&
                (navigationButtonsEnabled || it.geometry.navigationInsets == WorkspaceNavigationInsets())
        }?.let { it.copy(geometry = it.geometry.copy(displayRotation = rotation)) }
        if (savedProfile != null) {
            val g = savedProfile.geometry
            updateSnapshot(Snapshot(g.screenWidth, g.screenHeight, savedProfile.densityDpi / 160f,
                g.navigationInsets, rotation), key, savedProfile)
            if (encoded != savedProfile.encode()) saveSnapshot(key)
            return
        }
        val stored = runCatching {
            Snapshot.decode(encoded, navigationButtonsEnabled, rotation)
        }.getOrNull()
        if (stored != null) {
            updateSnapshot(stored, key)
            saveSnapshot(key)
            return
        }
        runCatching {
            // No inset-dispatch listener: only an unseen configuration/manual
            // recalibration reaches WindowMetrics. Gesture mode reserves no bar.
            val window = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
            check(width > 1 && height > 1)
            val navigation = if (navigationButtonsEnabled) {
                val inset = window.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                WorkspaceNavigationInsets(inset.left, inset.top, inset.right, inset.bottom)
            } else WorkspaceNavigationInsets()
            Snapshot(width, height, metrics.density, navigation, rotation)
        }.onSuccess { measured ->
            updateSnapshot(measured, key)
            saveSnapshot(key)
            NeXtepLog.info("workspace_viewport", "Recorded buttons=$navigationButtonsEnabled key=$key viewport=$measured")
        }.onFailure {
            // Do not persist a failed measurement as the user's calibration.
            NeXtepLog.warn("workspace_viewport", "Window metrics unavailable; using full-display fallback", it)
            updateSnapshot(Snapshot(width, height,
                metrics.density, WorkspaceNavigationInsets(), rotation), null)
        }
    }

    private fun updateSnapshot(snapshot: Snapshot, key: String?, saved: WorkspaceLayoutProfile? = null) {
        if (cached != snapshot || profile == null) {
            profile = saved ?: WorkspaceLayoutProfile.create(snapshot.width, snapshot.height,
                (snapshot.density * 160).roundToInt(), snapshot.navigationInsets, snapshot.displayRotation)
            SystemServerWorkspaceBridge.publishLayout(context, checkNotNull(profile))
        }
        cached = snapshot
        cachedKey = key
    }

    private fun saveSnapshot(key: String) {
        runCatching {
            preferences?.edit()?.apply {
                val otherKeys = preferences?.all?.keys.orEmpty()
                    .filter { (it.startsWith(BUTTONS_PREFIX) || it.startsWith(FULL_PREFIX)) && it != key }
                otherKeys.take((otherKeys.size - MAX_PROFILES + 1).coerceAtLeast(0)).forEach(::remove)
                putString(key, checkNotNull(profile).encode())
            }?.apply()
        }.onFailure { NeXtepLog.warn("workspace_viewport", "Unable to save viewport profile", it) }
    }

    private companion object {
        const val MODE_KEY = "navigation_buttons_enabled"
        const val BUTTONS_PREFIX = "buttons_v1:"
        const val FULL_PREFIX = "full_v1:"
        const val MAX_PROFILES = 16
    }
}
