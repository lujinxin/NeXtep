package io.github.lujinxin.nextep.multiapp

import android.content.Intent

/** Only an explicitly targeted share already running in that user may override the resolver tab. */
internal object TargetedShareResolverPolicy {
    data class Origin(val taskId: Int, val userId: Int, val packageName: String?,
        val launchedFromSystemUi: Boolean, val callerMatchesResolver: Boolean)

    fun targetUserId(marked: Boolean, action: String?, targetUser: Int, processUser: Int,
        requestedPackage: String?, resolvedPackage: String?): Int? {
        if (!marked || action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
        if (targetUser < 0 || targetUser != processUser) return null
        if (requestedPackage.isNullOrBlank() || requestedPackage != resolvedPackage) return null
        return targetUser
    }

    fun authorizeHandoff(marked: Boolean, action: String?, taskId: Int, userId: Int,
        packageName: String?, resolvedPackage: String?, origin: Origin): Int? {
        if (taskId < 0 || taskId != origin.taskId || !origin.launchedFromSystemUi ||
            !origin.callerMatchesResolver || origin.packageName !in setOf("android", "com.oplus.multiapp")) return null
        return targetUserId(marked, action, userId, origin.userId, packageName, resolvedPackage)
    }
}
