package com.nextep.shell.systemui

import android.content.Context

object SystemUiContextResolver {
    fun fromHookTarget(target: Any?): Context? = when (target) {
        is Context -> target.applicationContext ?: target
        else -> null
    }
}
