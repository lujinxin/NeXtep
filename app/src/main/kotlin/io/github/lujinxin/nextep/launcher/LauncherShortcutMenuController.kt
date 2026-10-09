package io.github.lujinxin.nextep.launcher

import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.util.WeakHashMap

/** A desktop shortcut menu has its own native window and its own input coordinates. */
internal object LauncherShortcutMenuController {
    private val originalTypes = WeakHashMap<PopupWindow, Int>()

    fun install(module: XposedModule) {
        val show = PopupWindow::class.java.getDeclaredMethod(
            "invokePopup", WindowManager.LayoutParams::class.java,
        ).apply { isAccessible = true }
        module.hook(show).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val popup = chain.thisObject as? PopupWindow
                val params = chain.getArg(0) as? WindowManager.LayoutParams
                if (popup != null && params != null && isWorkspaceMenu(popup)) {
                    originalTypes.putIfAbsent(popup, popup.windowLayoutType)
                    // Raising a child View cannot escape HOME's application window layer.
                    // Use the native window layer for BOTH drawing and InputDispatcher order.
                    // Launcher already owns SYSTEM_ALERT_WINDOW on the supported ColorOS ROM.
                    popup.windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    fitWindow(params)
                }
                return try {
                    chain.proceed()
                } catch (error: Throwable) {
                    popup?.let(::restoreType)
                    throw error
                }
            }
        })
        val dismiss = PopupWindow::class.java.getDeclaredMethod("dismiss")
        module.hook(dismiss).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val popup = chain.thisObject as? PopupWindow
                if (popup != null && !popup.isShowing) restoreType(popup)
                return result
            }
        })
        NeXtepLog.info("launcher_shortcut_menu", "Installed native shortcut menu window ordering")
    }

    fun beforeLayout(params: WindowManager.LayoutParams) {
        // PopupWindow.update() must keep the type selected when its window was added.
        if (params.title?.toString() == WINDOW_TITLE) fitWindow(params)
    }

    fun dismissMenus() {
        originalTypes.keys.toList().forEach { popup ->
            if (popup.isShowing) {
                runCatching { popup.dismiss() }.onFailure {
                    NeXtepLog.warn("launcher_shortcut_menu", "Could not dismiss workspace shortcut menu", it)
                }
            } else restoreType(popup)
        }
    }

    private fun isWorkspaceMenu(popup: PopupWindow): Boolean {
        if (!LauncherTransformController.isActive()) return false
        val content = popup.contentView ?: return false
        if (!LauncherPackageResolver.isCurrentHome(content.context)) return false
        if (!Settings.canDrawOverlays(content.context)) return false
        // Do not promote ordinary app dialogs, folders or other launcher's popups.
        return hasMenuRoot(content)
    }

    private fun hasMenuRoot(view: View): Boolean {
        if (generateSequence(view.javaClass as Class<*>?) { it.superclass }
                .any { it.name == "com.coui.appcompat.poplist.COUIPopupMenuRootView" }) return true
        return view is ViewGroup && (0 until view.childCount).any { hasMenuRoot(view.getChildAt(it)) }
    }

    private fun fitWindow(params: WindowManager.LayoutParams) {
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // An overlay must not remain attached to HOME's Activity/child-window token.
        params.token = null
        params.title = WINDOW_TITLE
        // COUI lays out its full-screen menu root in physical display coordinates.
        // A detached overlay otherwise gains the status-bar inset (120px on this ROM),
        // shifting every row and pushing the bottom of a tall menu off screen.
        params.setFitInsetsTypes(0)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // Keep COUI's physical menu geometry and native dispatch together. HOME's decor
        // matrix/touch mapper must not be applied a second time to this separate window.
    }

    private fun restoreType(popup: PopupWindow) {
        originalTypes.remove(popup)?.let { popup.windowLayoutType = it }
    }

    private const val WINDOW_TITLE = "NeXtepLauncherMenu"
}
