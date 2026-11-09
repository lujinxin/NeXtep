package io.github.lujinxin.nextep.systemserver

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.view.Display
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.ContentDropPermissionContract as Contract
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Forward only this drop's read grants, on behalf of its real source UID, to the selected slot. */
internal object ContentDropPermissionHook {
    // onSystemServerStarting can run before the main Looper is prepared.
    // A timer is needed only after an actual drop, once the system is ready.
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun install(module: XposedModule, loader: ClassLoader) {
        val type = loader.loadClass("com.android.server.wm.DragAndDropPermissionsHandler")
        val stub = loader.loadClass("com.android.internal.view.IDragAndDropPermissions\$Stub")
        val method = stub.getDeclaredMethod("onTransact", Int::class.javaPrimitiveType,
            Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val source = chain.thisObject ?: return chain.proceed()
                if (chain.args[0] != Contract.DELEGATE_TRANSACTION || !type.isInstance(source)) return chain.proceed()
                val data = chain.getArg(1) as Parcel
                val reply = chain.getArg(2) as? Parcel ?: return true
                runCatching {
                    data.enforceInterface(Contract.DESCRIPTOR)
                    val context = systemContext()
                    val callerUid = Binder.getCallingUid()
                    val systemUiUid = context.packageManager
                        .getApplicationInfo(TriggerBroadcastContract.SYSTEM_UI_PACKAGE, 0).uid
                    check(callerUid == systemUiUid && field(source, "mTargetPackage") ==
                        TriggerBroadcastContract.SYSTEM_UI_PACKAGE) { "Not a SystemUI drop" }
                    check(SystemServerWorkspaceBridge.observedActiveGeometry() != null &&
                        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Workspace is not interactive" }
                    val taskId = data.readInt()
                    val clip = checkNotNull(data.readTypedObject(ClipData.CREATOR))
                    val owner = checkNotNull(data.readStrongBinder())
                    val target = checkNotNull(TaskRepository(context).findTask(taskId))
                    check(target.displayId != Display.DEFAULT_DISPLAY && target.userId != null &&
                        target.component.packageName != TriggerBroadcastContract.SYSTEM_UI_PACKAGE) { "Target left its slot" }
                    val sourceUser = field(source, "mSourceUserId") as Int
                    checkProfileSharing(context, sourceUser, checkNotNull(target.userId))
                    val mode = field(source, "mMode") as Int
                    check(mode and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Drop does not grant read access" }
                    val allowed = (field(source, "mUris") as List<*>).filterIsInstance<Uri>()
                        .map { canonical(it, sourceUser) }.toSet()
                    check(clip.itemCount in 1..Contract.MAX_ITEMS)
                    for (index in 0 until clip.itemCount) {
                        val item = clip.getItemAt(index)
                        check(item.intent == null && item.uri != null && canonical(item.uri, sourceUser) in allowed) {
                            "URI was not part of this drop"
                        }
                    }
                    val constructor = type.declaredConstructors.single { candidate ->
                        val p = candidate.parameterTypes
                        p.size == 7 && p[1] == ClipData::class.java && p[2] == Int::class.javaPrimitiveType &&
                            p[3] == String::class.java && p.drop(4).all { it == Int::class.javaPrimitiveType }
                    }.apply { isAccessible = true }
                    val delegated = constructor.newInstance(field(source, "mGlobalLock"), clip,
                        field(source, "mSourceUid"), target.component.packageName,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION, sourceUser, target.userId)
                    var launchPolicy: Closeable? = null
                    try {
                        invoke(delegated, "takeTransient")
                        val policy = ContentShareLaunchPolicy.begin(target)
                        launchPolicy = policy
                        val lease = Lease(delegated, owner, callerUid, policy)
                        reply.writeNoException()
                        reply.writeStrongBinder(lease)
                    } catch (error: Throwable) {
                        launchPolicy?.close()
                        runCatching { invoke(delegated, "release") }
                        throw error
                    }
                }.onFailure { error ->
                    NeXtepLog.warn("content_drag", "Drop read permission handoff failed", error)
                    reply.writeException(IllegalStateException("Cannot forward this drop's read permissions"))
                }
                return true
            }
        })
    }

    private class Lease(private val permissions: Any, private val owner: IBinder, private val callerUid: Int,
        private val launchPolicy: Closeable) :
        Binder(), IBinder.DeathRecipient {
        private val closed = AtomicBoolean(false)
        private val expire = Runnable { close() }

        init {
            owner.linkToDeath(this, 0)
            handler.postDelayed(expire, Contract.LEASE_TIMEOUT_MS)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION && code != Contract.FINISH_HANDOFF_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(Contract.DESCRIPTOR)
            check(Binder.getCallingUid() == callerUid && data.readStrongBinder() == owner) { "Wrong drop owner" }
            if (code == Contract.FINISH_HANDOFF_TRANSACTION) launchPolicy.close() else close()
            reply?.writeNoException()
            return true
        }

        override fun binderDied() = close()

        private fun close() {
            if (!closed.compareAndSet(false, true)) return
            handler.removeCallbacks(expire)
            launchPolicy.close()
            runCatching { owner.unlinkToDeath(this, 0) }
            runCatching { invoke(permissions, "release") }
                .onFailure { NeXtepLog.warn("content_drag", "Drop read permission release failed", it) }
        }
    }

    private fun canonical(uri: Uri, defaultUser: Int): Pair<Int, Uri> {
        check(uri.scheme == "content") { "Only content URIs can be delegated" }
        val authority = checkNotNull(uri.authority)
        val user = if ('@' in authority) authority.substringBefore('@').toInt() else defaultUser
        return user to uri.buildUpon().authority(authority.substringAfter('@')).build()
    }

    @SuppressLint("BlockedPrivateApi")
    private fun systemContext(): Context {
        val type = Class.forName("android.app.ActivityThread")
        val thread = type.getMethod("currentActivityThread").invoke(null)
        return type.getMethod("getSystemContext").invoke(thread) as Context
    }

    @SuppressLint("BlockedPrivateApi")
    private fun checkProfileSharing(context: Context, source: Int, target: Int) {
        if (source == target) return
        val manager = context.getSystemService(UserManager::class.java)
        val user = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
        val restrictions = UserManager::class.java.getMethod("getUserRestrictions", UserHandle::class.java)
        val from = restrictions.invoke(manager, user.invoke(null, source)) as Bundle
        val to = restrictions.invoke(manager, user.invoke(null, target)) as Bundle
        check(!from.getBoolean(UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE) &&
            !to.getBoolean(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE)) { "Profile sharing is restricted" }
    }

    private fun field(target: Any, name: String): Any? = generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }.first()
        .apply { isAccessible = true }.get(target)

    private fun invoke(target: Any, name: String) = target.javaClass.getMethod(name)
        .apply { isAccessible = true }.invoke(target)
}
