package io.github.lujinxin.nextep.systemserver

import android.content.res.Configuration
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.libxposed.api.XposedInterface

/** A slot is a preview on the physical screen, not a different user input environment. */
class SlotDisplayConfigurationHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val display = chain.thisObject ?: return result
        val configuration = chain.getArg(0) as? Configuration ?: return result
        runCatching {
            val info = call(display, "getDisplayInfo") ?: return@runCatching
            val name = field(info, "name") as? String ?: return@runCatching
            if (!name.startsWith("NeXtep-slot-")) return@runCatching
            val service = field(display, "mWmService") ?: error("Window manager unavailable")
            val root = field(service, "mRoot") ?: error("Display root unavailable")
            val physical = call(root, "getDefaultDisplay") ?: error("Default display unavailable")
            val reference = call(physical, "getConfiguration") as? Configuration
                ?: error("Default display configuration unavailable")
            // Keep virtual-window bounds and rotation intact. Resolution/density already
            // match Display 0 in SlotGeometry; match the remaining display capabilities.
            // No Activity restart decision or package-specific configChanges is overridden.
            configuration.colorMode = reference.colorMode
            configuration.touchscreen = reference.touchscreen
            configuration.keyboard = reference.keyboard
            configuration.keyboardHidden = reference.keyboardHidden
            configuration.hardKeyboardHidden = reference.hardKeyboardHidden
            configuration.navigation = reference.navigation
            configuration.navigationHidden = reference.navigationHidden
            NeXtepLog.info("slot_display_config", "$name matched physical capabilities color=${reference.colorMode} touch=${reference.touchscreen}")
        }.onFailure {
            NeXtepLog.warn("slot_display_config", "Display capability matching failed open", it)
        }
        return result
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
