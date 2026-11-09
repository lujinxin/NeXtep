package io.github.lujinxin.nextep.systemserver

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/** Keep the drop's share entry and its immediate recipient-picker chain in the promoted task. */
internal object ContentShareLaunchPolicy {
    private data class Scope(val taskId: Int, val packageName: String, val userId: Int, @Volatile var expires: Long)
    private val scopes = ConcurrentHashMap<Int, Scope>()
    @Volatile private var ready = false

    @SuppressLint("BlockedPrivateApi")
    fun install(module: XposedModule, loader: ClassLoader) {
        ContentShareResolverHook.install(module, loader)
        val starter = loader.loadClass("com.android.server.wm.ActivityStarter")
        val starts = starter.declaredMethods.filter { method ->
            method.name == "startActivityInner" && method.parameterTypes.count {
                it.name == "com.android.server.wm.ActivityRecord"
            } >= 2 && ActivityOptions::class.java in method.parameterTypes
        }
        check(starts.isNotEmpty()) { "No compatible share activity launch boundary" }
        starts.forEach { method ->
            val records = method.parameterTypes.indices.filter {
                method.parameterTypes[it].name == "com.android.server.wm.ActivityRecord"
            }
            val optionsIndex = method.parameterTypes.indexOf(ActivityOptions::class.java)
            module.hook(method.apply { isAccessible = true }).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (scopes.isEmpty()) return chain.proceed()
                    val args = runCatching {
                        val record = chain.args[records[0]] ?: return@runCatching null
                        val source = chain.args[records[1]]
                        val scope = match(record, source, chain.args[optionsIndex] as? ActivityOptions)
                            ?: return@runCatching null
                        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
                        ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                            .invoke(options, 1)
                        ActivityOptions::class.java.getMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                            .invoke(options, scope.taskId)
                        val result = chain.args.toTypedArray()
                        result[optionsIndex] = options
                        // OEM share launches can carry new-document/adjacent flags
                        // into a second freeform task. This one share stays together.
                        (field(record, "intent") as? Intent)?.let { intent ->
                            val separate = Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                            intent.flags = intent.flags and separate.inv()
                        }
                        result
                    }.onFailure { NeXtepLog.warn("content_drag", "Share launch policy unavailable", it) }.getOrNull()
                    // The actual activity start is invoked exactly once, outside
                    // recovery handling; a failed launch must never be retried here.
                    return if (args != null) chain.proceed(args) else chain.proceed()
                }
            })
        }
        ready = true
        // Launch parameters are the final mode/bounds after OEM modifiers run.
        // Older ROMs without this optional boundary retain the explicit options.
        runCatching {
            val controller = loader.loadClass("com.android.server.wm.LaunchParamsController")
            controller.declaredMethods.filter { method ->
                method.name == "calculate" && method.parameterTypes.any { it.name == "com.android.server.wm.ActivityRecord" } &&
                    method.parameterTypes.any { it.name.endsWith("LaunchParamsController\$LaunchParams") }
            }.forEach { method ->
                val records = method.parameterTypes.indices.filter {
                    method.parameterTypes[it].name == "com.android.server.wm.ActivityRecord"
                }
                val optionsIndex = method.parameterTypes.indexOf(ActivityOptions::class.java)
                val outputIndex = method.parameterTypes.indexOfLast { it.name.endsWith("LaunchParamsController\$LaunchParams") }
                module.hook(method.apply { isAccessible = true }).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        if (scopes.isEmpty()) return result
                        runCatching {
                            val record = chain.args[records.first()] ?: return@runCatching
                            val source = records.getOrNull(1)?.let { chain.args[it] }
                            val options = if (optionsIndex >= 0) chain.args[optionsIndex] as? ActivityOptions else null
                            match(record, source, options) ?: return@runCatching
                            val output = chain.args[outputIndex] ?: return@runCatching
                            member(output, "mWindowingMode").setInt(output, 1)
                            (field(output, "mBounds") as? Rect)?.setEmpty()
                        }.onFailure { NeXtepLog.warn("content_drag", "Share launch geometry unavailable", it) }
                        return result
                    }
                })
            }
        }.onFailure { NeXtepLog.warn("content_drag", "Optional share launch geometry hook unavailable", it) }
    }

    fun begin(task: TaskRepository.TaskSnapshot): Closeable {
        check(ready) { "Share launch policy is unavailable" }
        val now = SystemClock.uptimeMillis()
        scopes.entries.removeIf { it.value.expires <= now }
        val scope = Scope(task.taskId, task.component.packageName, checkNotNull(task.userId), now + 15_000L)
        scopes[task.taskId] = scope
        return Closeable { scopes.remove(task.taskId, scope) }
    }

    fun renewResolverHandoff(taskId: Int, userId: Int) {
        // Choosing a share action can take longer than the launch settling window.
        scopes[taskId]?.takeIf { it.userId == userId }?.let {
            it.expires = SystemClock.uptimeMillis() + 15_000L
        }
    }

    private fun match(record: Any, source: Any?, options: ActivityOptions?): Scope? {
        if (SystemServerWorkspaceBridge.observedActiveGeometry() == null) return null
        val pkg = field(record, "packageName") as? String ?: return null
        val user = (field(record, "mUserId") ?: field(record, "userId")) as? Int ?: return null
        val now = SystemClock.uptimeMillis()
        val candidates = scopes.values.filter { it.packageName == pkg && it.userId == user && it.expires > now }
        if (candidates.isEmpty()) return null
        val sourceTask = source?.let { call(it, "getTask") }?.let { field(it, "mTaskId") as? Int }
        val requestedTask = options?.let { call(it, "getLaunchTaskId") as? Int }
        candidates.firstOrNull { it.taskId == sourceTask || it.taskId == requestedTask }?.let { return it }
        // Some share trampolines use their application Context, without a source
        // Activity. Accept only this app's own immediate follow-up, unambiguously.
        return candidates.singleOrNull()?.takeIf {
            source == null && field(record, "launchedFromPackage") == pkg
        }
    }

    private fun member(target: Any, name: String) = generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }.first().apply { isAccessible = true }
    private fun field(target: Any, name: String): Any? = runCatching { member(target, name).get(target) }.getOrNull()
    private fun call(target: Any, name: String): Any? = runCatching {
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod(name) }.getOrNull() }.first()
            .apply { isAccessible = true }.invoke(target)
    }.getOrNull()
}
