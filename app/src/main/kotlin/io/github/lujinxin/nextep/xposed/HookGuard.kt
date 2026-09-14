package io.github.lujinxin.nextep.xposed

import io.github.lujinxin.nextep.logging.NeXtepLog

object HookGuard {
    inline fun run(feature: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (error: Throwable) {
        NeXtepLog.error(feature, "Hook initialization failed open", error)
        false
    }
}
