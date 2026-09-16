package io.github.lujinxin.nextep.framework

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflective SystemUI/WM Shell bridge for transforming a real task leash.
 *
 * The task remains fullscreen on display 0. Only its compositor leash is scaled and positioned,
 * matching the reference OneStep implementation and avoiding ColorOS freeform re-layout.
 */
object TaskSurfaceCompat {
    private data class ActivePresentation(
        val leash: Any,
        val scaleX: Float,
        val scaleY: Float,
        val positionX: Float,
        val positionY: Float,
        val reapplyUntil: Long,
        var lastAppliedAt: Long,
    )

    private const val SYSTEM_UI_FACTORY =
        "com.android.systemui.SystemUIAppComponentFactoryBase"
    private const val INITIALIZER_FIELD = "systemUIInitializer"
    private const val ORGANIZER_PROVIDER_FIELD = "provideShellTaskOrganizerProvider"
    private const val TASKS_FIELD = "mTasks"
    private const val LOCK_FIELD = "mLock"

    @Volatile
    private var hostClassLoader: ClassLoader? = null
    private val activePresentations = ConcurrentHashMap<Int, ActivePresentation>()
    private val presentationAnimators = ConcurrentHashMap<Int, ValueAnimator>()

    fun initialize(classLoader: ClassLoader) {
        hostClassLoader = classLoader
        NeXtepLog.info("task_surface", "Host class loader captured: $classLoader")
    }

    fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> = runCatching {
        require(taskId > 0) { "Invalid taskId=$taskId" }
        // Recovery polling must not restart the entry fade on an already presented task.
        if (activePresentations.containsKey(taskId)) {
            reapply(taskId, geometry).getOrThrow()
            return@runCatching
        }
        val leash = findTaskLeash(taskId)
            ?: error("WM Shell task leash unavailable for taskId=$taskId")
        check(isValid(leash)) { "WM Shell task leash is invalid for taskId=$taskId" }

        val scaleX = geometry.contentWidth.toFloat() / geometry.screenWidth
        val scaleY = geometry.contentHeight.toFloat() / geometry.screenHeight
        val now = SystemClock.uptimeMillis()
        val presentation = ActivePresentation(
            leash = leash,
            scaleX = scaleX,
            scaleY = scaleY,
            positionX = geometry.contentLeft.toFloat(),
            positionY = geometry.contentTop.toFloat(),
            reapplyUntil = now + REAPPLY_WINDOW_MS,
            lastAppliedAt = now,
        )
        activePresentations[taskId] = presentation
        animatePresentation(
            taskId = taskId,
            from = presentation.copy(
                scaleX = presentation.scaleX * PRESENT_ENTER_SCALE,
                scaleY = presentation.scaleY * PRESENT_ENTER_SCALE,
                positionX = presentation.positionX + PRESENT_ENTER_OFFSET_PX,
                positionY = presentation.positionY + PRESENT_ENTER_OFFSET_PX,
            ),
            to = presentation,
            fadeIn = true,
        )
        NeXtepLog.info(
            "task_surface",
            "Presented taskId=$taskId scale=$scaleX,$scaleY " +
                "position=${geometry.contentLeft},${geometry.contentTop}",
        )
    }.onFailure { error ->
        NeXtepLog.error("task_surface", "Presentation failed taskId=$taskId", error)
    }

    fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> = runCatching {
        val previous = activePresentations[taskId]
            ?: error("No active surface presentation for taskId=$taskId")
        val now = SystemClock.uptimeMillis()
        val scaleX = geometry.contentWidth.toFloat() / geometry.screenWidth
        val scaleY = geometry.contentHeight.toFloat() / geometry.screenHeight
        val geometryChanged = previous.scaleX != scaleX ||
            previous.scaleY != scaleY ||
            previous.positionX != geometry.contentLeft.toFloat() ||
            previous.positionY != geometry.contentTop.toFloat()
        if (!geometryChanged && now - previous.lastAppliedAt < MIN_REAPPLY_INTERVAL_MS) {
            return@runCatching
        }
        if (!geometryChanged && now > previous.reapplyUntil &&
            now - previous.lastAppliedAt < STEADY_REAPPLY_INTERVAL_MS
        ) {
            return@runCatching
        }
        val currentLeash = findTaskLeash(taskId)
            ?: error("WM Shell task leash unavailable for taskId=$taskId")
        check(isValid(currentLeash)) {
            "WM Shell task leash is invalid for taskId=$taskId"
        }
        val leashChanged = currentLeash !== previous.leash
        val presentation = if (leashChanged || geometryChanged) {
            ActivePresentation(
                leash = currentLeash,
                scaleX = scaleX,
                scaleY = scaleY,
                positionX = geometry.contentLeft.toFloat(),
                positionY = geometry.contentTop.toFloat(),
                reapplyUntil = now + REAPPLY_WINDOW_MS,
                lastAppliedAt = 0L,
            ).also { activePresentations[taskId] = it }
        } else {
            previous
        }
        if (leashChanged) {
            // A replaced leash must never be overwritten by an older animator's final frame.
            cancelPresentationAnimation(taskId)
            applyPresentation(presentation)
        } else if (geometryChanged) {
            animatePresentation(taskId, previous, presentation, fadeIn = false)
        } else if (presentationAnimators[taskId]?.isRunning != true) {
            applyPresentation(presentation)
        }
        presentation.lastAppliedAt = now
        if (leashChanged) {
            NeXtepLog.info("task_surface", "Rebound changed leash taskId=$taskId")
        }
    }.onFailure { error ->
        NeXtepLog.error("task_surface", "Reapply failed taskId=$taskId", error)
    }

    fun restore(taskId: Int): Result<Unit> = runCatching {
        cancelPresentationAnimation(taskId)
        val leash = activePresentations.remove(taskId)?.leash ?: findTaskLeash(taskId)
        if (leash == null || !isValid(leash)) {
            NeXtepLog.warn(
                "task_surface",
                "Restore skipped because task leash is gone taskId=$taskId",
            )
            return@runCatching
        }
        applyPresentation(
            leash = leash,
            scaleX = 1f,
            scaleY = 1f,
            positionX = 0f,
            positionY = 0f,
        )
        NeXtepLog.info("task_surface", "Restored taskId=$taskId")
    }.onFailure { error ->
        NeXtepLog.error("task_surface", "Restore failed taskId=$taskId", error)
    }

    private fun applyPresentation(presentation: ActivePresentation) {
        applyPresentation(
            leash = presentation.leash,
            scaleX = presentation.scaleX,
            scaleY = presentation.scaleY,
            positionX = presentation.positionX,
            positionY = presentation.positionY,
        )
    }

    private fun animatePresentation(
        taskId: Int,
        from: ActivePresentation,
        to: ActivePresentation,
        fadeIn: Boolean,
    ) {
        cancelPresentationAnimation(taskId)
        applyPresentation(
            leash = to.leash,
            scaleX = from.scaleX,
            scaleY = from.scaleY,
            positionX = from.positionX,
            positionY = from.positionY,
        )
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = if (fadeIn) PRESENT_DURATION_MS else REPOSITION_DURATION_MS
        animator.interpolator = DecelerateInterpolator(1.6f)
        animator.addUpdateListener { valueAnimator ->
            if (activePresentations[taskId] !== to) {
                cancelPresentationAnimation(taskId)
                return@addUpdateListener
            }
            val progress = valueAnimator.animatedValue as Float
            runCatching {
                applyPresentation(
                    leash = to.leash,
                    scaleX = lerp(from.scaleX, to.scaleX, progress),
                    scaleY = lerp(from.scaleY, to.scaleY, progress),
                    positionX = lerp(from.positionX, to.positionX, progress),
                    positionY = lerp(from.positionY, to.positionY, progress),
                )
            }.onFailure { error ->
                valueAnimator.cancel()
                NeXtepLog.warn(
                    "task_surface_animation",
                    "Frame apply failed taskId=$taskId",
                    error,
                )
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (presentationAnimators.remove(taskId, animator) &&
                    activePresentations[taskId] === to
                ) {
                    runCatching { applyPresentation(to) }.onFailure { error ->
                        NeXtepLog.warn(
                            "task_surface_animation",
                            "Final frame apply failed taskId=$taskId",
                            error,
                        )
                    }
                }
            }
        })
        presentationAnimators[taskId] = animator
        animator.start()
    }

    private fun cancelPresentationAnimation(taskId: Int) {
        presentationAnimators.remove(taskId)?.cancel()
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    private fun applyPresentation(
        leash: Any,
        scaleX: Float,
        scaleY: Float,
        positionX: Float,
        positionY: Float,
    ) {
        val loader = hostClassLoader ?: error("SystemUI host class loader is unavailable")
        val transactionClass = Class.forName(
            "android.view.SurfaceControl\$Transaction",
            false,
            loader,
        )
        val transaction = transactionClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()
        try {
            clearCrop(transaction, leash)
            // WM/Launcher owns visibility during remote transitions. Writing alpha here
            // can resurrect an outgoing task or overwrite its native enter/exit fade.
            if (!invokeOptional(
                    transaction,
                    "setMatrix",
                    leash,
                    scaleX,
                    0f,
                    0f,
                    scaleY,
                )
            ) {
                invokeRequired(transaction, "setScale", leash, scaleX, scaleY)
            }
            invokeRequired(transaction, "setPosition", leash, positionX, positionY)
            invokeRequired(transaction, "apply")
        } finally {
            invokeOptional(transaction, "close")
        }
    }

    private fun clearCrop(transaction: Any, leash: Any) {
        val candidate = allMethods(transaction.javaClass).firstOrNull { method ->
            method.name in setOf("setWindowCrop", "setCrop") &&
                method.parameterTypes.size == 2 &&
                !method.parameterTypes[1].isPrimitive
        } ?: return
        runCatching {
            candidate.isAccessible = true
            candidate.invoke(transaction, leash, null)
        }.onFailure { error ->
            NeXtepLog.warn("task_surface", "Unable to clear task crop", error)
        }
    }

    private fun findTaskLeash(taskId: Int): Any? = runCatching {
        val loader = hostClassLoader ?: error("SystemUI host class loader is unavailable")
        val factoryClass = loader.loadClass(SYSTEM_UI_FACTORY)
        val initializer = findField(factoryClass, INITIALIZER_FIELD).get(null)
            ?: error("SystemUI initializer is null")
        val wmComponent = invokeRequired(initializer, "getWMComponent")
            ?: error("SystemUI WM component is null")
        val organizer = resolveTaskOrganizer(wmComponent)
            ?: error("ShellTaskOrganizer provider is unavailable")
        val tasks = findField(organizer.javaClass, TASKS_FIELD).get(organizer)
            ?: error("ShellTaskOrganizer task map is null")
        val lock = allFields(organizer.javaClass)
            .firstOrNull { it.name == LOCK_FIELD }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(organizer)
                }.getOrNull()
            }
            ?: organizer
        val appearedInfo = synchronized(lock) {
            invokeRequired(tasks, "get", taskId)
        } ?: error("Task $taskId is absent from ShellTaskOrganizer")
        invokeRequired(appearedInfo, "getLeash")
            ?: error("Task $taskId leash is null")
    }.onFailure { error ->
        NeXtepLog.warn("task_surface", "Task leash lookup failed taskId=$taskId", error)
    }.getOrNull()

    private fun resolveTaskOrganizer(wmComponent: Any): Any? {
        runCatching {
            val provider = findField(wmComponent.javaClass, ORGANIZER_PROVIDER_FIELD)
                .get(wmComponent)
            val organizer = provider?.let { invokeRequired(it, "get") }
            if (organizer != null && hasField(organizer.javaClass, TASKS_FIELD)) {
                return organizer
            }
        }

        allFields(wmComponent.javaClass).forEach { field ->
            val value = runCatching {
                field.isAccessible = true
                field.get(wmComponent)
            }.getOrNull() ?: return@forEach
            if (hasField(value.javaClass, TASKS_FIELD)) return value
            if (field.name.contains("TaskOrganizer", ignoreCase = true) ||
                value.javaClass.name.contains("Provider", ignoreCase = true)
            ) {
                val provided = runCatching { invokeRequired(value, "get") }.getOrNull()
                if (provided != null && hasField(provided.javaClass, TASKS_FIELD)) {
                    return provided
                }
            }
        }

        allMethods(wmComponent.javaClass)
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.name.contains("TaskOrganizer", ignoreCase = true)
            }
            .forEach { method ->
                val organizer = runCatching {
                    method.isAccessible = true
                    method.invoke(wmComponent)
                }.getOrNull()
                if (organizer != null && hasField(organizer.javaClass, TASKS_FIELD)) {
                    return organizer
                }
            }
        return null
    }

    private fun isValid(leash: Any): Boolean =
        (runCatching { invokeRequired(leash, "isValid") }.getOrNull() as? Boolean) == true

    private fun invokeRequired(target: Any, name: String, vararg arguments: Any?): Any? {
        val method = findCompatibleMethod(target.javaClass, name, arguments)
            ?: error("No compatible ${target.javaClass.name}#$name candidate")
        method.isAccessible = true
        return method.invoke(target, *arguments)
    }

    private fun invokeOptional(target: Any, name: String, vararg arguments: Any?): Boolean {
        val method = findCompatibleMethod(target.javaClass, name, arguments) ?: return false
        return runCatching {
            method.isAccessible = true
            method.invoke(target, *arguments)
        }.isSuccess
    }

    private fun findCompatibleMethod(
        startType: Class<*>,
        name: String,
        arguments: Array<out Any?>,
    ): Method? = allMethods(startType).firstOrNull { candidate ->
        candidate.name == name &&
            candidate.parameterTypes.size == arguments.size &&
            candidate.parameterTypes.indices.all { index ->
                val argument = arguments[index]
                if (argument == null) {
                    !candidate.parameterTypes[index].isPrimitive
                } else {
                    wraps(candidate.parameterTypes[index]).isAssignableFrom(argument.javaClass)
                }
            }
    }

    private fun findField(type: Class<*>, name: String): Field =
        allFields(type).firstOrNull { it.name == name }?.apply { isAccessible = true }
            ?: error("No field ${type.name}#$name")

    private fun hasField(type: Class<*>, name: String): Boolean =
        allFields(type).any { it.name == name }

    private fun allFields(startType: Class<*>): Sequence<Field> = sequence {
        var type: Class<*>? = startType
        while (type != null && type != Any::class.java) {
            yieldAll(type.declaredFields.asSequence())
            type = type.superclass
        }
    }

    private fun allMethods(startType: Class<*>): Sequence<Method> = sequence {
        var type: Class<*>? = startType
        while (type != null && type != Any::class.java) {
            yieldAll(type.declaredMethods.asSequence())
            type = type.superclass
        }
    }

    private fun wraps(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> type
    }

    private const val REAPPLY_WINDOW_MS = 2_000L
    private const val MIN_REAPPLY_INTERVAL_MS = 120L
    private const val STEADY_REAPPLY_INTERVAL_MS = 900L
    private const val PRESENT_DURATION_MS = 240L
    private const val REPOSITION_DURATION_MS = 260L
    private const val PRESENT_ENTER_SCALE = 0.96f
    private const val PRESENT_ENTER_OFFSET_PX = 10f
}
