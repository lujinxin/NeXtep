package com.nextep.shell.framework

import android.graphics.Rect
import com.nextep.shell.logging.NeXtepLog
import java.lang.reflect.Method

object WindowContainerTransactionCompat {
    fun applyTaskLayout(
        token: Any,
        bounds: Rect,
        densityDpi: Int,
        windowingMode: Int,
    ): Result<Unit> = runCatching {
        val transactionClass = Class.forName("android.window.WindowContainerTransaction")
        val transaction = transactionClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()

        invoke(transaction, "setWindowingMode", token, windowingMode)
        invoke(transaction, "setBounds", token, Rect(bounds))
        invoke(transaction, "setDensityDpi", token, densityDpi)

        val organizerClass = Class.forName("android.window.WindowOrganizer")
        val organizer = organizerClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()
        invoke(organizer, "applyTransaction", transaction)
        NeXtepLog.info(
            "window_container_transaction",
            "Applied bounds=$bounds densityDpi=$densityDpi windowingMode=$windowingMode",
        )
    }.onFailure { error ->
        NeXtepLog.error("window_container_transaction", "Task layout transaction failed", error)
    }

    private fun invoke(target: Any, name: String, vararg arguments: Any): Any? {
        val method = findCompatibleMethod(target.javaClass, name, arguments)
            ?: error("No compatible ${target.javaClass.name}#$name candidate")
        return method.invoke(target, *arguments)
    }

    private fun findCompatibleMethod(
        startType: Class<*>,
        name: String,
        arguments: Array<out Any>,
    ): Method? {
        var type: Class<*>? = startType
        while (type != null) {
            val method = type.declaredMethods.firstOrNull { candidate ->
                candidate.name == name &&
                    candidate.parameterTypes.size == arguments.size &&
                    candidate.parameterTypes.indices.all { index ->
                        wraps(candidate.parameterTypes[index])
                            .isAssignableFrom(arguments[index].javaClass)
                    }
            }
            if (method != null) return method.apply { isAccessible = true }
            type = type.superclass
        }
        return null
    }

    private fun wraps(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> type
    }
}
