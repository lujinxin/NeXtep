package io.github.lujinxin.nextep.launcher

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import io.github.lujinxin.nextep.icons.AppIconAppearance
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Repaints our existing launcher item without removing or adding a component. */
internal object LauncherNightIconHook {
    private val callbacks = WeakHashMap<LauncherApps.Callback, Handler>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var application: Context? = null
    @Volatile private var night: Boolean? = null

    fun install(module: XposedModule) {
        // Keep the launcher's real model callbacks and their dispatch thread. A
        // package update reloads artwork while preserving item IDs and folders.
        val register = LauncherApps::class.java.getDeclaredMethod("registerCallback",
            LauncherApps.Callback::class.java, Handler::class.java)
        module.hook(register).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val callback = chain.getArg(0) as? LauncherApps.Callback ?: return result
                val handler = chain.getArg(1) as? Handler ?: mainHandler
                synchronized(callbacks) { callbacks[callback] = handler }
                return result
            }
        })
        val unregister = LauncherApps::class.java.getDeclaredMethod("unregisterCallback",
            LauncherApps.Callback::class.java)
        module.hook(unregister).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val callback = chain.getArg(0) as? LauncherApps.Callback
                synchronized(callbacks) { callbacks.remove(callback) }
                return result
            }
        })
        listOf("getIcon", "getBadgedIcon").forEach { name ->
            val method = LauncherActivityInfo::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val info = chain.thisObject as? LauncherActivityInfo ?: return chain.proceed()
                    val context = application ?: return chain.proceed()
                    if (night != true || info.componentName.packageName != TriggerBroadcastContract.MODULE_PACKAGE) {
                        return chain.proceed()
                    }
                    return runCatching {
                        val resources = context.packageManager.getResourcesForApplication(info.applicationInfo)
                        val icon = resources.getIdentifier("ic_launcher_night", "mipmap",
                            TriggerBroadcastContract.MODULE_PACKAGE)
                        val drawable = checkNotNull(resources.getDrawableForDensity(icon, chain.getArg(0) as Int, null))
                        if (name == "getBadgedIcon") context.packageManager.getUserBadgedIcon(drawable, info.user)
                        else drawable
                    }.onFailure { NeXtepLog.warn("launcher_night_icon", "Unable to load night artwork", it) }
                        .getOrElse { chain.proceed() }
                }
            })
        }
        // ColorOS reads the activity icon directly when building its bitmap
        // cache. LauncherActivityInfo.getIcon is only its error fallback.
        val activityIcon = Class.forName("android.app.ApplicationPackageManager")
            .getDeclaredMethod("getActivityIcon", ComponentName::class.java)
        module.hook(activityIcon).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val component = chain.getArg(0) as? ComponentName ?: return chain.proceed()
                val context = application ?: return chain.proceed()
                if (night != true || component.packageName != TriggerBroadcastContract.MODULE_PACKAGE) {
                    return chain.proceed()
                }
                return runCatching {
                    val resources = context.packageManager.getResourcesForApplication(component.packageName)
                    val icon = resources.getIdentifier("ic_launcher_night", "mipmap", component.packageName)
                    checkNotNull(resources.getDrawable(icon, null))
                }.onFailure { NeXtepLog.warn("launcher_night_icon", "Unable to load activity night artwork", it) }
                    .getOrElse { chain.proceed() }
            }
        })
    }

    fun observe(context: Context) {
        if (!LauncherPackageResolver.isCurrentHome(context)) return
        val owner = context.applicationContext ?: context
        if (application == null) {
            application = owner
            owner.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = update(owner, newConfig)
                override fun onLowMemory() = Unit
            })
            owner.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = update(owner)
            }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        }
        update(owner)
    }

    private fun update(context: Context, configuration: Configuration = context.resources.configuration) {
        val requested = AppIconAppearance.usesNightIcon(context, configuration)
        if (night == requested) return
        night = requested
        val targets = synchronized(callbacks) { callbacks.map { WeakReference(it.key) to it.value } }
        targets.forEach { (reference, handler) ->
            handler.post {
                runCatching {
                    val callback = reference.get() ?: return@runCatching
                    // ColorOS ignores the public single-package callback for
                    // the main user. Its batch overload queues the icon update.
                    val batch = callback.javaClass.methods.firstOrNull { method ->
                        method.name == "onPackageChanged" && method.parameterTypes.contentEquals(
                            arrayOf(UserHandle::class.java, Array<String>::class.java),
                        )
                    }
                    if (batch != null) {
                        batch.invoke(callback, Process.myUserHandle(), arrayOf(TriggerBroadcastContract.MODULE_PACKAGE))
                    } else {
                        callback.onPackageChanged(TriggerBroadcastContract.MODULE_PACKAGE, Process.myUserHandle())
                    }
                }.onFailure { NeXtepLog.warn("launcher_night_icon", "Unable to refresh existing launcher item", it) }
            }
        }
        NeXtepLog.info("launcher_night_icon", "Artwork night=$requested; refreshing ${targets.size} launcher callbacks")
    }
}
