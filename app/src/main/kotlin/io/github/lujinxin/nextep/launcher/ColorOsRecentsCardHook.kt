package io.github.lujinxin.nextep.launcher

import android.app.ActivityManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Rect
import android.view.Display
import android.view.SurfaceControl
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.xposed.HookGuard
import java.lang.reflect.Method
import java.util.WeakHashMap

/** The live task is a separate surface. The snapshot that replaces it inherits HOME's transform. */
internal object ColorOsRecentsCardHook {
    private data class Binding(
        val simulator: String, val build: String, val properties: String, val target: String, val params: String,
    )

    // TaskViewSimulator.onBuildTargetParams in the supported ColorOS Launcher builds.
    private val bindings = listOf(
        Binding("gza", "e", "cla", "aa9", "jeb"),
        Binding("la.s1", "f", "xi.q\$b", "gi.c", "la.v1"),
        Binding(
            "com.android.quickstep.util.TaskViewSimulator", "onBuildTargetParams",
            "com.android.quickstep.util.SurfaceTransaction\$SurfaceProperties",
            "com.android.systemui.shared.system.RemoteAnimationTargetCompat", "com.android.quickstep.util.TransformParams",
        ),
    )

    private data class Frame(val surface: SurfaceControl, val transform: TouchCoordinateMapper.Transform)

    fun install(module: XposedModule, loader: ClassLoader) {
        val build = bindings.firstNotNullOfOrNull { binding ->
            runCatching {
                loader.loadClass(binding.simulator).getDeclaredMethod(
                    binding.build, loader.loadClass(binding.properties), loader.loadClass(binding.target),
                    loader.loadClass(binding.params),
                ).takeIf { it.returnType == Void.TYPE }
            }.getOrNull()
        } ?: return
        val (propertiesType, targetType) = build.parameterTypes
        val setMatrix = propertiesType.declaredMethods.single {
            it.parameterTypes.contentEquals(arrayOf(Matrix::class.java)) && it.returnType == propertiesType
        }
        val named = build.name == "onBuildTargetParams"
        val surface = propertiesType.declaredFields.single { it.type == SurfaceControl::class.java }
            .apply { isAccessible = true }
        val leash = targetType.getField(if (named) "leash" else "d")
        val mode = targetType.getField(if (named) "mode" else "c")
        val activityType = targetType.getField(if (named) "activityType" else "s")
        val taskInfo = targetType.declaredFields.single { it.type == ActivityManager.RunningTaskInfo::class.java }
            .apply { isAccessible = true }
        val eligibleTargets = WeakHashMap<Any, Boolean>()
        fun isMainApp(target: Any): Boolean = synchronized(eligibleTargets) {
            eligibleTargets.getOrPut(target) {
                runCatching {
                    if (activityType.getInt(target) != 1) return@runCatching false
                    val info = taskInfo.get(target) as? ActivityManager.RunningTaskInfo ?: return@runCatching false
                    val state = TaskInfoCompat.readWindowState(info) ?: return@runCatching false
                    state.displayId == Display.DEFAULT_DISPLAY && state.windowingMode == 1 && !state.vendorWindowed
                }.getOrDefault(false)
            }
        }
        fun isClosingMainApp(target: Any) = mode.getInt(target) == 1 && isMainApp(target)

        HookGuard.run("launcher_recents_card_open") {
            val helper = loader.loadClass("com.android.launcher3.anim.OplusLauncherAppTransitionHelper")
            val launch = helper.declaredMethods.single {
                it.name == "startTaskViewLaunchWindowAnim" && it.parameterTypes.getOrNull(2) == Rect::class.java
            }
            val apps = launch.parameterTypes[1].getDeclaredField(if (named) "unfilteredApps" else "c")
                .apply { isAccessible = true }
            check(apps.type.componentType == targetType)
            val spring = launch.returnType.declaredMethods.single {
                it.name == "connectFromPriorAnimIfNeeded\$default" && it.parameterTypes.getOrNull(2) == RectF::class.java
            }
            val destination = ThreadLocal<Pair<Rect, TouchCoordinateMapper.Transform>>()
            module.deoptimize(launch)
            module.hook(spring).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val current = destination.get() ?: return chain.proceed()
                    val end = chain.getArg(2) as? RectF ?: return chain.proceed()
                    // Quickstep measured the thumbnail on screen. Only the fullscreen endpoint
                    // needs fitting. The same local RectF also feeds the fresh-spring constructor.
                    if (end == RectF(current.first)) {
                        val t = current.second
                        end.set(end.left * t.scaleX + t.translationX, end.top * t.scaleY + t.translationY,
                            end.right * t.scaleX + t.translationX, end.bottom * t.scaleY + t.translationY)
                    }
                    return chain.proceed()
                }
            })
            module.hook(launch).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val transform = LauncherTransformController.currentAnimationTransform() ?: return chain.proceed()
                    val root = (chain.getArg(0) as? View)?.rootView ?: return chain.proceed()
                    val bounds = chain.getArg(2) as? Rect ?: return chain.proceed()
                    if (!LauncherTransformController.canUseWorkspaceAnimations(root) ||
                        bounds != Rect(0, 0, root.width, root.height)
                    ) return chain.proceed()
                    val targets = apps.get(chain.getArg(1)) as? Array<*> ?: return chain.proceed()
                    if (targets.none { it != null && mode.getInt(it) == 0 && isMainApp(it) }) return chain.proceed()
                    val previous = destination.get()
                    destination.set(Rect(bounds) to transform)
                    return try { chain.proceed() } finally {
                        if (previous == null) destination.remove() else destination.set(previous)
                    }
                }
            })
        }

        val frame = ThreadLocal<Frame>()
        // Keep inlined writes visible to the hook, including the OEM's optional second matrix write.
        module.deoptimize(build)
        module.hook(setMatrix).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val current = frame.get() ?: return chain.proceed()
                if (surface.get(chain.thisObject) !== current.surface) return chain.proceed()
                val native = chain.getArg(0) as? Matrix ?: return chain.proceed()
                // Convert the output coordinates once. Quickstep reuses its own matrix for gesture math.
                val display = Matrix(native).apply {
                    postScale(current.transform.scaleX, current.transform.scaleY)
                    postTranslate(current.transform.translationX, current.transform.translationY)
                }
                return chain.proceed(arrayOf<Any?>(display))
            }
        })
        fun scope(method: Method, targetSurface: (XposedInterface.Chain) -> SurfaceControl?) {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val transform = LauncherTransformController.currentAnimationTransform() ?: return chain.proceed()
                    val target = runCatching { targetSurface(chain) }.getOrNull() ?: return chain.proceed()
                    val previous = frame.get()
                    frame.set(Frame(target, transform))
                    return try {
                        chain.proceed()
                    } finally {
                        if (previous == null) frame.remove() else frame.set(previous)
                    }
                }
            })
        }
        scope(build) { chain ->
            val target = chain.getArg(1) ?: return@scope null
            if (isClosingMainApp(target)) surface.get(chain.getArg(0)) as? SurfaceControl else null
        }

        // On release the OEM spring writes directly to the live app's surface, bypassing the simulator.
        HookGuard.run("launcher_recents_card_spring") {
            val callback = loader.loadClass(
                "com.android.launcher3.anim.OplusLauncherAppTransitionHelper\$createToRecentsAnimRecord\$3",
            )
            val params = callback.getDeclaredField("\$transformParams").apply { isAccessible = true }
            check(params.type == build.parameterTypes[2])
            val targets = params.type.getDeclaredField(if (named) "mTargetSet" else "f").apply { isAccessible = true }
            val apps = targets.type.getDeclaredField(if (named) "unfilteredApps" else "c").apply { isAccessible = true }
            check(apps.type.componentType == targetType)
            val float = Float::class.javaPrimitiveType
            val update = callback.getDeclaredMethod("onUpdate", RectF::class.java, float, float, float, float, float)
            scope(update) { chain ->
                val set = targets.get(params.get(chain.thisObject)) ?: return@scope null
                val app = (apps.get(set) as? Array<*>)?.singleOrNull { it != null && isClosingMainApp(it) }
                    ?: return@scope null
                leash.get(app) as? SurfaceControl
            }
        }
    }
}
