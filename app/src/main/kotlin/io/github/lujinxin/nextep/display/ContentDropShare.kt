package io.github.lujinxin.nextep.display

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.view.Display
import android.view.DragEvent
import io.github.lujinxin.nextep.framework.UserTargetedActivityLauncher
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.ContentDropPermissionContract as Permissions
import java.io.Closeable
import java.util.concurrent.Executors

/** A targeted ACTION_SEND handoff, without passing DROP into an OEM floating share panel. */
internal object ContentDropShare {
    data class Payload(val clip: ClipData, val permissions: IBinder?)

    class Prepared(val target: TaskRepository.TaskSnapshot, private val intent: Intent,
        private val permissions: Permissions.Lease?) : Closeable {
        var launched = false
            private set

        // Called only by the injected SystemUI host, with its privileged task APIs.
        @SuppressLint("BlockedPrivateApi")
        fun launch(context: Context): Result<Unit> = runCatching {
            val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
            // Explicitly stay in the promoted task. Launch bounds or OEM drag launch
            // flags could create a second freeform window instead of the share page.
            ActivityOptions::class.java.getMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                .invoke(options, target.taskId)
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, 1)
            UserTargetedActivityLauncher.start(context, intent, options.toBundle()).getOrThrow()
            launched = true
        }

        fun finishHandoff() {
            runCatching { permissions?.finishHandoff() }
                .onFailure { NeXtepLog.warn("content_drag", "Share window handoff could not be finished", it) }
        }

        override fun close() {
            runCatching { permissions?.close() }
                .onFailure { NeXtepLog.warn("content_drag", "Share permissions could not be released", it) }
        }
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-ContentShare").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())

    fun read(event: DragEvent): Payload {
        val data = checkNotNull(event.clipData) { "No dropped content" }
        check(data.itemCount in 1..Permissions.MAX_ITEMS) { "Unsupported item count" }
        val items = (0 until data.itemCount).map { index ->
            val item = data.getItemAt(index)
            check(item.intent == null) { "App/intent drags are not content shares" }
            check(item.uri == null || item.uri.scheme in setOf("content", "http", "https")) { "Unsupported URI scheme" }
            ClipData.Item(item.text?.toString(), item.htmlText, null, item.uri)
        }
        check(items.sumOf { it.text?.length ?: 0 } <= 100_000) { "Dropped text exceeds limit" }
        val clip = ClipData(ClipDescription(data.description), items.first())
        items.drop(1).forEach(clip::addItem)
        return Payload(clip, Permissions.fromEvent(event))
    }

    @SuppressLint("BlockedPrivateApi")
    fun prepare(context: Context, target: TaskRepository.TaskSnapshot, payload: Payload,
        onFinished: (Result<Prepared>) -> Unit) {
        worker.execute {
            var lease: Permissions.Lease? = null
            val result = runCatching {
                val clip = payload.clip
                val hostUser = checkNotNull(TaskInfoCompat.userIdentifier(Process.myUserHandle())) { "Unknown source user" }
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    .filter { it.scheme == "content" }.map { uri ->
                        val authority = checkNotNull(uri.encodedAuthority)
                        if ('@' in authority) uri else uri.buildUpon().encodedAuthority("$hostUser@$authority").build()
                    }.distinct()
                val texts = (0 until clip.itemCount).mapNotNull { index ->
                    val item = clip.getItemAt(index)
                    item.text?.toString()?.takeIf { it.isNotEmpty() }
                        ?: item.uri?.takeIf { it.scheme in setOf("http", "https") }?.toString()
                }
                check(uris.isNotEmpty() || texts.isNotEmpty()) { "No shareable content" }
                val userId = checkNotNull(target.userId) { "Unknown target user" }
                val user = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId) as UserHandle
                var files: ClipData? = null
                val mime = if (uris.isEmpty()) "text/plain" else {
                    val source = checkNotNull(payload.permissions) { "Source did not grant URI access" }
                    Permissions.takeSource(source).use {
                        val types = uris.mapNotNull { uri -> runCatching { context.contentResolver.getType(uri) }.getOrNull() }
                        val declared = (0 until clip.description.mimeTypeCount).map(clip.description::getMimeType)
                            .filter { it != "text/uri-list" && it != "text/plain" }
                        val resolved = commonMime(if (types.size == uris.size) types else (types + declared))
                        val content = ClipData("NeXtep shared content", arrayOf(resolved), ClipData.Item(uris.first()))
                        uris.drop(1).forEach { uri -> content.addItem(ClipData.Item(uri)) }
                        lease = Permissions.delegate(source, target.taskId, content)
                        files = content
                        resolved
                    }
                }
                val share = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
                    .setPackage(target.component.packageName).setType(mime)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER, user)
                    .putExtra(Permissions.EXTRA_SHARE_TASK_ID, target.taskId)
                if (uris.isNotEmpty()) {
                    share.clipData = files
                    // system_server already granted the actual recipient read access
                    // using the drop's source UID. SystemUI cannot regrant as UID 1000.
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (uris.size == 1) share.putExtra(Intent.EXTRA_STREAM, uris.first())
                    else share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
                if (texts.isNotEmpty()) share.putExtra(Intent.EXTRA_TEXT, texts.joinToString("\n"))
                Prepared(target, share, lease)
            }.onFailure { runCatching { lease?.close() } }
            handler.post { onFinished(result) }
        }
    }

    private fun commonMime(types: List<String>): String {
        val normalized = types.filter { '/' in it }.distinct()
        if (normalized.isEmpty()) return "application/octet-stream"
        if (normalized.size == 1) return normalized.first()
        val family = normalized.map { it.substringBefore('/') }.distinct().singleOrNull()
        return if (family != null) "$family/*" else "*/*"
    }
}
