package com.nextep.shell.systemui

import android.view.View
import com.nextep.shell.logging.NeXtepLog
import java.util.Collections
import java.util.WeakHashMap

object SystemUiStatusBarGestureInstaller {
    private val installedRoots = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun install(anchor: View) {
        runCatching {
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null)
            val roots = globalClass.getMethod("getRootViews").invoke(global) as? Collection<*>
                ?: return
            val screenWidth = anchor.resources.displayMetrics.widthPixels
            val maximumHeight = (64f * anchor.resources.displayMetrics.density).toInt()
            val root = roots.filterIsInstance<View>().firstOrNull { candidate ->
                candidate !== anchor.rootView &&
                    candidate.width >= screenWidth - 2 &&
                    candidate.height in 1..maximumHeight
            } ?: error("SystemUI status-bar root is unavailable")

            synchronized(installedRoots) {
                if (!installedRoots.add(root)) return
            }
            val original = currentTouchListener(root)
            val coordinator = SystemUiGestureCoordinator(root.context)
            root.setOnTouchListener { view, event ->
                if (coordinator.observe(event, screenWidth) == TopCornerGestureDetector.Result.CONFIRMED) {
                    coordinator.dispatchConfirmedToggle()
                    NeXtepLog.info(
                        "top_corner_gesture",
                        "Confirmed by status-bar root observer without consuming the event",
                    )
                }
                original?.onTouch(view, event) == true
            }
            NeXtepLog.info(
                "top_corner_gesture",
                "Installed status-bar observer on ${root.javaClass.name} ${root.width}x${root.height}",
            )
        }.onFailure { error ->
            NeXtepLog.warn("top_corner_gesture", "Unable to install status-bar observer", error)
        }
    }

    private fun currentTouchListener(view: View): View.OnTouchListener? = runCatching {
        val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo").apply {
            isAccessible = true
        }
        val listenerInfo = listenerInfoField.get(view) ?: return null
        val touchListenerField = listenerInfo.javaClass.getDeclaredField("mOnTouchListener").apply {
            isAccessible = true
        }
        touchListenerField.get(listenerInfo) as? View.OnTouchListener
    }.getOrNull()
}
