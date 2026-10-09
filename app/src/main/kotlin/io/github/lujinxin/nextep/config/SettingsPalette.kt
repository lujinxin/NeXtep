package io.github.lujinxin.nextep.config

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.google.android.material.color.MaterialColors

internal object SettingsPalette {
    fun isDark(context: Context) = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun page(context: Context) = MaterialColors.getColor(context, android.R.attr.colorBackground, 0)
    fun surface(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0)
    fun primary(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0)
    fun text(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)
    fun secondary(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
    fun outline(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0)
    fun container(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0)
    fun glassTint(context: Context) = if (isDark(context)) Color.argb(102, 40, 40, 44) else Color.argb(102, 255, 255, 255)
}
