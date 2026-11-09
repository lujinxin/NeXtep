package io.github.lujinxin.nextep.workspace

import android.annotation.SuppressLint
import android.content.ClipData
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.view.DragEvent
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** A drop's existing Binder capability is the only entry point; there is no public grant service. */
internal object ContentDropPermissionContract {
    const val DESCRIPTOR = "io.github.lujinxin.nextep.ContentDropPermissions"
    const val DELEGATE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 0x4E5800
    const val FINISH_HANDOFF_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1
    const val MAX_ITEMS = 32
    const val LEASE_TIMEOUT_MS = 15 * 60_000L
    const val EXTRA_SHARE_TASK_ID = "io.github.lujinxin.nextep.extra.CONTENT_SHARE_TASK_ID"

    @SuppressLint("BlockedPrivateApi")
    fun fromEvent(event: DragEvent): IBinder? = runCatching {
        val permissions = DragEvent::class.java.getMethod("getDragAndDropPermissions").invoke(event)
        (permissions as? IInterface)?.asBinder()
    }.getOrNull()

    class Lease(private val token: IBinder, private val owner: IBinder) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            transact(IBinder.FIRST_CALL_TRANSACTION)
        }

        fun finishHandoff() {
            if (!closed.get()) transact(FINISH_HANDOFF_TRANSACTION)
        }

        private fun transact(code: Int) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeStrongBinder(owner)
                if (token.transact(code, data, reply, 0)) reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    @SuppressLint("BlockedPrivateApi")
    fun takeSource(source: IBinder): Closeable {
        val stub = Class.forName("com.android.internal.view.IDragAndDropPermissions\$Stub")
        val permissions = stub.getMethod("asInterface", IBinder::class.java).invoke(null, source)
        try {
            permissions.javaClass.getMethod("takeTransient").apply { isAccessible = true }.invoke(permissions)
        } catch (error: Throwable) {
            runCatching { permissions.javaClass.getMethod("release").apply { isAccessible = true }.invoke(permissions) }
            throw error
        }
        return Closeable {
            permissions.javaClass.getMethod("release").apply { isAccessible = true }.invoke(permissions)
        }
    }

    fun delegate(source: IBinder, targetTaskId: Int, clip: ClipData): Lease {
        val owner = Binder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(targetTaskId)
            data.writeTypedObject(clip, 0)
            data.writeStrongBinder(owner)
            check(source.transact(DELEGATE_TRANSACTION, data, reply, 0)) { "Drop permission bridge unavailable" }
            reply.readException()
            return Lease(checkNotNull(reply.readStrongBinder()) { "Drop permission grant rejected" }, owner)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
