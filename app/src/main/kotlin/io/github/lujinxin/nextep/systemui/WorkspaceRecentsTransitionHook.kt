package io.github.lujinxin.nextep.systemui

import android.app.ActivityManager
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.util.ArrayMap
import android.view.Display
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.lang.reflect.Proxy

internal object WorkspaceRecentsTransitionHook {
    private class Card(
        val id: Int, val surface: SurfaceControl, val info: Any,
        val finish: SurfaceControl.Transaction, val opening: Boolean,
    )

    fun install(module: XposedModule, loader: ClassLoader) {
        val shell = loader.loadClass("com.android.wm.shell.transition.Transitions")
        val observerType = loader.loadClass("com.android.wm.shell.transition.Transitions\$TransitionObserver")
        val infoType = loader.loadClass("android.window.TransitionInfo")
        val changeType = loader.loadClass("android.window.TransitionInfo\$Change")
        val transaction = SurfaceControl.Transaction::class.java
        val tokenType = IBinder::class.java
        val ready = shell.getDeclaredMethod("onTransitionReadyInner", tokenType, infoType, transaction, transaction)
        val register = shell.getDeclaredMethod("registerObserver", observerType)
        val recentsType = shell.getField("TRANSIT_START_RECENTS_TRANSITION").getInt(null)
        val getType = infoType.getMethod("getType")
        val getChanges = infoType.getMethod("getChanges")
        val taskInfo = changeType.getMethod("getTaskInfo")
        val mode = changeType.getMethod("getMode")
        val surface = changeType.getMethod("getLeash")
        val startRotation = changeType.getMethod("getStartRotation")
        val endRotation = changeType.getMethod("getEndRotation")
        val ext = infoType.getField("mExt")
        val extendedInfo = ext.type.getMethod("getExtendedInfo")
        val fromRecents = loader.loadClass("android.window.OplusTransitionExtendedInfo").getMethod("getTaskIdStartFromRecents")
        val sameSurface = SurfaceControl::class.java.getMethod("isSameSurface", SurfaceControl::class.java)
        val manager = loader.loadClass("com.android.wm.shell.transition.AdaptiveSmoothShellAnimManager")
        val recentStart = manager.getDeclaredMethod("hookRecentStart", Bundle::class.java, infoType,
            ArrayMap::class.java, transaction, transaction)

        // These callbacks run on Shell's transition executor. Merged transitions finish with their owner.
        val cards = mutableMapOf<IBinder, MutableList<Card>>()
        fun observeReady(token: IBinder, info: Any, start: SurfaceControl.Transaction, finish: SurfaceControl.Transaction) {
            val type = getType.invoke(info)
            val opening = type == 3 || type == 1
            if (!opening && type != recentsType) return
            val openingId = if (opening) {
                val data = extendedInfo.invoke(ext.get(info)) ?: return
                (fromRecents.invoke(data) as Int).takeIf { it > 0 } ?: return
            } else null
            val geometry = SystemUiRuntime.cardAnimationGeometry() ?: return
            val changes = getChanges.invoke(info) as List<*>
            for (change in changes.filterNotNull()) {
                val task = taskInfo.invoke(change) as? ActivityManager.RunningTaskInfo ?: continue
                val eligible = if (opening) task.taskId == openingId && mode.invoke(change) in setOf(1, 3)
                    else TaskSurfaceCompat.ownsMainTask(task.taskId) && mode.invoke(change) in setOf(2, 4)
                if (!eligible || startRotation.invoke(change) != endRotation.invoke(change)) continue
                val state = TaskInfoCompat.readWindowState(task) ?: continue
                if (state.displayId != Display.DEFAULT_DISPLAY || state.windowingMode != 1 || state.vendorWindowed ||
                    state.bounds != Rect(0, 0, geometry.screenWidth, geometry.screenHeight)) continue
                val leash = surface.invoke(change) as SurfaceControl
                if (!leash.isValid) continue
                val card = Card(task.taskId, leash, info, finish, opening)
                cards.getOrPut(token) { mutableListOf() }.add(card)
                if (opening) TaskSurfaceCompat.startCardAnimation(card.id, card, start, leash, null, geometry)
            }
        }
        val observer = Proxy.newProxyInstance(loader, arrayOf(observerType)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "NeXtep Recents observer"
                else -> {
                    runCatching {
                        val token = args?.firstOrNull() as? IBinder ?: return@runCatching
                        when (method.name) {
                            "onTransitionReady" -> observeReady(token, args[1], args[2] as SurfaceControl.Transaction,
                                args[3] as SurfaceControl.Transaction)
                            "onTransitionMerged" -> cards.remove(token)?.let {
                                cards.getOrPut(args[1] as IBinder) { mutableListOf() }.addAll(it)
                            }
                            "onTransitionFinished" -> cards.remove(token)?.let { finished ->
                                val geometry = runCatching { SystemUiRuntime.cardAnimationGeometry() }.getOrNull()
                                finished.forEach { card ->
                                    runCatching {
                                        TaskSurfaceCompat.finishCardAnimation(card.id, card, card.finish, card.surface,
                                            geometry, card.opening && args[1] != true)
                                    }.onFailure { NeXtepLog.warn("workspace_recents_handoff", "Unable to settle task ${card.id}", it) }
                                }
                                SystemUiRuntime.onTasksChanged()
                            }
                        }
                    }.onFailure { NeXtepLog.warn("workspace_recents_handoff", "Card transition callback failed", it) }
                    null
                }
            }
        }
        module.hook(recentStart).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    val info = chain.getArg(1)
                    val map = chain.getArg(2) as? Map<*, *> ?: return@runCatching
                    val start = chain.getArg(3) as SurfaceControl.Transaction
                    val geometry = SystemUiRuntime.cardAnimationGeometry() ?: return@runCatching
                    for (group in cards.values) for (card in group) {
                        if (card.opening || card.info !== info) continue
                        val parent = map[card.surface] as? SurfaceControl ?: continue
                        if (!parent.isValid) continue
                        val animationSurface = if (sameSurface.invoke(parent, card.surface) == true) card.surface else parent
                        TaskSurfaceCompat.startCardAnimation(card.id, card, start, card.surface, animationSurface, geometry)
                    }
                }.onFailure { NeXtepLog.warn("workspace_recents_handoff", "Unable to fit first card frame", it) }
                return result
            }
        })
        var registered: Any? = null
        module.hook(ready).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (registered !== chain.thisObject) {
                    runCatching {
                        register.invoke(chain.thisObject, observer)
                        registered = chain.thisObject
                    }.onFailure { NeXtepLog.warn("workspace_recents_handoff", "Unable to observe transitions", it) }
                }
                return chain.proceed()
            }
        })
    }
}
