package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.hardware.input.InputManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowInsets
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.libxposed.api.XposedInterface

/** Exit a retained player fullscreen UI once after moving it into a portrait slot. */
class SlotVideoFullscreenExitHooker(private val report: (String) -> Unit) : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val activity = chain.thisObject ?: return chain.proceed()
        val destination = chain.getArg(0)
        val source = runCatching { field(activity, "mDisplayContent") }.getOrNull()
        val candidate = runCatching {
            source != null && source !== destination &&
                call(source, "getDisplayId") == 0 && destination != null &&
                isSlot(destination) && isDynamicFullscreen(activity).also {
                    report("slot video migration: eligible=$it requested=${call(activity, "getRequestedOrientation")} " +
                        "manifest=${(field(activity, "info") as? ActivityInfo)?.screenOrientation}")
                }
        }.getOrDefault(false)
        val result = chain.proceed()
        if (!candidate || destination == null) return result
        report("slot video exit scheduled")
        runCatching {
            val service = checkNotNull(field(activity, "mAtmService"))
            val handler = field(service, "mH") as Handler
            // Give the application's normal configuration handler a chance to exit first.
            // No retries: a second BACK could navigate away from the playing video.
            handler.postDelayed({ exitIfStillFullscreen(activity, destination, service) }, 500L)
        }.onFailure {
            report("slot video schedule failed: $it")
            NeXtepLog.warn("slot_video_exit", "Unable to schedule fullscreen exit", it)
        }
        return result
    }

    private fun exitIfStillFullscreen(activity: Any, display: Any, service: Any) {
        runCatching {
            val context = field(service, "mContext") as Context
            val packageName = field(activity, "packageName") as String
            val sessions = context.getSystemService(MediaSessionManager::class.java)
                ?: return
            val userId = field(activity, "mUserId") as? Int
                ?: field(activity, "userId") as? Int ?: return
            val controllers = sessions.javaClass.methods.firstOrNull {
                it.name == "getActiveSessionsForUser" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }?.invoke(sessions, null, userId) as? List<*>
                ?: if (userId == 0) sessions.getActiveSessions(null) else return
            val hasMedia = controllers.filterIsInstance<android.media.session.MediaController>().any {
                it.packageName == packageName && (it.playbackState?.state in setOf(
                    PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
                    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
                ) || hasPortraitReturnTarget(activity))
            }
            if (!hasMedia) {
                report("slot video exit skipped: no active media session")
                return
            }
            val input = context.getSystemService(InputManager::class.java) ?: return
            val inject = input.javaClass.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
            )
            val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            val lock = checkNotNull(field(service, "mGlobalLock"))
            synchronized(lock) {
                // Validate ownership and focus again after the delay. Never send BACK to
                // Display 0, a replacement task, a dialog, or an Activity that already exited.
                if (field(activity, "mDisplayContent") !== display || !isSlot(display) ||
                    !isDynamicFullscreen(activity)
                ) {
                    report("slot video exit skipped: Activity moved or fullscreen already cleared")
                    return
                }
                val mainWindow = call(activity, "findMainWindow") ?: return
                if (field(display, "mCurrentFocus") !== mainWindow) {
                    report("slot video exit skipped: main window not focused")
                    return
                }
                val displayId = call(display, "getDisplayId") as? Int ?: return
                if (displayId <= 0) return
                val now = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val event = KeyEvent(
                        now, now, action, KeyEvent.KEYCODE_BACK, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                        InputDevice.SOURCE_KEYBOARD,
                    )
                    setDisplay.invoke(event, displayId)
                    // ASYNC: never wait for an app while holding the window-manager lock.
                    check(inject.invoke(input, event, 0) == true) { "BACK injection rejected" }
                }
                NeXtepLog.info("slot_video_exit", "Requested one fullscreen exit package=$packageName display=$displayId")
                report("slot video exit sent once display=$displayId")
            }
        }.onFailure {
            report("slot video exit failed: $it")
            NeXtepLog.warn("slot_video_exit", "Fullscreen exit failed open", it)
        }
    }

    private fun isDynamicFullscreen(activity: Any): Boolean {
        val info = field(activity, "info") as? ActivityInfo ?: return false
        if (info.applicationInfo.category == ApplicationInfo.CATEGORY_GAME) return false
        if (isLandscape(info.screenOrientation) && !hasPortraitReturnTarget(activity)) return false
        if (!isLandscape(call(activity, "getRequestedOrientation") as? Int)) return false
        val window = call(activity, "findMainWindow") ?: return false
        val visibleTypes = call(window, "getRequestedVisibleTypes") as? Int ?: return false
        return visibleTypes and WindowInsets.Type.statusBars() == 0
    }

    // Some players open a dedicated landscape Activity for a portrait detail page.
    // A registered media session may remain STOPPED during playback in that Activity.
    private fun hasPortraitReturnTarget(activity: Any): Boolean {
        val target = field(activity, "resultTo") ?: return false
        if (field(target, "finishing") == true ||
            field(target, "packageName") != field(activity, "packageName") ||
            call(target, "getTask") !== call(activity, "getTask")
        ) return false
        return (call(target, "getRequestedOrientation") as? Int) in setOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
        )
    }

    private fun isLandscape(orientation: Int?): Boolean = orientation in setOf(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
    )

    private fun isSlot(display: Any): Boolean {
        val info = call(display, "getDisplayInfo") ?: return false
        return (field(info, "name") as? String)?.startsWith("NeXtep-slot-") == true
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == name }
            ?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }?.invoke(target)
}
