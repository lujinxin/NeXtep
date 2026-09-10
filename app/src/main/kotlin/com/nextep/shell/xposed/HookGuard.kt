package com.nextep.shell.xposed

import com.nextep.shell.logging.NeXtepLog

object HookGuard {
    inline fun run(feature: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (error: Throwable) {
        NeXtepLog.error(feature, "Hook initialization failed open", error)
        false
    }
}
