package io.github.lujinxin.nextep.systemserver

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Base64
import io.github.lujinxin.nextep.workspace.ControlAreaVideoButtonBounds
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs

/** Commands are restricted to controls discovered in this exact borrowed window. */
internal object NativeVideoWindowControls {
    enum class Action { CLOSE, TOGGLE_PLAYBACK, FULLSCREEN }
    data class Controls(
        val close: String? = null,
        val playbackToggle: String? = null,
        val seekable: Boolean = false,
        val fullscreen: String? = null,
        val closeChrome: CornerControl? = null,
        val fullscreenChrome: CornerControl? = null,
        val replacedClose: ControlAreaVideoButtonBounds? = null,
        val replacedFullscreen: ControlAreaVideoButtonBounds? = null,
    )
    data class CornerControl(val glyph: String, val visibility: Int, val bounds: ControlAreaVideoButtonBounds,
        val alpha: Float = 1f)

    /** Hide only recognized corner glyphs while our full-resolution controls replace them. */
    class ChromeLease {
        private data class Hidden(val alpha: Float, val execute: (String, String, ParcelFileDescriptor) -> Unit)
        private val hidden = mutableMapOf<String, Hidden>() // Accessed only on worker.
        @Volatile private var closed = false

        fun replace(controls: Controls, isCurrent: () -> Boolean,
            execute: (String, String, ParcelFileDescriptor) -> Unit): Controls {
            if (closed || !isCurrent()) return controls
            // Keep the player's native close button visible and clickable. Only
            // replace fullscreen chrome, for which SystemUI provides a larger hit target.
            val targets = listOfNotNull(controls.fullscreenChrome?.glyph).toSet()
            hidden.keys.filter { it !in targets }.forEach { target ->
                val saved = hidden.remove(target) ?: return@forEach
                runCatching { setAlpha(target, saved.alpha, saved.execute) }
            }
            fun replace(control: CornerControl?): ControlAreaVideoButtonBounds? {
                if (control == null || closed || !isCurrent()) return null
                hidden.putIfAbsent(control.glyph, Hidden(control.alpha, execute))
                return runCatching {
                    // Keep visibility, layout and listener semantics native; only
                    // conceal the scaled glyph beneath our vector replacement.
                    if (control.alpha != 0f) setAlpha(control.glyph, 0f, execute)
                    check(!closed && isCurrent())
                    control.bounds
                }.getOrElse {
                    // A timed-out reply may still have changed the view. Restore it
                    // before declining to publish a replacement button.
                    runCatching { setAlpha(control.glyph, hidden.getValue(control.glyph).alpha, execute) }
                    null
                }
            }
            return controls.copy(replacedClose = controls.closeChrome?.bounds,
                replacedFullscreen = replace(controls.fullscreenChrome))
        }

        fun close() {
            closed = true
            worker.execute {
                hidden.forEach { (target, saved) -> runCatching { setAlpha(target, saved.alpha, saved.execute) } }
                hidden.clear()
            }
        }
    }
    class NotInvokedException(cause: Throwable) : RuntimeException("Native control was not invoked", cause)
    private val nonTimelineId = Regex("(?i)volume|brightness|speed|rate")
    private val timelineId = Regex("(?i)seek|timeline|playback_position")
    private val playerTimelineType = Regex("(?i)(?:video|player|media|playback|timeline).*SeekBar$")

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-NativeVideoControls").apply { isDaemon = true }
    }

    fun inspect(
        isCurrent: () -> Boolean,
        execute: (String, String, ParcelFileDescriptor) -> Unit,
        chrome: ChromeLease? = null,
        onFinished: (Result<Controls>) -> Unit,
    ) {
        worker.execute {
            onFinished(runCatching {
                check(isCurrent()) { "Video source changed before inspection" }
                val controls = findControls(command("DUMP", "", execute))
                check(isCurrent()) { "Video source changed during inspection" }
                chrome?.replace(controls, isCurrent, execute) ?: controls
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
            var invocationAttempted = false
            val result = runCatching {
                check(isCurrent()) { "Video source changed before $action" }
                val controls = findControls(command("DUMP", "", execute))
                val target = checkNotNull(when (action) {
                    Action.CLOSE -> controls.close
                    Action.TOGGLE_PLAYBACK -> controls.playbackToggle
                    Action.FULLSCREEN -> controls.fullscreen
                }) { "No unambiguous native $action control" }
                check(isCurrent()) { "Video source changed while locating $action" }
                // Never retry: some frameworks invoke the listener and then fail
                // to serialize performClick's primitive return value.
                invocationAttempted = true
                val reply = command("INVOKE_METHOD", "$target performClick AAAAAA==", execute)
                check(reply.lineSequence().firstOrNull() == "1") {
                    "Native $action click returned no confirmation: ${reply.take(160)}"
                }
            }
            // A caller may safely choose another backend only before invocation.
            // An uncertain reply after performClick must never cause a second toggle.
            onFinished(if (!invocationAttempted && result.isFailure) {
                Result.failure(NotInvokedException(checkNotNull(result.exceptionOrNull())))
            } else result)
        }
    }

    private fun setAlpha(target: String, alpha: Float,
        execute: (String, String, ParcelFileDescriptor) -> Unit) {
        val args = ByteBuffer.allocate(10).putInt(1).putChar('F').putFloat(alpha).array()
        val reply = command("INVOKE_METHOD", "$target setAlpha ${Base64.encodeToString(args, Base64.NO_WRAP)}", execute)
        check(reply.lineSequence().firstOrNull() == "1") { "Native glyph replacement was not confirmed" }
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
            node.parent = parents.lastOrNull()
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
        val fullscreen = nodes.filter { it.actionable && it.id.matches(
            Regex("(?i)(?:.*_)?(?:fullscreen|full_screen|expand|maximize)(?:_.*)?|(?:fullScreen|fullscreen|expand)(?:Button|View)")) &&
            cornerControl(it, root, right = true) != null }
        val closeTarget = if (close.isEmpty()) profiled.close else close.singleOrNull()?.target
        val fullscreenTarget = if (fullscreen.isEmpty()) profiled.fullscreen else fullscreen.singleOrNull()?.target
        val seekControls = nodes.filter { node ->
            // Inspect capabilities, including controls hidden until the first tap.
            // Do not confuse a brightness/volume slider or a read-only ProgressBar
            // with the player's timeline. Unknown custom-drawn controls remain unknown.
            node.properties["isEnabled"] == "true" &&
                !nonTimelineId.containsMatchIn(node.id) &&
                (node.type.endsWith("TimeBar") || playerTimelineType.containsMatchIn(node.type.substringAfterLast('.')) ||
                    (timelineId.containsMatchIn(node.id) && (node.type.endsWith("SeekBar") || node.actionable)))
        }
        return Controls(
            close = closeTarget,
            playbackToggle = if (playback.isEmpty()) profiled.playbackToggle else playback.singleOrNull()?.target,
            seekable = seekControls.size == 1,
            fullscreen = fullscreenTarget,
            closeChrome = nodes.singleOrNull { it.target == closeTarget }?.let { cornerControl(it, root, right = false) },
            fullscreenChrome = nodes.singleOrNull { it.target == fullscreenTarget }?.let { cornerControl(it, root, right = true) },
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
        return Controls(close.target, toggle.target, fullscreen = fullscreen.target)
    }

    private fun cornerControl(node: Node, root: Node, right: Boolean): CornerControl? {
        if (root.width <= 0 || root.height <= 0 || node.width <= 0 || node.height <= 0) return null
        val path = generateSequence(node) { it.parent }.takeWhile { it !== root }.toList()
        if (path.any { it.left < 0 || it.top < 0 }) return null
        val left = path.sumOf { it.left }
        val top = path.sumOf { it.top }
        if (left < 0 || top < 0 || left + node.width > root.width || top + node.height > root.height ||
            node.width > root.width * 0.4f || node.height > root.height * 0.35f ||
            top + node.height / 2f > root.height * 0.3f) return null
        val centre = left + node.width / 2f
        if (if (right) centre < root.width * 0.7f else centre > root.width * 0.3f) return null
        val glyph = if (node.type.endsWith("ImageView")) node else
            node.children.filter { it.type.endsWith("ImageView") }.singleOrNull() ?: return null
        val visibility = when (glyph.properties["getVisibility"]) {
            "VISIBLE", "0", "0x0" -> 0
            "INVISIBLE", "4", "0x4" -> 4
            "GONE", "8", "0x8" -> 8
            else -> return null
        }
        return CornerControl(glyph.target, visibility,
            ControlAreaVideoButtonBounds(left, top, node.width, node.height, root.width, root.height),
            glyph.properties["getAlpha"]?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f } ?: 1f)
    }

    private class Node(val target: String, val indent: Int, val properties: Map<String, String>) {
        var parent: Node? = null
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
