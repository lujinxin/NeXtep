package io.github.lujinxin.nextep.launcher

import android.content.Context
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

object LauncherPackageResolver {
    /** HOME packages used by Pixel Launcher and AOSP Launcher3. */
    const val PIXEL_LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher"
    const val AOSP_LAUNCHER_PACKAGE = "com.android.launcher3"

    fun isCurrentHome(context: Context): Boolean =
        TriggerBroadcastContract.resolveHomePackage(context) == context.packageName

    fun isWorkspaceSurface(context: Context): Boolean =
        isCurrentHome(context) || context.packageName == ASSISTANT_SCREEN_PACKAGE

    fun isAssistantScreen(context: Context): Boolean =
        context.packageName == ASSISTANT_SCREEN_PACKAGE

    fun isPixelLauncherPackage(packageName: String): Boolean =
        packageName == PIXEL_LAUNCHER_PACKAGE || packageName == AOSP_LAUNCHER_PACKAGE

    private const val ASSISTANT_SCREEN_PACKAGE = "com.coloros.assistantscreen"
}
