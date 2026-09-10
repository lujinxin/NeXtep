package com.nextep.shell.framework

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import com.nextep.shell.trigger.TriggerBroadcastContract

object UserTargetedActivityLauncher {
    fun start(context: Context, source: Intent, options: Bundle? = null): Result<Unit> = runCatching {
        val targetUser = source.getParcelableExtra(
            TriggerBroadcastContract.EXTRA_TARGET_USER,
            UserHandle::class.java,
        )
        val intent = Intent(source).apply {
            removeExtra(TriggerBroadcastContract.EXTRA_TARGET_USER)
        }
        if (targetUser == null) {
            context.startActivity(intent, options)
            return@runCatching
        }
        val method = Context::class.java.getMethod(
            "startActivityAsUser",
            Intent::class.java,
            Bundle::class.java,
            UserHandle::class.java,
        )
        method.invoke(context, intent, options, targetUser)
    }
}
