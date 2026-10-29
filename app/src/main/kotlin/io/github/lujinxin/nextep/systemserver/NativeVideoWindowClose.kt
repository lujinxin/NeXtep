package io.github.lujinxin.nextep.systemserver

import android.os.ParcelFileDescriptor

/** Closing runs the owner's own listener, including when its player chrome auto-hides. */
internal object NativeVideoWindowClose {
    fun request(
        isCurrent: () -> Boolean,
        execute: (String, String, ParcelFileDescriptor) -> Unit,
        onFinished: (Result<Unit>) -> Unit,
    ) = NativeVideoWindowControls.request(NativeVideoWindowControls.Action.CLOSE, isCurrent, execute, onFinished)

    internal fun findCloseTarget(hierarchy: String): String? = NativeVideoWindowControls.findControls(hierarchy).close
}
