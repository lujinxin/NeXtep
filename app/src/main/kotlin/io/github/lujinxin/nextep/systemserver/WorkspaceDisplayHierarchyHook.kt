package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge

/** Keep cross-display animation roots below the workspace's system windows. */
internal object WorkspaceDisplayHierarchyHook {
    fun install(module: XposedModule, loader: ClassLoader) {
        val method = loader.loadClass("com.android.server.wm.Transition").declaredMethods.single {
            it.name == "calculateTransitionRoots" && it.parameterCount == 3
        }.apply { isAccessible = true }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    val targets = chain.args[1] as? List<*> ?: return@runCatching
                    val movedTask = targets.firstNotNullOfOrNull { change ->
                        change ?: return@firstNotNullOfOrNull null
                        val container = field(change, "mContainer") ?: return@firstNotNullOfOrNull null
                        val display = call(container, "getDisplayContent") ?: return@firstNotNullOfOrNull null
                        val from = field(change, "mDisplayId") as? Int ?: return@firstNotNullOfOrNull null
                        val to = call(display, "getDisplayId") as? Int ?: return@firstNotNullOfOrNull null
                        if (from == to || (from != 0 && to != 0)) return@firstNotNullOfOrNull null
                        val service = field(container, "mAtmService") ?: return@firstNotNullOfOrNull null
                        val root = field(service, "mRootWindowContainer") ?: return@firstNotNullOfOrNull null
                        val slot = call(root, "getDisplayContent", if (from == 0) to else from)
                            ?: return@firstNotNullOfOrNull null
                        val info = call(slot, "getDisplayInfo") ?: return@firstNotNullOfOrNull null
                        if ((field(info, "name") as? String)?.startsWith("NeXtep-slot-") != true) {
                            return@firstNotNullOfOrNull null
                        }
                        service to root
                    } ?: return@runCatching
                    val context = field(movedTask.first, "mContext") as? Context ?: return@runCatching
                    if (!SystemServerWorkspaceBridge.isWorkspaceActive(context)) return@runCatching
                    val physical = call(movedTask.second, "getDefaultDisplay") ?: return@runCatching
                    // A real display rotation/resize must retain its native hierarchy.
                    if (targets.any { change ->
                        change != null && field(change, "mContainer") === physical &&
                            (field(change, "mRotation") != call(physical, "getRotation") ||
                                field(change, "mAbsoluteBounds") != call(physical, "getBounds"))
                    }) return@runCatching
                    val info = chain.args[0] ?: return@runCatching
                    val index = call(info, "findRootIndex", 0) as? Int ?: return@runCatching
                    if (index < 0) return@runCatching
                    val leash = call(call(info, "getRoot", index) ?: return@runCatching, "getLeash")
                        as? SurfaceControl ?: return@runCatching
                    if (!leash.isValid || !leash.toString().contains("Transition Root: Display 0")) return@runCatching
                    val taskArea = call(physical, "getDefaultTaskDisplayArea") ?: return@runCatching
                    val parent = call(taskArea, "getParent") ?: return@runCatching
                    val parentLeash = call(parent, "getSurfaceControl") as? SurfaceControl ?: return@runCatching
                    if (!parentLeash.isValid) return@runCatching
                    val transaction = chain.args[2] as? SurfaceControl.Transaction ?: return@runCatching
                    // ColorOS creates this source-display root beside OneHanded at z=0.
                    // Its wallpaper then covers every system window for one frame, before
                    // Shell restores the parents. Keep it in the task area's own parent,
                    // where application-overlay siblings remain above it, from creation.
                    transaction.reparent(leash, parentLeash).setLayer(leash, 0)
                    NeXtepLog.info("workspace_display_hierarchy", "Kept slot exchange root below workspace windows")
                }.onFailure { NeXtepLog.warn("workspace_display_hierarchy", "Exchange root fitting unavailable", it) }
                return result
            }
        })
        NeXtepLog.info("workspace_display_hierarchy", "Installed scoped cross-display root hierarchy")
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String, vararg args: Any): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == args.size }
            ?.apply { isAccessible = true }?.invoke(target, *args)
}
