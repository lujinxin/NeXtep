package io.github.lujinxin.nextep.launcher

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/** Keep workspace animation overrides out of native rotation and app-opening transitions. */
internal object LauncherAnimationPolicy {
    private data class TargetFields(val mode: Field, val activityType: Field, val rotationChange: Field)
    private val targetFields = ConcurrentHashMap<Class<*>, TargetFields>()

    fun matchesViewport(
        recordedRotation: Int,
        displayRotation: Int,
        recordedWidth: Int,
        recordedHeight: Int,
        rootWidth: Int,
        rootHeight: Int,
    ): Boolean = recordedRotation in 0..3 && recordedRotation == displayRotation &&
        recordedWidth > 0 && recordedHeight > 0 && rootWidth > 0 && rootHeight > 0 &&
        (recordedWidth > recordedHeight) == (rootWidth > rootHeight)

    fun canOverrideClose(targets: Any?): Boolean = runCatching {
        val apps = targets as? Array<*> ?: return@runCatching false
        if (apps.isEmpty()) return@runCatching false
        var hasClosingApp = false
        for (target in apps) {
            target ?: return@runCatching false
            // Read the OEM target's immutable metadata. Missing/changed fields retain
            // the native decision rather than opting an unknown transition into it.
            val fields = targetFields.getOrPut(target.javaClass) {
                fun intField(name: String) = target.javaClass.getField(name).apply {
                    check(type == Int::class.javaPrimitiveType)
                    isAccessible = true
                }
                TargetFields(intField("mode"), intField("activityType"), intField("rotationChange"))
            }
            if (fields.rotationChange.getInt(target) != 0) return@runCatching false
            when {
                fields.mode.getInt(target) == MODE_CLOSING &&
                    fields.activityType.getInt(target) == ACTIVITY_TYPE_STANDARD -> hasClosingApp = true
                fields.mode.getInt(target) == MODE_OPENING &&
                    fields.activityType.getInt(target) == ACTIVITY_TYPE_HOME -> Unit
                else -> return@runCatching false
            }
        }
        hasClosingApp
    }.getOrDefault(false)

    private const val MODE_OPENING = 0
    private const val MODE_CLOSING = 1
    private const val ACTIVITY_TYPE_STANDARD = 1
    private const val ACTIVITY_TYPE_HOME = 2
}
