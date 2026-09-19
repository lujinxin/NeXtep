package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.Display
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface

/** Change only the landscape fallback; sensor and explicit rotation choices remain system-owned. */
class WorkspaceLandscapeRotationHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val original = chain.proceed()
        val rotation = original as? Int ?: return original
        return runCatching {
            val target = chain.thisObject ?: return@runCatching original
            val display = field(target, "mDisplayContent") ?: return@runCatching original
            if (field(display, "mDisplayId") != Display.DEFAULT_DISPLAY) return@runCatching original
            val context = field(target, "mContext") as? Context ?: return@runCatching original
            if (!SystemServerWorkspaceBridge.isWorkspaceActive(context)) return@runCatching original
            val orientation = chain.getArg(0) as? Int ?: return@runCatching original
            if (orientation !in setOf(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
                )) return@runCatching original
            val landscape = field(target, "mLandscapeRotation") as? Int ?: return@runCatching original
            val seascape = field(target, "mSeascapeRotation") as? Int ?: return@runCatching original
            if (rotation != landscape) return@runCatching original
            val lastRotation = chain.getArg(1) as? Int ?: return@runCatching original
            val flexibleLandscape = orientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            if (flexibleLandscape && (lastRotation == landscape || lastRotation == seascape)) {
                return@runCatching original
            }
            // Be conservative on vendor ROMs: a missing policy field means no override.
            val sensorRotation = field(target, "mLastSensorRotation") as? Int
                ?: return@runCatching original
            if (flexibleLandscape && (sensorRotation == landscape || sensorRotation == seascape)) {
                return@runCatching original
            }
            val userRotation = field(target, "mUserRotation") as? Int ?: return@runCatching original
            if (userRotation == landscape || userRotation == seascape) return@runCatching original
            val portrait = field(target, "mPortraitRotation") as? Int ?: return@runCatching original
            // In display coordinates the portrait top edge is on the right after a
            // counter-clockwise quarter turn. Use the device mapping, not a hardcoded
            // SCREEN_ORIENTATION_LANDSCAPE/REVERSE_LANDSCAPE assumption.
            val preferred = (portrait + 3) % 4
            if (preferred != landscape && preferred != seascape) return@runCatching original
            if (rotation != preferred) {
                NeXtepLog.info("workspace_landscape_rotation", "Main display rotation=$rotation -> $preferred")
            }
            preferred
        }.onFailure {
            NeXtepLog.warn("workspace_landscape_rotation", "Rotation policy unavailable; keeping system result", it)
        }.getOrDefault(original)
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true }?.get(target)
}
