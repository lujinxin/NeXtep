package io.github.lujinxin.nextep.systemui

import android.app.Application
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
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
