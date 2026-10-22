package io.github.lujinxin.nextep.systemserver

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Region
import android.util.Size
import android.view.SurfaceControl
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Suppress only the input metadata of windows explicitly hosted by the video controller. */
internal object ControlAreaVideoInputHook {
    private const val LOG_FEATURE = "control_area_video_input"
    private const val PIP_INPUT_CONSUMER = "pip_input_consumer"

    private data class InputAccess(
        val newHandle: Constructor<*>,
        val copyApplicationHandle: Constructor<*>,
        val inputApplicationHandle: Field,
        val displayId: Field,
        val scalarFields: List<Field>,
        val frame: Field,
        val transform: Field,
        val contentSize: Field,
        val touchableRegionSurfaceControl: Field,
        val wrapperHandle: Field,
        val inputConfig: Field,
        val replaceTouchableRegionWithCrop: Field,
        val touchableRegion: Field,
        val touchOcclusionMode: Field,
        val setTouchableRegionCrop: Method,
        val setInputWindowInfo: Method,
        val consumerName: Field,
        val consumerHandle: Field,
        val consumerSurface: Field,
        val notTouchable: Int,
        val allowOcclusion: Int,
        val isSameSurface: Method?,
    )

    private data class TrackedInput(val original: Any, val pipConsumer: Boolean)

    // A pending display transaction can outlive restoration and still contain a suppressed
    // copy. Retain the live native original until its surface dies, so every eventual apply
    // can replace stale metadata with the state required at submission time.
    private val savedInputs = IdentityHashMap<SurfaceControl, TrackedInput>()
    private val savedInputLock = Any()
    private val composingApply = ThreadLocal.withInitial { false }
    private val fields = ConcurrentHashMap<String, Field>()
    private val methods = ConcurrentHashMap<String, Method>()
    private val reportedFailure = AtomicBoolean(false)
    @Volatile private var ready = false
    @Volatile private var access: InputAccess? = null
    private var installAttempted = false

    @Synchronized
    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        if (installAttempted) return ready
        installAttempted = true
        return runCatching {
            val monitorClass = loader.loadClass("com.android.server.wm.InputMonitor")
            val wrapperClass = loader.loadClass("com.android.server.wm.InputWindowHandleWrapper")
            val consumerClass = loader.loadClass("com.android.server.wm.InputConsumerImpl")
            val containerClass = loader.loadClass("com.android.server.wm.WindowContainer")
            val handleClass = loader.loadClass("android.view.InputWindowHandle")
            val applicationHandleClass = loader.loadClass("android.view.InputApplicationHandle")
            val inputConfigClass = loader.loadClass("android.os.InputConfig")
            val occlusionClass = loader.loadClass("android.gui.TouchOcclusionMode")
            val transactionClass = SurfaceControl.Transaction::class.java
            val surfaceClass = SurfaceControl::class.java
            val resolved = InputAccess(
                newHandle = handleClass.getDeclaredConstructor(applicationHandleClass,
                    Int::class.javaPrimitiveType!!).apply { isAccessible = true },
                copyApplicationHandle = applicationHandleClass.getDeclaredConstructor(applicationHandleClass)
                    .apply { isAccessible = true },
                inputApplicationHandle = requireField(handleClass, "inputApplicationHandle"),
                displayId = requireField(handleClass, "displayId"),
                // Match every scalar/reference copied by AOSP Android 16's copy constructor.
                // Binder tokens and Strings intentionally retain their identity; ptr never does.
                scalarFields = listOf("token", "windowToken", "name", "layoutParamsFlags",
                    "layoutParamsType", "dispatchingTimeoutMillis", "surfaceInset", "scaleFactor",
                    "inputConfig", "touchOcclusionMode", "ownerPid", "ownerUid", "packageName",
                    "displayId", "replaceTouchableRegionWithCrop", "focusTransferTarget", "alpha",
                    "canOccludePresentation").map { requireField(handleClass, it) },
                frame = requireField(handleClass, "frame"),
                transform = requireField(handleClass, "transform"),
                contentSize = requireField(handleClass, "contentSize"),
                touchableRegionSurfaceControl = requireField(handleClass, "touchableRegionSurfaceControl"),
                wrapperHandle = requireField(wrapperClass, "mHandle"),
                inputConfig = requireField(handleClass, "inputConfig"),
                replaceTouchableRegionWithCrop = requireField(handleClass, "replaceTouchableRegionWithCrop"),
                touchableRegion = requireField(handleClass, "touchableRegion"),
                touchOcclusionMode = requireField(handleClass, "touchOcclusionMode"),
                setTouchableRegionCrop = requireMethod(handleClass, "setTouchableRegionCrop", surfaceClass),
                setInputWindowInfo = requireMethod(transactionClass, "setInputWindowInfo", surfaceClass, handleClass),
                consumerName = requireField(consumerClass, "mName"),
                consumerHandle = requireField(consumerClass, "mWindowHandle"),
                consumerSurface = findField(consumerClass, "mInputSurface")
                    ?: requireField(consumerClass, "mSurfaceControl"),
                notTouchable = requireField(inputConfigClass, "NOT_TOUCHABLE").getInt(null),
                allowOcclusion = requireField(occlusionClass, "ALLOW").getInt(null),
                isSameSurface = findMethod(surfaceClass, "isSameSurface", surfaceClass),
            )
            val windowInfoMethod = requireMethod(monitorClass, "setInputWindowInfoIfNeeded",
                transactionClass, surfaceClass, wrapperClass)
            check(Modifier.isStatic(windowInfoMethod.modifiers))
            val consumerShows = buildList {
                add(requireMethod(consumerClass, "show", transactionClass,
                    Int::class.javaPrimitiveType!!))
                // AOSP's relative-layer overload submits input metadata independently of
                // show(Transaction, int). Hook it too whenever this ROM provides it.
                findMethod(consumerClass, "show", transactionClass, containerClass)?.let { add(it) }
            }
            val transactionApplies = transactionClass.declaredMethods.filter {
                it.name == "apply" && !Modifier.isNative(it.modifiers) && !Modifier.isStatic(it.modifiers)
            }
            check(transactionApplies.isNotEmpty()) { "No compatible SurfaceControl.Transaction.apply overload" }

            module.hook(windowInfoMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // Preserve the original result and original exception behavior, even when off.
                    val result = chain.proceed()
                    if (access == null) return result
                    var transaction: SurfaceControl.Transaction? = null
                    runCatching {
                        val currentAccess = access ?: return@runCatching
                        val currentTransaction = chain.getArg(0) as? SurfaceControl.Transaction
                            ?: return@runCatching
                        transaction = currentTransaction
                        val surface = chain.getArg(1) as? SurfaceControl ?: return@runCatching
                        val wrapper = chain.getArg(2) ?: return@runCatching
                        val handle = currentAccess.wrapperHandle.get(wrapper) ?: return@runCatching
                        if (ready && ControlAreaVideoController.ownsInputHandle(handle)) {
                            suppress(currentTransaction, surface, handle, currentAccess)
                        } else {
                            restoreIfTracked(currentTransaction, surface, handle, currentAccess)
                        }
                    }.onFailure { failOpen(transaction, it) }
                    return result
                }
            })
            val consumerHook = object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (access == null) return result
                    var transaction: SurfaceControl.Transaction? = null
                    runCatching {
                        val currentAccess = access ?: return@runCatching
                        val consumer = chain.thisObject ?: return@runCatching
                        if (currentAccess.consumerName.get(consumer) != PIP_INPUT_CONSUMER) {
                            return@runCatching
                        }
                        val currentTransaction = chain.getArg(0) as? SurfaceControl.Transaction
                            ?: return@runCatching
                        transaction = currentTransaction
                        val surface = currentAccess.consumerSurface.get(consumer) as? SurfaceControl
                            ?: return@runCatching
                        val handle = currentAccess.consumerHandle.get(consumer) ?: return@runCatching
                        if (ready && ControlAreaVideoController.isPipEmbedded()) {
                            suppress(currentTransaction, surface, handle, currentAccess, pipConsumer = true)
                        } else {
                            restoreIfTracked(currentTransaction, surface, handle, currentAccess, pipConsumer = true)
                        }
                    }.onFailure { failOpen(transaction, it) }
                    return result
                }
            }
            for (consumerShow in consumerShows) module.hook(consumerShow).intercept(consumerHook)
            val applyHook = object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val transaction = chain.thisObject as? SurfaceControl.Transaction ?: return chain.proceed()
                    if (composingApply.get() == true || access == null) return chain.proceed()
                    var compositionFailure: Throwable? = null
                    composingApply.set(true)
                    try {
                        // Serialize sampling and submission with restoration. An older pending
                        // transaction must not submit an already sampled suppression after a
                        // newer restore has committed. Controller ownership reads are volatile;
                        // never acquire the WM global lock while holding savedInputLock.
                        return synchronized(savedInputLock) {
                            val currentAccess = access
                            if (currentAccess != null) {
                                runCatching { appendCurrentInputsLocked(transaction, currentAccess) }
                                    .onFailure {
                                        ready = false
                                        compositionFailure = it
                                        // Discard any partial suppression in this transaction.
                                        // Recovery that takes the WM lock runs after submission.
                                        runCatching { appendCurrentInputsLocked(transaction, currentAccess) }
                                    }
                            }
                            // Preserve native return values and exception behavior. Nested Java
                            // overloads pass through the ThreadLocal without recomposing.
                            chain.proceed()
                        }
                    } finally {
                        try {
                            compositionFailure?.let { error ->
                                // Acquiring the WM lock here is safe: savedInputLock is released,
                                // including when native apply itself threw an exception. Keep
                                // the guard set while recovery applies its own transaction.
                                runCatching { failOpen(null, error) }
                            }
                        } finally {
                            composingApply.set(false)
                        }
                    }
                }
            }
            for (apply in transactionApplies) module.hook(apply).intercept(applyHook)
            // Partly installed hooks remain inert. Every available show overload must be hooked
            // before publishing ready; the shared accessors are published first.
            access = resolved
            ready = true
            runCatching {
                NeXtepLog.info(LOG_FEATURE, "Installed window, PiP consumer and transaction input suppression")
            }
            true
        }.getOrElse {
            ready = false
            reportFailure("Video input hooks unavailable; keeping native input", it)
            false
        }
    }

    fun isReady(): Boolean = ready

    /** Called before reparenting, using the same transaction as the visual change. */
    fun suppressCurrent(
        transaction: SurfaceControl.Transaction,
        windows: List<Any>,
        display: Any,
    ): Boolean {
        if (!ready) return false
        val currentAccess = access ?: return false
        val success = runCatching {
            var visibleWindowCount = 0
            for (window in windows) {
                val visible = callOptional(window, "isVisible") as? Boolean
                    ?: callOptional(window, "isVisibleRequested") as? Boolean ?: true
                if (!visible) continue
                val wrapper = fieldValue(window, "mInputWindowHandle") ?: return@runCatching false
                val handle = currentAccess.wrapperHandle.get(wrapper) ?: return@runCatching false
                if (!ControlAreaVideoController.ownsInputHandle(handle)) return@runCatching false
                val animator = fieldValue(window, "mWinAnimator") ?: return@runCatching false
                val surface = windowInputSurface(animator) ?: return@runCatching false
                if (!surface.isValid) return@runCatching false
                suppress(transaction, surface, handle, currentAccess)
                visibleWindowCount++
            }
            // Do not move an apparently visible player without suppressing its window input.
            if (visibleWindowCount == 0) return@runCatching false
            if (ControlAreaVideoController.isPipEmbedded()) {
                val monitor = callOptional(display, "getInputMonitor")
                    ?: fieldValue(display, "mInputMonitor") ?: return@runCatching false
                val consumer = requireMethod(monitor.javaClass, "getInputConsumer", String::class.java)
                    .invoke(monitor, PIP_INPUT_CONSUMER)
                // A consumer created later is covered by its show() hook before submission.
                if (consumer != null) {
                    val surface = currentAccess.consumerSurface.get(consumer) as? SurfaceControl
                        ?: return@runCatching false
                    val handle = currentAccess.consumerHandle.get(consumer) ?: return@runCatching false
                    if (!surface.isValid) return@runCatching false
                    suppress(transaction, surface, handle, currentAccess, pipConsumer = true)
                }
            }
            true
        }.getOrElse {
            reportFailure("Current player input unavailable; leaving the player undocked", it)
            false
        }
        if (!success) restoreAll(transaction)
        return success
    }

    /** The controller must clear its ownership gate and request a fresh input update as well. */
    fun restoreAll(transaction: SurfaceControl.Transaction) {
        val currentAccess = access ?: return
        synchronized(savedInputLock) {
            pruneInvalidInputsLocked()
            // Keep tracking valid surfaces after queuing clean metadata: an older display
            // transaction may still apply later and must also be rewritten by the guard.
            for ((surface, tracked) in savedInputs) {
                runCatching {
                    currentAccess.setInputWindowInfo.invoke(transaction, surface, tracked.original)
                }.onFailure { reportFailure("Original video input metadata could not be restored", it) }
            }
        }
    }

    private fun suppress(
        transaction: SurfaceControl.Transaction,
        surface: SurfaceControl,
        original: Any,
        currentAccess: InputAccess,
        pipConsumer: Boolean = false,
    ) {
        check(surface.isValid)
        synchronized(savedInputLock) {
            trackInputLocked(surface, original, pipConsumer, currentAccess)
            currentAccess.setInputWindowInfo.invoke(transaction, surface, suppressedHandle(original, currentAccess))
        }
    }

    private fun suppressedHandle(original: Any, currentAccess: InputAccess): Any {
        val copy = copyHandle(original, currentAccess)
        currentAccess.inputConfig.setInt(copy,
            currentAccess.inputConfig.getInt(copy) or currentAccess.notTouchable)
        // PiP normally replaces even an empty region with the Task's surface bounds.
        currentAccess.replaceTouchableRegionWithCrop.setBoolean(copy, false)
        currentAccess.setTouchableRegionCrop.invoke(copy, *arrayOf<Any?>(null))
        (currentAccess.touchableRegion.get(copy) as Region).setEmpty()
        // A visible, non-touchable external overlay can otherwise block SystemUI touches.
        // This applies only to the copy of an explicitly embedded window, never WM's model.
        currentAccess.touchOcclusionMode.setInt(copy, currentAccess.allowOcclusion)
        // InputWindowHandle has no Java dispose API. The temporary copy is left to its finalizer.
        return copy
    }

    private fun trackInputLocked(
        surface: SurfaceControl,
        original: Any,
        pipConsumer: Boolean,
        currentAccess: InputAccess,
    ) {
        val iterator = savedInputs.entries.iterator()
        while (iterator.hasNext()) {
            val saved = iterator.next()
            if (!saved.key.isValid || sameSurface(saved.key, surface, currentAccess)) iterator.remove()
        }
        // Keep a native model reference, never one of our suppressed copies. Both native
        // wrapper replacement and normal WM updates refresh this reference via the hooks.
        savedInputs[surface] = TrackedInput(original, pipConsumer)
    }

    private fun pruneInvalidInputsLocked() {
        val iterator = savedInputs.entries.iterator()
        while (iterator.hasNext()) if (!iterator.next().key.isValid) iterator.remove()
    }

    private fun appendCurrentInputsLocked(transaction: SurfaceControl.Transaction, currentAccess: InputAccess) {
        pruneInvalidInputsLocked()
        for ((surface, tracked) in savedInputs) {
            val suppress = ready && if (tracked.pipConsumer) ControlAreaVideoController.isPipEmbedded()
                else ControlAreaVideoController.ownsInputHandle(tracked.original)
            val handle = if (suppress) suppressedHandle(tracked.original, currentAccess) else tracked.original
            currentAccess.setInputWindowInfo.invoke(transaction, surface, handle)
        }
    }

    private fun copyHandle(original: Any, currentAccess: InputAccess): Any {
        // AOSP InputWindowHandle(other) unconditionally copies its application handle. Ordinary
        // overlays have no ActivityRecord and therefore a null application handle, so construct
        // a new native-uninitialized handle instead of invoking that null-unsafe constructor.
        val copy = currentAccess.newHandle.newInstance(null, currentAccess.displayId.getInt(original))
        val application = currentAccess.inputApplicationHandle.get(original)
        currentAccess.inputApplicationHandle.set(copy,
            application?.let { currentAccess.copyApplicationHandle.newInstance(it) })
        for (field in currentAccess.scalarFields) field.set(copy, field.get(original))
        // Keep the constructor-created final Rect/Region objects; never alias WM's mutable data.
        (currentAccess.frame.get(copy) as Rect).set(currentAccess.frame.get(original) as Rect)
        (currentAccess.touchableRegion.get(copy) as Region)
            .set(currentAccess.touchableRegion.get(original) as Region)
        currentAccess.transform.set(copy,
            (currentAccess.transform.get(original) as? Matrix)?.let(::Matrix))
        val contentSize = currentAccess.contentSize.get(original) as Size
        currentAccess.contentSize.set(copy, Size(contentSize.width, contentSize.height))
        val crop = currentAccess.touchableRegionSurfaceControl.get(original) as? WeakReference<*>
        currentAccess.touchableRegionSurfaceControl.set(copy, WeakReference(crop?.get()))
        return copy
    }

    private fun restoreIfTracked(
        transaction: SurfaceControl.Transaction,
        surface: SurfaceControl,
        original: Any,
        currentAccess: InputAccess,
        pipConsumer: Boolean = false,
    ) {
        synchronized(savedInputLock) {
            pruneInvalidInputsLocked()
            if (surface.isValid && savedInputs.keys.any { sameSurface(it, surface, currentAccess) }) {
                trackInputLocked(surface, original, pipConsumer, currentAccess)
                // The original method may skip output because its wrapper was not dirty.
                // Always emit the current native model for a previously borrowed surface.
                currentAccess.setInputWindowInfo.invoke(transaction, surface, original)
            }
        }
    }

    private fun windowInputSurface(animator: Any): SurfaceControl? {
        (fieldValue(animator, "mSurfaceControl") as? SurfaceControl)?.let { return it }
        val controller = fieldValue(animator, "mSurfaceController")
        if (controller != null) {
            (fieldValue(controller, "mSurfaceControl") as? SurfaceControl)?.let { return it }
            surfaceFromGetter(controller)?.let { return it }
        }
        return surfaceFromGetter(animator)
    }

    private fun surfaceFromGetter(target: Any): SurfaceControl? {
        val getter = findMethod(target.javaClass, "getSurfaceControl")
        if (getter != null) return getter.invoke(target) as? SurfaceControl
        val copyGetter = findMethod(target.javaClass, "getSurfaceControl", SurfaceControl::class.java)
            ?: return null
        val output = SurfaceControl::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }.newInstance()
        try {
            copyGetter.invoke(target, output)
        } catch (error: Throwable) {
            output.release()
            throw error
        }
        // The returned Java wrapper is cached until restoration, then collected normally.
        return output
    }

    private fun sameSurface(first: SurfaceControl, second: SurfaceControl, currentAccess: InputAccess): Boolean =
        first === second || (first.isValid && second.isValid && runCatching {
            currentAccess.isSameSurface?.invoke(first, second) == true
        }.getOrDefault(false))

    private fun failOpen(transaction: SurfaceControl.Transaction?, error: Throwable) {
        ready = false
        runCatching {
            // Clear controller ownership and restore the visual source in the same transaction.
            // A null transaction is handled by the controller with a fresh transaction.
            ControlAreaVideoController.onInputFailure(transaction)
        }.onFailure { restoreError ->
            // Recovery must not throw through either system_server hook. Keep the original
            // input-metadata fallback even if native-parent restoration itself is unavailable.
            runCatching {
                if (transaction != null) {
                    restoreAll(transaction)
                } else {
                    SurfaceControl.Transaction().use { fallback ->
                        restoreAll(fallback)
                        fallback.apply()
                    }
                }
            }
            reportFailure("Video source recovery unavailable after input suppression failure", restoreError)
        }
        reportFailure("Video input suppression disabled; keeping native input", error)
    }

    private fun reportFailure(message: String, error: Throwable) {
        if (reportedFailure.compareAndSet(false, true)) {
            runCatching { NeXtepLog.warn(LOG_FEATURE, message, error) }
        }
    }

    private fun fieldValue(target: Any, name: String): Any? = findField(target.javaClass, name)?.get(target)

    private fun requireField(type: Class<*>, name: String): Field =
        findField(type, name) ?: throw NoSuchFieldException("${type.name}#$name")

    private fun findField(type: Class<*>, name: String): Field? {
        val key = "${type.name}#$name"
        fields[key]?.let { return it }
        return generateSequence(type as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true; fields[key] = this }
    }

    private fun callOptional(target: Any, name: String): Any? =
        findMethod(target.javaClass, name)?.invoke(target)

    private fun requireMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method =
        findMethod(type, name, *parameters)
            ?: throw NoSuchMethodException("${type.name}#$name/${parameters.size}")

    private fun findMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method? {
        val key = "${type.name}#$name/${parameters.joinToString { it.name }}"
        methods[key]?.let { return it }
        return generateSequence(type as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod(name, *parameters) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true; methods[key] = this }
    }
}
