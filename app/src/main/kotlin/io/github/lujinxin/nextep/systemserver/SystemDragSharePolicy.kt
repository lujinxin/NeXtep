package io.github.lujinxin.nextep.systemserver

/** Match only the OEM's drag interception windows, never application drop targets. */
internal object SystemDragSharePolicy {
    const val PORTAL_PACKAGE = "com.oplus.contentportal"
    private val portalWindows = setOf("ContentPortal-DragEventReceiver", "ContentPortalWindow")

    fun shouldBlock(workspaceActive: Boolean, enabled: Boolean, displayId: Int,
        packageName: String?, title: String?): Boolean =
        workspaceActive && enabled && displayId == 0 &&
            packageName == PORTAL_PACKAGE && title in portalWindows
}
