package com.nextep.shell.multiapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.nextep.shell.logging.NeXtepLog
import com.nextep.shell.trigger.TriggerBroadcastContract
import com.nextep.shell.xposed.HookGuard
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object OplusMultiAppHook {
    fun install(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("multi_app_hooks") {
            val resolverClass = Class.forName(
                "com.oplus.multiapp.chooser.MultiAppResolverActivity",
                false,
                classLoader,
            )
            val onCreate = resolverClass.getDeclaredMethod("onCreate", Bundle::class.java).apply {
                isAccessible = true
            }
            module.hook(onCreate).intercept(MultiAppResolverHooker())
            NeXtepLog.info("multi_app_hooks", "ColorOS resolver hook installed")
        }
    }
}

class MultiAppResolverHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val activity = chain.thisObject as? Activity ?: return chain.proceed()
        val result = chain.proceed()
        val targetIntent = activity.intent?.getParcelableExtra(
            Intent.EXTRA_INTENT,
            Intent::class.java,
        ) ?: return result
        if (!targetIntent.getBooleanExtra(
                TriggerBroadcastContract.EXTRA_BYPASS_MULTI_APP_CHOOSER,
                false,
            )
        ) {
            return result
        }
        val userId = targetIntent.getIntExtra(
            TriggerBroadcastContract.EXTRA_TARGET_USER_ID,
            0,
        )
        targetIntent.removeExtra(TriggerBroadcastContract.EXTRA_BYPASS_MULTI_APP_CHOOSER)
        targetIntent.removeExtra(TriggerBroadcastContract.EXTRA_TARGET_USER_ID)
        return runCatching {
            activity.javaClass.getMethod(
                "startActivityAsCaller",
                Int::class.javaPrimitiveType!!,
            ).invoke(activity, userId)
            activity.finish()
            NeXtepLog.info(
                "multi_app_resolver",
                "Opened marked launcher target directly for user=$userId",
            )
            result
        }.getOrElse { error ->
            NeXtepLog.warn("multi_app_resolver", "Direct launch failed", error)
            result
        }
    }
}
