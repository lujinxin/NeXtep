package io.github.lujinxin.nextep.systemui

import android.graphics.Rect
import android.graphics.Region
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** Match the shade's input window to its transformed viewport, before native dispatch. */
internal object SystemUiShadeInputController {
    private data class InputState(
        val originallyNotModal: Boolean,
        val observer: ViewTreeObserver,
        val listener: Any,
        val bounds: Rect,
    )

    private val states = WeakHashMap<View, InputState>()
    private val listenerClass by lazy {
        Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
    }

    fun apply(view: View, geometry: WorkspaceGeometry) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching {
            val bounds = Rect(geometry.contentLeft, geometry.contentTop,
                geometry.contentRight, geometry.contentBottom)
            val state = states[view] ?: run {
                val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) {
                    proxy, method, args ->
                    when (method.name) {
                        "onComputeInternalInsets" -> {
                            val info = args?.firstOrNull() ?: return@newProxyInstance null
                            info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                                .invoke(info, 3) // InternalInsetsInfo.TOUCHABLE_INSETS_REGION
                            (info.javaClass.getField("touchableRegion").get(info) as Region).set(bounds)
                            null
                        }
                        "equals" -> proxy === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "NeXtepShadeInsets"
                        else -> null
                    }
                }
                val observer = view.viewTreeObserver
                observer.javaClass.getMethod("addOnComputeInternalInsetsListener", listenerClass)
                    .invoke(observer, listener)
                InputState(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0,
                    observer, listener, bounds).also { states[view] = it }
            }
            state.bounds.set(bounds)
            // A modal window expands its region to the entire display in WindowState.
            // Insets alone cannot make transparent space pass touches to sibling windows.
            if (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL == 0) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                view.context.getSystemService(WindowManager::class.java).updateViewLayout(view, params)
            }
            view.requestLayout()
        }.onFailure { NeXtepLog.warn("shade_input", "Could not fit shade input viewport", it) }
    }

    /** OEM shade state changes repeatedly replace flags while the panel is expanded. */
    fun beforeLayout(view: View, params: WindowManager.LayoutParams) {
        if (states.containsKey(view)) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    fun restore(view: View) {
        val state = states.remove(view) ?: return
        runCatching {
            val observer = if (state.observer.isAlive) state.observer else view.viewTreeObserver
            if (observer.isAlive) observer.javaClass
                .getMethod("removeOnComputeInternalInsetsListener", listenerClass)
                .invoke(observer, state.listener)
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@runCatching
            params.flags = if (state.originallyNotModal) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            if (view.isAttachedToWindow) {
                view.context.getSystemService(WindowManager::class.java).updateViewLayout(view, params)
                view.requestLayout()
            }
        }.onFailure { NeXtepLog.warn("shade_input", "Could not restore native shade input", it) }
    }
}
