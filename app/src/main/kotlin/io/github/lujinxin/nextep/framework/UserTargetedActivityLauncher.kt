package io.github.lujinxin.nextep.framework

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

object UserTargetedActivityLauncher {
    fun start(context: Context, source: Intent, options: Bundle? = null): Result<Unit> = runCatching {
        val targetUser = source.getParcelableExtra(
            TriggerBroadcastContract.EXTRA_TARGET_USER,
            UserHandle::class.java,
        )
        val intent = Intent(source).apply {
            removeExtra(TriggerBroadcastContract.EXTRA_TARGET_USER)
            if (targetUser != null) {
                val userId = checkNotNull(TaskInfoCompat.userIdentifier(targetUser)) { "Unknown target user" }
                check(userId >= 0) { "Invalid target user" }
                // Keep this identity through OEM launchers and the system resolver.
                // Starting the resolver as a clone alone does not pin its selected tab.
                putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER_ID, userId)
                putExtra(TriggerBroadcastContract.EXTRA_BYPASS_MULTI_APP_CHOOSER, true)
            }
        }
        if (targetUser == null || targetUser == Process.myUserHandle()) {
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
