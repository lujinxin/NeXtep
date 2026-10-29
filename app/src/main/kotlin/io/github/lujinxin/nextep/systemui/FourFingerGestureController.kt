package io.github.lujinxin.nextep.systemui

import android.app.GameManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/** Applies the current SystemUI policy before accepting a system-observed gesture. */
internal class FourFingerGestureController(
    private val context: Context,
    private val isWorkspaceActive: () -> Boolean,
    private val visibleSlotTaskIds: () -> Set<Int>,
    private val canChangeWorkspace: () -> Boolean,
    private val requestWorkspace: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val taskRepository = TaskRepository(context)
    private var config = WorkspaceTopConfig(fourFingerGestureEnabled = false)
    private val gameManager by lazy { runCatching { context.getSystemService(GameManager::class.java) }.getOrNull() }
    private val gameModeMethod by lazy {
        runCatching { gameManager?.javaClass?.getMethod("getGameMode", String::class.java) }.getOrNull()
    }

    fun updateConfig(value: WorkspaceTopConfig) {
        config = value
    }

    fun handleRequest(intent: Intent): Boolean {
        if (!intent.hasExtra(TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE)) return false
        val active = intent.getBooleanExtra(TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE, false)
        val eventTime = intent.getLongExtra(TriggerBroadcastContract.EXTRA_GESTURE_EVENT_TIME, -1L)
        fun allowed(): Boolean =
            SystemClock.uptimeMillis() - eventTime in 0L..400L &&
                config.fourFingerGestureEnabled && canChangeWorkspace() && screenReady() &&
                isWorkspaceActive() != active && policyAllowsGesture()
        if (!allowed()) return false
        // Return the ordered acknowledgement first so system_server can cancel the
        // recognized contact stream. Recheck policy before changing the workspace.
        handler.post { if (allowed()) requestWorkspace(active) }
        return true
    }

    private fun policyAllowsGesture(): Boolean {
        val tasks = taskRepository.runningTasks()
        val foreground = tasks.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY } ?: return false
        val slots = if (isWorkspaceActive()) visibleSlotTaskIds() else emptySet()
        val visible = (listOf(foreground) + tasks.filter { it.taskId in slots }).distinctBy { it.taskId }
        return visible.none { task ->
            task.component.packageName in config.fourFingerGestureBlacklist ||
                (config.fourFingerGestureBlockGames && isGame(task.component.packageName))
        }
    }

    @Suppress("DEPRECATION")
    private fun isGame(packageName: String): Boolean {
        val application = runCatching {
            context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        }.getOrNull()
        if (application?.category == ApplicationInfo.CATEGORY_GAME ||
            (application != null && application.flags and ApplicationInfo.FLAG_IS_GAME != 0)
        ) return true
        // A system-selected game mode also covers games whose manifest has no game category.
        // This package-scoped overload is a system API; never query SystemUI's own game mode.
        val mode = runCatching { gameModeMethod?.invoke(gameManager, packageName) as? Int }.getOrNull()
        return mode != null && mode != GameManager.GAME_MODE_UNSUPPORTED
    }

    private fun screenReady(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
    }.getOrDefault(false)

}
