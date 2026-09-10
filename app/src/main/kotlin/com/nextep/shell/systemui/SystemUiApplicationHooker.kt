package com.nextep.shell.systemui

import android.app.Application
import com.nextep.shell.logging.NeXtepLog
import com.nextep.shell.trigger.TriggerBroadcastContract
import io.github.libxposed.api.XposedInterface

/**
 * Framework-level fallback for OEM builds that rename, wrap, or invoke their SystemUI
 * Application differently from AOSP's SystemUIApplication.
 */
class SystemUiApplicationHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val application = chain.getArg(0) as? Application
        val result = chain.proceed()
        if (application?.packageName != TriggerBroadcastContract.SYSTEM_UI_PACKAGE) return result

        try {
            SystemUiRuntime.initialize(application)
        } catch (error: Throwable) {
            NeXtepLog.error("systemui_framework_startup", "Fallback initialization failed open", error)
        }
        return result
    }
}
