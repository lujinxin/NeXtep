package io.github.lujinxin.nextep.systemserver

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Runs the borrowed window's own click listener, so its owner releases the player. */
internal object NativeVideoWindowClose {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-NativeVideoClose").apply { isDaemon = true }
    }

    fun request(
        isCurrent: () -> Boolean,
        execute: (String, String, ParcelFileDescriptor) -> Unit,
        onFinished: (Result<Unit>) -> Unit,
    ) {
        worker.execute {
            onFinished(runCatching {
                check(isCurrent()) { "Video source changed before close" }
                val hierarchy = command("DUMP", "", execute)
                val target = checkNotNull(findCloseTarget(hierarchy)) { "No unambiguous native close control" }
                check(isCurrent()) { "Video source changed while finding close control" }
                // Four zero bytes encode an empty argument list for ViewDebug. Invoke
                // only performClick on the one control found in this exact window.
                val reply = command("INVOKE_METHOD", "$target performClick AAAAAA==", execute)
                check(reply.lineSequence().firstOrNull() == "1") { "Native close click was not confirmed: ${reply.take(160)}" }
            })
        }
    }

    private fun command(
        name: String,
        parameters: String,
        execute: (String, String, ParcelFileDescriptor) -> Unit,
    ): String {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            execute(name, parameters, pipe[1])
            pipe[1].close()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val poll = StructPollfd().apply { fd = pipe[0].fileDescriptor; events = OsConstants.POLLIN.toShort() }
            val deadline = SystemClock.uptimeMillis() + 1500L
            while (true) {
                val remaining = (deadline - SystemClock.uptimeMillis()).toInt()
                check(remaining > 0 && Os.poll(arrayOf(poll), remaining) > 0) { "Native close request timed out" }
                val count = Os.read(pipe[0].fileDescriptor, buffer, 0, buffer.size)
                if (count == 0) break
                check(output.size() + count <= 1024 * 1024) { "Native window hierarchy exceeded limit" }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    internal fun findCloseTarget(hierarchy: String): String? {
        val candidates = mutableListOf<String>()
        var windowVisible: Boolean? = null
        for (line in hierarchy.lineSequence()) {
            val indent = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: continue
            val body = line.substring(indent)
            val target = body.substringBefore(' ')
            if (!target.matches(Regex("[\\w.$]+@[0-9a-fA-F]+"))) continue
            val properties = readProperties(body.substringAfter(' ', ""))
            if (windowVisible == null) windowVisible = properties["getVisibility"] in setOf("VISIBLE", "0", "0x0")
            val id = properties["mID"]?.substringAfter("id/", "") ?: continue
            val close = id.matches(Regex("(?i)(?:.*_)?(?:close|dismiss)(?:_.*)?|close(?:Window|Button|View)"))
            // Native player chrome can auto-hide while the window keeps playing.
            // Its close listener is still the owner's teardown action.
            if (windowVisible && close && properties["isClickable"] == "true" && properties["isEnabled"] == "true" &&
                (properties["getWidth"]?.toIntOrNull() ?: 0) > 0 &&
                (properties["getHeight"]?.toIntOrNull() ?: 0) > 0) candidates += target
        }
        return candidates.singleOrNull()
    }

    /** ViewDebug values are length prefixed; labels may themselves contain property-looking text. */
    private fun readProperties(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var cursor = 0
        while (cursor < text.length) {
            val equals = text.indexOf('=', cursor).takeIf { it >= 0 } ?: break
            val comma = text.indexOf(',', equals + 1).takeIf { it >= 0 } ?: break
            val length = text.substring(equals + 1, comma).toIntOrNull() ?: break
            val end = comma.toLong() + 1L + length
            if (length < 0 || end > text.length) break
            val key = text.substring(cursor, equals).trim().substringAfterLast(':').removeSuffix("()")
            result[key] = text.substring(comma + 1, end.toInt())
            cursor = end.toInt() + 1
        }
        return result
    }
}
