package io.github.lujinxin.nextep.systemserver

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/** Commands are restricted to controls discovered in this exact borrowed window. */
internal object NativeVideoWindowControls {
    enum class Action { CLOSE, TOGGLE_PLAYBACK }
    data class Controls(val close: String? = null, val playbackToggle: String? = null)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-NativeVideoControls").apply { isDaemon = true }
    }

    fun inspect(
        isCurrent: () -> Boolean,
        execute: (String, String, ParcelFileDescriptor) -> Unit,
        onFinished: (Result<Controls>) -> Unit,
    ) {
        worker.execute {
            onFinished(runCatching {
                check(isCurrent()) { "Video source changed before inspection" }
                val controls = findControls(command("DUMP", "", execute))
                check(isCurrent()) { "Video source changed during inspection" }
                controls
            })
        }
    }

    fun request(
        action: Action,
        isCurrent: () -> Boolean,
        execute: (String, String, ParcelFileDescriptor) -> Unit,
        onFinished: (Result<Unit>) -> Unit,
    ) {
        worker.execute {
            onFinished(runCatching {
                check(isCurrent()) { "Video source changed before $action" }
                val controls = findControls(command("DUMP", "", execute))
                val target = checkNotNull(when (action) {
                    Action.CLOSE -> controls.close
                    Action.TOGGLE_PLAYBACK -> controls.playbackToggle
                }) { "No unambiguous native $action control" }
                check(isCurrent()) { "Video source changed while locating $action" }
                // Never retry: some frameworks invoke the listener and then fail
                // to serialize performClick's primitive return value.
                val reply = command("INVOKE_METHOD", "$target performClick AAAAAA==", execute)
                check(reply.lineSequence().firstOrNull() == "1") {
                    "Native $action click returned no confirmation: ${reply.take(160)}"
                }
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
                check(remaining > 0 && Os.poll(arrayOf(poll), remaining) > 0) { "Native window request timed out" }
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

    internal fun findControls(hierarchy: String): Controls {
        val nodes = mutableListOf<Node>()
        val parents = mutableListOf<Node>()
        for (line in hierarchy.lineSequence()) {
            val indent = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: continue
            val body = line.substring(indent)
            val target = body.substringBefore(' ')
            if (!target.matches(Regex("[\\w.$]+@[0-9a-fA-F]+"))) continue
            val node = Node(target, indent, readProperties(body.substringAfter(' ', "")))
            while (parents.isNotEmpty() && parents.last().indent >= indent) parents.removeAt(parents.lastIndex)
            parents.lastOrNull()?.children?.add(node)
            parents.add(node)
            nodes.add(node)
        }
        val root = nodes.firstOrNull() ?: return Controls()
        if (root.properties["getVisibility"] !in setOf("VISIBLE", "0", "0x0")) return Controls()
        val close = nodes.filter { it.actionable && it.id.matches(
            Regex("(?i)(?:.*_)?(?:close|dismiss)(?:_.*)?|close(?:Window|Button|View)")) }
        val playback = nodes.filter { it.actionable && it.id.matches(
            Regex("(?i)(?:.*_)?(?:play|pause|play_pause|playback_toggle)(?:_.*)?|(?:playPause|pausePlay)(?:Button|View)?")) }
        val profiled = dragonPlayerControls(root)
        return Controls(
            close = if (close.isEmpty()) profiled.close else close.singleOrNull()?.target,
            playbackToggle = if (playback.isEmpty()) profiled.playbackToggle else playback.singleOrNull()?.target,
        )
    }

    private fun dragonPlayerControls(root: Node): Controls {
        // Recognize this player's complete native chrome structure. Obfuscated IDs
        // and screen coordinates are not used to select its close/fullscreen buttons.
        if (root.type != "com.dragon.read.component.shortvideo.impl.floatwindow.FloatingWindowLayout" ||
            root.width <= 0 || root.height <= 0
        ) return Controls()
        val chrome = root.children.filter {
            it.type == "androidx.constraintlayout.widget.ConstraintLayout" &&
                it.width == root.width && it.height == root.height
        }.singleOrNull() ?: return Controls()
        val buttons = chrome.children.filter { it.actionable }
        if (buttons.size != 3 || buttons.any { button -> button.children.none { it.type.endsWith("ImageView") } }) {
            return Controls()
        }
        fun topButton(node: Node) = node.top >= 0 && node.top + node.height <= root.height * 0.3f &&
            node.width <= root.width * 0.35f && node.height <= root.height * 0.3f
        val close = buttons.singleOrNull { topButton(it) && it.left >= 0 && it.left <= root.width * 0.1f }
            ?: return Controls()
        val fullscreen = buttons.singleOrNull {
            topButton(it) && it.left >= root.width * 0.5f && it.left + it.width >= root.width * 0.9f
        } ?: return Controls()
        val toggle = buttons.singleOrNull {
            it !== close && it !== fullscreen && it.width >= close.width && it.height >= close.height &&
                abs(it.left + it.width / 2f - root.width / 2f) <= root.width * 0.1f &&
                abs(it.top + it.height / 2f - root.height / 2f) <= root.height * 0.1f
        } ?: return Controls()
        return Controls(close.target, toggle.target)
    }

    private class Node(val target: String, val indent: Int, val properties: Map<String, String>) {
        val children = mutableListOf<Node>()
        val type: String get() = target.substringBefore('@')
        val id: String get() = properties["mID"]?.substringAfter("id/", "").orEmpty()
        val left: Int get() = properties["getLeft"]?.toIntOrNull() ?: properties["mLeft"]?.toIntOrNull() ?: -1
        val top: Int get() = properties["getTop"]?.toIntOrNull() ?: properties["mTop"]?.toIntOrNull() ?: -1
        val width: Int get() = properties["getWidth"]?.toIntOrNull() ?: 0
        val height: Int get() = properties["getHeight"]?.toIntOrNull() ?: 0
        val actionable: Boolean get() = properties["isClickable"] == "true" &&
            properties["isEnabled"] == "true" && width > 0 && height > 0
    }

    /** Labels may themselves contain property-looking text; consume each length-prefixed value. */
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
