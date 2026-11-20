package io.github.lujinxin.nextep.systemui

import android.annotation.SuppressLint
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Workspace-scoped subscriptions; only task IDs cross into the view, never callback-owned images. */
internal class RecentTasksObserver(
    private val onTasksChanged: () -> Unit,
    private val onSnapshotChanged: (taskId: Int, invalidated: Boolean) -> Unit,
) {
    private data class Registration(val service: Any, val unregister: Method, val listener: Any)
    private val registrations = mutableListOf<Registration>()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var epoch = 0
    @Volatile private var active = false
    private var modernSnapshots = false
    private var failureLogged = false

    @SuppressLint("BlockedPrivateApi")
    fun start() {
        if (active) return
        active = true
        val request = ++epoch
        runCatching {
            val service = Class.forName("android.app.ActivityTaskManager")
                .getDeclaredMethod("getService").invoke(null)
            // Newer Android releases moved snapshot notifications to a separate service.
            modernSnapshots = runCatching {
                val manager = service.javaClass.getMethod("getTaskSnapshotManager")
                    .apply { isAccessible = true }.invoke(service)
                subscribe(manager, "android.window.ITaskSnapshotListener", "TaskSnapshot", request, snapshotsOnly = true)
            }.isSuccess
            subscribe(service, "android.app.ITaskStackListener", "TaskStack", request, snapshotsOnly = false)
        }.onFailure {
            // Existing ShellTaskOrganizer callbacks still update the task list on unsupported ROMs.
            if (!failureLogged) {
                failureLogged = true
                NeXtepLog.warn("recent_tasks", "Some system task subscriptions are unavailable", it)
            }
        }
    }

    fun stop() {
        active = false
        epoch++
        registrations.asReversed().forEach { registration ->
            runCatching { registration.unregister.invoke(registration.service, registration.listener) }
                .onFailure { NeXtepLog.warn("recent_tasks", "Unable to unregister system task listener", it) }
        }
        registrations.clear()
        modernSnapshots = false
    }

    private fun subscribe(service: Any, interfaceName: String, kind: String, request: Int, snapshotsOnly: Boolean) {
        val type = Class.forName(interfaceName)
        val endpoint = ListenerEndpoint(type) { event, taskId ->
            ui.post {
                if (!active || request != epoch) return@post
                when (event) {
                    "onTaskSnapshotChanged", "onTaskSnapshotInvalidated" -> {
                        if (snapshotsOnly || !modernSnapshots) {
                            onSnapshotChanged(taskId, event == "onTaskSnapshotInvalidated")
                        }
                    }
                    else -> onTasksChanged()
                }
            }
        }
        val register = service.javaClass.getMethod("register${kind}Listener", type).apply { isAccessible = true }
        val unregister = service.javaClass.getMethod("unregister${kind}Listener", type).apply { isAccessible = true }
        register.invoke(service, endpoint.listener)
        registrations += Registration(service, unregister, endpoint.listener)
    }

    /**
     * Resolve AIDL transaction IDs from the running framework rather than hard-coding OEM numbers.
     * The interface proxy supplies asBinder(); this endpoint handles the remote, one-way callbacks.
     */
    private class ListenerEndpoint(type: Class<*>, private val changed: (String, Int) -> Unit) : Binder() {
        private val descriptor = type.name
        private val transactions = Class.forName("${type.name}\$Stub").declaredFields
            .filter { it.name.startsWith("TRANSACTION_") && it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                field.isAccessible = true
                field.getInt(null) to field.name.removePrefix("TRANSACTION_")
            }
        val listener: Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "asBinder" -> this
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NeXtepRecentTasksListener($descriptor)"
                else -> null
            }
        }
        private var snapshotFailureLogged = false

        init {
            attachInterface(listener as IInterface, descriptor)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(descriptor)
                return true
            }
            val event = transactions[code] ?: return super.onTransact(code, data, reply, flags)
            data.enforceInterface(descriptor)
            when (event) {
                "onTaskSnapshotChanged" -> {
                    val taskId = data.readInt()
                    // This observer needs only invalidation. Release its own parceled buffer immediately.
                    runCatching { releaseSnapshot(data) }.onFailure {
                        if (!snapshotFailureLogged) {
                            snapshotFailureLogged = true
                            NeXtepLog.warn("recent_tasks", "Unable to release snapshot notification buffer", it)
                        }
                    }
                    changed(event, taskId)
                }
                "onTaskSnapshotInvalidated" -> changed(event, data.readInt())
                "onTaskStackChanged", "onRecentTaskListUpdated", "onTaskCreated", "onTaskRemoved",
                "onTaskMovedToFront", "onTaskMovedToBack", "onTaskDisplayChanged", "onTaskFocusChanged" -> changed(event, -1)
                // Other task-stack callbacks are intentionally ignored without decoding their payloads.
            }
            if (reply != null && (flags and IBinder.FLAG_ONEWAY) == 0) reply.writeNoException()
            return true
        }

        @SuppressLint("BlockedPrivateApi")
        @Suppress("UNCHECKED_CAST")
        private fun releaseSnapshot(data: Parcel) {
            val type = Class.forName("android.window.TaskSnapshot")
            val creator = type.getField("CREATOR").get(null) as Parcelable.Creator<Any>
            val snapshot = data.readTypedObject(creator) ?: return
            val close = runCatching { type.getMethod("closeBuffer") }.getOrNull()
            if (close != null) close.invoke(snapshot)
            else (type.getMethod("getHardwareBuffer").invoke(snapshot) as? HardwareBuffer)?.close()
        }
    }
}
