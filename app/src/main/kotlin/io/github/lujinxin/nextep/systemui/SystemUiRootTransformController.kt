package io.github.lujinxin.nextep.systemui

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.lujinxin.nextep.launcher.TouchCoordinateMapper
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import java.util.WeakHashMap
import kotlin.math.roundToInt

object SystemUiRootTransformController {
    private data class OriginalTransform(
        val pivotX: Float,
        val pivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
        val clipBounds: Rect?,
    )

    private val roots = WeakHashMap<View, OriginalTransform>()
    private val transformedRoots = WeakHashMap<View, Boolean>()
    private var active = false
    private var geometry: WorkspaceGeometry? = null

    fun observeRoot(view: View) {
        if (!isNotificationShadeRoot(view)) return
        roots.getOrPut(view) {
            NeXtepLog.info(
                "systemui_root_transform",
                "Observed shade root title=${windowTitle(view)} class=${view.javaClass.name}",
            )
            OriginalTransform(
                pivotX = view.pivotX,
                pivotY = view.pivotY,
                scaleX = view.scaleX,
                scaleY = view.scaleY,
                translationX = view.translationX,
                translationY = view.translationY,
                clipBounds = view.clipBounds?.let(::Rect),
            )
        }
    }

    fun setActive(requestedActive: Boolean) {
        active = requestedActive
        if (active) {
            // A top-corner workspace gesture can be confirmed while the shade is already open.
            // In that path the root was observed by the same touch dispatch, but there is no
            // subsequent downward shade gesture to start its transform. Apply all live observed
            // shade roots as part of activation so an already-rendered control centre moves into
            // the content viewport immediately.
            beginObservedShadeTransform()
        } else {
            roots.keys.toList().forEach(::restore)
            transformedRoots.clear()
        }
        NeXtepLog.info("systemui_root_transform", "active=$active roots=${roots.size}")
    }

    fun setGeometry(value: WorkspaceGeometry) {
        geometry = value
        if (active) {
            transformedRoots.keys.toList().forEach { view ->
                if (view.parent != null) apply(view)
            }
        }
        NeXtepLog.info("systemui_root_transform", "geometry=$value")
    }

    fun mapToContent(view: View, event: MotionEvent?): TouchCoordinateMapper.Transform? {
        observeRoot(view)
        if (!active || transformedRoots[view] != true) return null
        val transform = transformFor()
        return transform.takeIf { TouchCoordinateMapper.toContent(event, it) }
    }

    fun mapToScreen(event: MotionEvent?, transform: TouchCoordinateMapper.Transform?) {
        TouchCoordinateMapper.toScreen(event, transform)
    }

    fun mapWindowLocationToContent(view: View, location: IntArray) {
        val root = view.rootView
        if (!active || transformedRoots[root] != true || location.size < 2 ||
            root.width <= 1 || root.height <= 1
        ) return
        val transform = transformFor()
        location[0] = ((location[0] - transform.translationX) / transform.scaleX).roundToInt()
        location[1] = ((location[1] - transform.translationY) / transform.scaleY).roundToInt()
    }

    fun beginShadeTransform(view: View): Boolean {
        observeRoot(view)
        if (!active || !roots.containsKey(view)) return false
        apply(view)
        transformedRoots[view] = true
        monitorUntilCollapsed(view)
        return true
    }

    fun beginObservedShadeTransform(): Boolean {
        if (!active) return false
        var transformed = false
        roots.keys.toList().forEach { view ->
            if (view.parent != null && isNotificationShadeRoot(view)) {
                apply(view)
                transformedRoots[view] = true
                monitorUntilCollapsed(view)
                transformed = true
            }
        }
        return transformed
    }

    fun isNotificationShadeRoot(view: View): Boolean = isFullScreenRoot(view) &&
        (windowTitle(view).contains("NotificationShade", ignoreCase = true) ||
            view.javaClass.name.contains("NotificationShadeWindowView"))

    private fun apply(view: View) {
        val transform = transformFor()
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = transform.scaleX
        view.scaleY = transform.scaleY
        view.translationX = transform.translationX
        view.translationY = transform.translationY
        geometry?.let {
            view.clipBounds = Rect(it.availableLeft, it.availableTop, it.availableRight, it.availableBottom)
            SystemUiShadeInputController.apply(view, it)
        }
    }

    private fun restore(view: View) {
        SystemUiShadeInputController.restore(view)
        val original = roots[view] ?: return
        view.pivotX = original.pivotX
        view.pivotY = original.pivotY
        view.scaleX = original.scaleX
        view.scaleY = original.scaleY
        view.translationX = original.translationX
        view.translationY = original.translationY
        view.clipBounds = original.clipBounds
        transformedRoots.remove(view)
    }

    private fun monitorUntilCollapsed(view: View) {
        view.postDelayed({
            if (!active || view.parent == null || view.windowVisibility != View.VISIBLE ||
                !view.isShown
            ) {
                restore(view)
                return@postDelayed
            }
            if (transformedRoots[view] == true) monitorUntilCollapsed(view)
        }, SHADE_VISIBILITY_POLL_MS)
    }

    private fun transformFor(): TouchCoordinateMapper.Transform {
        val currentGeometry = checkNotNull(geometry) { "Workspace geometry must precede shade activation" }
        return TouchCoordinateMapper.Transform(
            scaleX = currentGeometry.contentScale,
            scaleY = currentGeometry.contentScale,
            translationX = currentGeometry.contentTranslationX,
            translationY = currentGeometry.contentTranslationY,
        )
    }

    private fun isFullScreenRoot(view: View): Boolean {
        val metrics = view.resources.displayMetrics
        return view.rootView === view &&
            view.width >= metrics.widthPixels - 2 &&
            view.height >= metrics.heightPixels - 2
    }

    private fun windowTitle(view: View): String =
        ((view.layoutParams as? WindowManager.LayoutParams)?.title ?: "").toString()

    private const val SHADE_VISIBILITY_POLL_MS = 120L
}
