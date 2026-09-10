package com.nextep.shell.systemserver

import android.content.Context
import com.nextep.shell.logging.NeXtepLog
import com.nextep.shell.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface

class SizeCompatDisplayInsetsHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val context = findContext(chain.thisObject) ?: return chain.proceed()
        if (SystemServerWorkspaceBridge.activePhysicalAspectRatio(context) == null) {
            return chain.proceed()
        }
        return false
    }

    private fun findContext(target: Any?): Context? = runCatching {
        val activityRecord = if (target?.javaClass?.name == ACTIVITY_RECORD_CLASS) {
            target
        } else {
            readField(target, "mActivityRecord")
        } ?: return@runCatching null
        val service = readField(activityRecord, "mAtmService") ?: return@runCatching null
        readField(service, "mContext") as? Context
    }.onFailure { error ->
        NeXtepLog.warn("size_compat_display_insets", "Context lookup failed open", error)
    }.getOrNull()

    private fun readField(target: Any?, name: String): Any? {
        var type: Class<*>? = target?.javaClass
        while (type != null && type != Any::class.java) {
            runCatching {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }
            type = type.superclass
        }
        return null
    }

    private companion object {
        const val ACTIVITY_RECORD_CLASS = "com.android.server.wm.ActivityRecord"
    }
}
