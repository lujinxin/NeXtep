package io.github.lujinxin.nextep.systemserver

import android.content.res.Configuration
import android.view.SurfaceControl
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/** Keep native floating tasks above our panels, but below higher system window layers. */
class NativeFloatingWindowLayerHooker : XposedInterface.Hooker {
    private val methods = ConcurrentHashMap<String, Method>()
    private val fields = ConcurrentHashMap<String, Field>()
    private var reportedFailure = false

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        runCatching {
            val display = chain.thisObject ?: return@runCatching
            if (call(display, "getDisplayId") != 0) return@runCatching
            val transaction = call(display, "getPendingTransaction") as? SurfaceControl.Transaction ?: return@runCatching
            val panels = mutableListOf<Any>()
            call(display, "forAllWindows", Consumer<Any> { window ->
                val attrs = field(window, "mAttrs") as? WindowManager.LayoutParams
                if (attrs?.packageName == "com.android.systemui" &&
                    attrs.title?.toString() in PANEL_TITLES && call(window, "isVisible") == true) {
                    val token = field(window, "mToken") ?: return@Consumer
                    panels.add(token)
                }
            }, false)
            if (panels.isEmpty()) return@runCatching
            val roots = mutableListOf<Any>()
            call(display, "forAllTasks", Consumer<Any> { task ->
                if (call(task, "isVisible") != true || !isFloating(task)) return@Consumer
                val root = call(task, "getRootTask") ?: return@Consumer
                if (roots.none { it === root }) roots.add(root)
            }, false)
            val root = roots.firstOrNull() ?: return@runCatching
            val anchor = call(root, "getSurfaceControl") as? SurfaceControl ?: return@runCatching
            if (!anchor.isValid) return@runCatching
            // ColorOS rewrites layers during prepareSurfaces. Only reorder our own tokens
            // after that pass; leave native task matrices, animation leashes and order alone.
            panels.forEachIndexed { index, panel ->
                call(panel, "assignRelativeLayer", transaction, anchor, index - panels.size, true)
            }
        }.onFailure {
            if (!reportedFailure) {
                reportedFailure = true
                NeXtepLog.warn("native_floating_layer", "Native layer adjustment unavailable", it)
            }
        }
        return result
    }

    private fun isFloating(task: Any): Boolean {
        val mode = call(task, "getWindowingMode") as? Int
        if (mode == 5 || mode == 2) return true // Freeform and picture-in-picture.
        val config = call(task, "getConfiguration") as? Configuration ?: return false
        val extra = field(config, "mOplusExtraConfiguration") ?: return false
        return call(extra, "getScenario") in setOf(1, 2)
    }

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
