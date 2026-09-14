package io.github.lujinxin.nextep.workspace

import android.content.Context
import android.provider.Settings
import io.github.lujinxin.nextep.logging.NeXtepLog

object SystemServerWorkspaceBridge {
    private const val ACTIVE_KEY = "nextep_workspace_active_v1"
    private const val WIDTH_KEY = "nextep_workspace_screen_width_v1"
    private const val HEIGHT_KEY = "nextep_workspace_screen_height_v1"
    private const val SIDEBAR_SIDE_KEY = "nextep_workspace_sidebar_side_v1"

    fun publish(context: Context, active: Boolean, geometry: WorkspaceGeometry? = null) {
        runCatching {
            val resolver = context.contentResolver
            geometry?.let {
                Settings.Global.putInt(resolver, WIDTH_KEY, it.screenWidth)
                Settings.Global.putInt(resolver, HEIGHT_KEY, it.screenHeight)
                Settings.Global.putString(resolver, SIDEBAR_SIDE_KEY, it.sidebarSide.name)
            }
            Settings.Global.putInt(resolver, ACTIVE_KEY, if (active) 1 else 0)
        }.onFailure { error ->
            NeXtepLog.warn("system_server_bridge", "Unable to publish active=$active", error)
        }
    }

    fun activePhysicalAspectRatio(context: Context): Float? = runCatching {
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, ACTIVE_KEY, 0) != 1) return@runCatching null
        val width = Settings.Global.getInt(resolver, WIDTH_KEY, 0)
        val height = Settings.Global.getInt(resolver, HEIGHT_KEY, 0)
        if (width <= 0 || height <= width) null else height.toFloat() / width.toFloat()
    }.getOrNull()

    fun isWorkspaceActive(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        val uncachedValue = resolver.call(
            Settings.AUTHORITY,
            "GET_global",
            ACTIVE_KEY,
            null,
        )?.getString(Settings.NameValueTable.VALUE)
        (uncachedValue?.toIntOrNull()
            ?: Settings.Global.getInt(resolver, ACTIVE_KEY, 0)) == 1
    }.getOrDefault(false)

    fun sidebarSide(context: Context): SidebarSide = runCatching {
        Settings.Global.getString(context.contentResolver, SIDEBAR_SIDE_KEY)
            ?.let(SidebarSide::valueOf)
            ?: SidebarSide.RIGHT
    }.getOrDefault(SidebarSide.RIGHT)
}
