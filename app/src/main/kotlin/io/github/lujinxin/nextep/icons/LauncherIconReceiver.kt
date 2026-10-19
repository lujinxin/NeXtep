package io.github.lujinxin.nextep.icons

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LauncherIconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LauncherIconManager.ACTION_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> LauncherIconManager.refresh(context)
        }
    }
}
