package io.github.lujinxin.nextep.framework

import android.app.ActivityManager
import android.graphics.Rect
import android.os.UserHandle
import io.github.lujinxin.nextep.logging.NeXtepLog

object TaskInfoCompat {
    data class WindowState(
        val displayId: Int,
        val bounds: Rect,
        val windowingMode: Int,
        val resizeMode: Int?,
        val densityDpi: Int,
        val token: Any?,
    )

    fun readWindowState(info: ActivityManager.RunningTaskInfo): WindowState? {
        val displayId = readInt(
            target = info,
            fieldNames = listOf("displayId", "mDisplayId"),
            methodNames = listOf("getDisplayId"),
        ) ?: return missing("displayId", info)

        val configuration = readMember(
            target = info,
            fieldNames = listOf("configuration", "mConfiguration"),
            methodNames = listOf("getConfiguration"),
        ) ?: return missing("configuration", info)

        val windowConfiguration = readMember(
            target = configuration,
            fieldNames = listOf("windowConfiguration", "mWindowConfiguration"),
            methodNames = listOf("getWindowConfiguration"),
        ) ?: return missing("windowConfiguration", info)

        val bounds = (invokeNoArg(windowConfiguration, listOf("getBounds")) as? Rect)
            ?.let(::Rect)
            ?: Rect()
        val windowingMode = readInt(
            target = windowConfiguration,
            fieldNames = listOf("windowingMode", "mWindowingMode"),
            methodNames = listOf("getWindowingMode"),
        ) ?: 0
        val resizeMode = readInt(
            target = info,
            fieldNames = listOf("resizeMode", "mResizeMode"),
            methodNames = listOf("getResizeMode"),
        )
        val densityDpi = readInt(
            target = configuration,
            fieldNames = listOf("densityDpi"),
            methodNames = listOf("getDensityDpi"),
        ) ?: return missing("densityDpi", info)
        val token = readMember(
            target = info,
            fieldNames = listOf("token", "mToken"),
            methodNames = listOf("getToken"),
        )

        return WindowState(displayId, bounds, windowingMode, resizeMode, densityDpi, token)
    }

    fun readUserId(info: ActivityManager.RunningTaskInfo): Int? = readInt(
        target = info,
        fieldNames = listOf("userId", "mUserId"),
        methodNames = listOf("getUserId"),
    )

    fun userIdentifier(userHandle: UserHandle): Int? = readInt(
        target = userHandle,
        fieldNames = listOf("mHandle", "identifier"),
        methodNames = listOf("getIdentifier"),
    )

    private fun readInt(
        target: Any,
        fieldNames: List<String>,
        methodNames: List<String>,
    ): Int? = (readMember(target, fieldNames, methodNames) as? Number)?.toInt()

    private fun readMember(
        target: Any,
        fieldNames: List<String>,
        methodNames: List<String>,
    ): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val currentType = type
            for (fieldName in fieldNames) {
                try {
                    val field = currentType.getDeclaredField(fieldName).apply { isAccessible = true }
                    return field.get(target)
                } catch (_: NoSuchFieldException) {
                    // Continue through candidates and the superclass chain.
                } catch (error: Throwable) {
                    NeXtepLog.warn(
                        "task_info_compat",
                        "Field ${currentType.name}#$fieldName failed",
                        error,
                    )
                }
            }
            type = currentType.superclass
        }
        return invokeNoArg(target, methodNames)
    }

    private fun invokeNoArg(target: Any, methodNames: List<String>): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val currentType = type
            for (methodName in methodNames) {
                try {
                    val method = currentType.getDeclaredMethod(methodName).apply { isAccessible = true }
                    return method.invoke(target)
                } catch (_: NoSuchMethodException) {
                    // Continue through candidates and the superclass chain.
                } catch (error: Throwable) {
                    NeXtepLog.warn(
                        "task_info_compat",
                        "Method ${currentType.name}#$methodName failed",
                        error,
                    )
                }
            }
            type = currentType.superclass
        }
        return null
    }

    private fun missing(
        member: String,
        info: ActivityManager.RunningTaskInfo,
    ): WindowState? {
        NeXtepLog.warn(
            "task_info_compat",
            "No $member candidate taskId=${info.taskId} runtime=${info.javaClass.name}",
        )
        return null
    }
}
