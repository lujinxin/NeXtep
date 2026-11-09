package io.github.lujinxin.nextep.systemserver

import android.app.ActivityManager
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.multiapp.TargetedShareResolverPolicy
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract as Launch
import io.github.lujinxin.nextep.workspace.ContentDropPermissionContract as Drop
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge

/** Bind a resolver's handoff to the actual promoted task, before user-specific resolution. */
internal object ContentShareResolverHook {
    fun install(module: XposedModule, loader: ClassLoader) {
        val service = loader.loadClass("com.android.server.wm.ActivityTaskManagerService")
        val recordType = loader.loadClass("com.android.server.wm.ActivityRecord")
        val findRecord = listOf("isInAnyTask", "forTokenLocked").firstNotNullOf { name ->
            recordType.declaredMethods.firstOrNull { it.name == name &&
                it.parameterTypes.contentEquals(arrayOf(IBinder::class.java)) }
        }.apply { isAccessible = true }
        val canceled = ActivityManager::class.java.getDeclaredField("START_CANCELED")
            .apply { isAccessible = true }.getInt(null)
        val starts = service.declaredMethods.filter { it.name == "startActivityAsCaller" &&
            Intent::class.java in it.parameterTypes && IBinder::class.java in it.parameterTypes &&
            it.parameterTypes.lastOrNull() == Int::class.javaPrimitiveType }
        check(starts.isNotEmpty()) { "No compatible system resolver handoff" }
        starts.forEach { method ->
            val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
            val tokenIndex = method.parameterTypes.indexOf(IBinder::class.java)
            module.hook(method.apply { isAccessible = true }).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val outgoing = chain.args[intentIndex] as? Intent ?: return chain.proceed()
                    if (outgoing.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return chain.proceed()
                    var targeted = outgoing.hasExtra(Drop.EXTRA_SHARE_TASK_ID)
                    val decision = runCatching {
                        val owner = chain.thisObject ?: return@runCatching null
                        val lock = member(owner, "mGlobalLock") ?: return@runCatching null
                        synchronized(lock) {
                            val token = chain.args[tokenIndex] as? IBinder ?: return@synchronized null
                            val record = findRecord.invoke(null, token) ?: return@synchronized null
                            val original = member(record, "intent") as? Intent ?: return@synchronized null
                            // Inspect the protected source ActivityRecord rather than trusting
                            // extras supplied by an arbitrary startActivityAsCaller request.
                            if (!original.hasExtra(Drop.EXTRA_SHARE_TASK_ID)) return@synchronized null
                            targeted = true
                            check(SystemServerWorkspaceBridge.observedActiveGeometry() != null) { "Workspace left" }
                            val task = call(record, "getTask")
                            val process = member(record, "app")
                            val origin = TargetedShareResolverPolicy.Origin(
                                taskId = (task?.let { member(it, "mTaskId") } as? Int) ?: -1,
                                userId = (member(record, "mUserId") ?: member(record, "userId")) as? Int ?: -1,
                                packageName = member(record, "packageName") as? String,
                                launchedFromSystemUi = member(record, "launchedFromPackage") == Launch.SYSTEM_UI_PACKAGE,
                                callerMatchesResolver = process?.let { member(it, "mUid") } == Binder.getCallingUid(),
                            )
                            val target = checkNotNull(TargetedShareResolverPolicy.authorizeHandoff(
                                marked = original.getBooleanExtra(Launch.EXTRA_BYPASS_MULTI_APP_CHOOSER, false),
                                action = original.action,
                                taskId = original.getIntExtra(Drop.EXTRA_SHARE_TASK_ID, -1),
                                userId = original.getIntExtra(Launch.EXTRA_TARGET_USER_ID, -1),
                                packageName = original.`package`,
                                resolvedPackage = outgoing.component?.packageName ?: outgoing.`package`,
                                origin = origin,
                            )) { "Share resolver identity no longer matches the drop" }
                            ContentShareLaunchPolicy.renewResolverHandoff(origin.taskId, target)
                            target
                        }
                    }
                    val targetUser = decision.getOrNull()
                    if (targetUser == null && targeted) {
                        NeXtepLog.warn("content_drag", "Rejected mismatched share resolver handoff", decision.exceptionOrNull())
                        return canceled
                    }
                    if (targetUser == null) return chain.proceed()
                    val args = chain.args.toTypedArray()
                    val requestedUser = args.last()
                    args[args.lastIndex] = targetUser
                    NeXtepLog.info("content_drag", "Share resolver handoff user=$targetUser requestedUser=$requestedUser")
                    // Native caller checks and URI grant checks still run, exactly once.
                    return chain.proceed(args)
                }
            })
        }
        NeXtepLog.info("content_drag", "System resolver user routing installed")
    }

    private fun member(target: Any, name: String): Any? = generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }.firstOrNull()
        ?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String): Any? = generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredMethod(name) }.getOrNull() }.firstOrNull()
        ?.apply { isAccessible = true }?.invoke(target)
}
