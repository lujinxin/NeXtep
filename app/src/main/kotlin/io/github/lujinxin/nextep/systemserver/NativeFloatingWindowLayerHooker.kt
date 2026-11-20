package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.FloatingPipLayer
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Keep panels in their stable system layer and place native floating tasks above them. */
class NativeFloatingWindowLayerHooker : XposedInterface.Hooker {
    private data class TaskLayer(
        val surface: SurfaceControl,
        val originalLayer: Int,
        val originalRelativeTo: SurfaceControl?,
        var anchor: SurfaceControl,
        var layer: Int,
    )

    private val taskLayers = WeakHashMap<Any, TaskLayer>()
    private val methods = ConcurrentHashMap<String, Method>()
    private val fields = ConcurrentHashMap<String, Field>()
    private val setRelativeLayer by lazy {
        SurfaceControl.Transaction::class.java.getDeclaredMethod("setRelativeLayer",
            SurfaceControl::class.java, SurfaceControl::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }
    private var reportedFailure = false
    private val observationRequested = AtomicBoolean(false)
    private val observationHandler by lazy { Handler(Looper.getMainLooper()) }
    private var workspaceObserver: ContentObserver? = null
    @Volatile private var workspaceActive: Boolean? = null

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        // prepareSurfaces runs for unrelated apps too. This is an in-memory read;
        // settings IPC and subscription work run outside the WM traversal/lock.
        runCatching {
            val display = chain.thisObject ?: return@runCatching
            if (call(display, "getDisplayId") != 0) return@runCatching
            val transaction = call(display, "getPendingTransaction") as? SurfaceControl.Transaction ?: return@runCatching
            try {
                if (workspaceActive != false || taskLayers.isNotEmpty()) {
                    adjustDisplayLayers(display, transaction)
                }
            } finally {
                // The host bridge must initialize and restore even with the workspace closed.
                ControlAreaVideoController.prepare(display, transaction, this)
            }
        }.onFailure {
            if (!reportedFailure) {
                reportedFailure = true
                NeXtepLog.warn("native_floating_layer", "Native layer adjustment unavailable", it)
            }
        }
        return result
    }

    internal fun adjustDisplayLayers(
        display: Any,
        transaction: SurfaceControl.Transaction,
        workspaceConfirmedActive: Boolean = false,
    ) {
        startWorkspaceObservation(display)
        if (workspaceActive == false && !workspaceConfirmedActive) {
            restoreTaskLayers(transaction)
            return
        }
        val anchor = findPanelAnchor(display)
        if (anchor == null) {
            restoreTaskLayers(transaction)
            return
        }
        val roots = mutableListOf<Any>()
        call(display, "forAllTasks", Consumer<Any> { task ->
            if (!isFloating(task)) return@Consumer
            // A cold notification launch has a requested-visible task before
            // its first application/splash buffer is ready. Keep a pinned task's
            // order even while Shell temporarily replaces its visible surface.
            if (!isPinned(task) && call(task, "isVisible") != true &&
                call(task, "isVisibleRequested") != true) return@Consumer
            val candidate = call(task, "getRootTask") ?: task
            // Notification animations can temporarily put the floating task
            // under HOME. Never lift the whole fullscreen background with it.
            val root = if (isPinned(task)) candidate.takeIf(::isPinned) ?: task
                else candidate.takeIf(::isFloating) ?: task
            if (ControlAreaVideoController.ownsTask(root)) return@Consumer
            if (roots.none { it === root }) roots.add(root)
        }, false)
        restoreTaskLayers(transaction) { task -> roots.none { it === task } }
        roots.forEachIndexed { index, task ->
            val surface = call(task, "getSurfaceControl") as? SurfaceControl ?: return@forEachIndexed
            if (!surface.isValid) return@forEachIndexed
            val existing = taskLayers[task]?.takeIf { ownsTaskLayer(task, it) }
            val layer = index + 1
            val saved = existing ?: TaskLayer(surface,
                field(task, "mLastLayer") as? Int ?: return@forEachIndexed,
                field(task, "mLastRelativeToLayer") as? SurfaceControl, anchor, layer)
            saved.anchor = anchor
            saved.layer = layer
            taskLayers[task] = saved
            call(task, "assignRelativeLayer", transaction, anchor, layer, true)
        }
    }

    internal fun pinnedMotionLayer(task: Any): FloatingPipLayer? {
        val saved = taskLayers[task] ?: return null
        if (!saved.surface.isValid || !saved.anchor.isValid || !ownsTaskLayer(task, saved)) return null
        return FloatingPipLayer(saved.surface, saved.anchor, saved.layer)
    }

    internal fun raisePinnedAnimation(
        display: Any,
        task: Any,
        transaction: SurfaceControl.Transaction,
        leash: SurfaceControl,
    ) {
        val root = call(task, "getRootTask")?.takeIf(::isPinned) ?: task
        if (!leash.isValid || ControlAreaVideoController.ownsTask(root)) return
        val saved = taskLayers[root]
        val anchor = saved?.anchor?.takeIf { it.isValid } ?: findPanelAnchor(display) ?: return
        setRelativeLayer.invoke(transaction, leash, anchor, saved?.layer ?: 1)
    }

    internal fun finishPinnedAnimation(task: Any, transaction: SurfaceControl.Transaction) {
        val saved = taskLayers[task] ?: return
        if (workspaceActive == false || ControlAreaVideoController.ownsTask(task) || !isPinned(task) ||
            !saved.anchor.isValid || !saved.surface.isValid) return
        // WM finishes by assigning the display's normal layers. Restore the
        // workspace-relative order after those assignments, in the same commit.
        if (call(task, "getSurfaceControl") === saved.surface) {
            setRelativeLayer.invoke(transaction, saved.surface, saved.anchor, saved.layer)
        }
    }

    private fun findPanelAnchor(display: Any): SurfaceControl? {
        var anchor: SurfaceControl? = null
        // Workspace panels keep their own system order. Use the highest visible
        // panel with a valid token surface as the common relative-layer anchor.
        call(display, "forAllWindows", Consumer<Any> { window ->
            if (anchor != null) return@Consumer
            val attrs = field(window, "mAttrs") as? WindowManager.LayoutParams
            if (attrs?.packageName != "com.android.systemui" ||
                attrs.title?.toString() !in PANEL_TITLES || call(window, "isVisible") != true) return@Consumer
            anchor = field(window, "mToken")?.let { call(it, "getSurfaceControl") as? SurfaceControl }
                ?.takeIf { it.isValid }
        }, true)
        return anchor
    }

    private fun ownsTaskLayer(task: Any, saved: TaskLayer): Boolean =
        call(task, "getSurfaceControl") === saved.surface &&
            field(task, "mLastRelativeToLayer") === saved.anchor &&
            field(task, "mLastLayer") == saved.layer

    private fun restoreTaskLayers(
        transaction: SurfaceControl.Transaction,
        shouldRestore: (Any) -> Boolean = { true },
    ) {
        taskLayers.entries.toList().forEach { (task, saved) ->
            if (!shouldRestore(task)) return@forEach
            if (!saved.surface.isValid || !ownsTaskLayer(task, saved)) {
                // WM may already have assigned a newer native layer or replaced the surface.
                taskLayers.remove(task)
                return@forEach
            }
            val relativeTo = saved.originalRelativeTo?.takeIf { it.isValid }
            if (relativeTo != null) {
                call(task, "assignRelativeLayer", transaction, relativeTo, saved.originalLayer, true)
            } else {
                call(task, "assignLayer", transaction, saved.originalLayer)
            }
            // assignLayer can be deferred by TransitionController. Keep ownership
            // until a later native prepareSurfaces pass actually restores the layer.
            if (!ownsTaskLayer(task, saved)) taskLayers.remove(task)
        }
    }

    private fun startWorkspaceObservation(display: Any) {
        if (observationRequested.get()) return
        val service = field(display, "mWmService") ?: field(display, "mAtmService") ?: return
        val context = field(service, "mContext") as? Context ?: return
        if (!observationRequested.compareAndSet(false, true)) return
        observationHandler.post {
            runCatching {
                workspaceObserver = SystemServerWorkspaceBridge.observeWorkspaceActive(
                    context, observationHandler,
                ) { workspaceActive = it }
            }.onFailure {
                // Unknown state keeps the path gated by visible workspace panels.
                workspaceActive = null
                NeXtepLog.warn("native_floating_layer", "Activity observer unavailable; keeping fallback", it)
            }
        }
    }

    private fun isFloating(task: Any): Boolean {
        val mode = call(task, "getWindowingMode") as? Int
        if (mode == 5 || mode == 2) return true // Freeform and picture-in-picture.
        val config = call(task, "getConfiguration") as? Configuration ?: return false
        val extra = field(config, "mOplusExtraConfiguration") ?: return false
        return call(extra, "getScenario") in setOf(1, 2)
    }

    private fun isPinned(task: Any): Boolean = call(task, "getWindowingMode") == 2

    private fun field(target: Any, name: String): Any? {
        val key = "${target.javaClass.name}#$name"
        val member = fields[key] ?: generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true; fields[key] = this } ?: return null
        return member.get(target)
    }

    private fun call(target: Any, name: String, vararg args: Any): Any? {
        val key = "${target.javaClass.name}#$name/${args.joinToString { it.javaClass.name }}"
        val method = methods[key] ?: generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.first { method ->
                method.name == name && method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { i ->
                        val type = method.parameterTypes[i]
                        type.isInstance(args[i]) ||
                            (type == Int::class.javaPrimitiveType && args[i] is Int) ||
                            (type == Boolean::class.javaPrimitiveType && args[i] is Boolean)
                    }
            }.apply { isAccessible = true; methods[key] = this }
        return method.invoke(target, *args)
    }

    private companion object {
        val PANEL_TITLES = setOf("NeXtepTopBar", "NeXtepSidebar", "NeXtepContentPanel")
    }
}
