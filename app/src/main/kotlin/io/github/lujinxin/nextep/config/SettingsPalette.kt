package io.github.lujinxin.nextep.config

import android.content.Context
import com.google.android.material.color.MaterialColors

internal object SettingsPalette {
    fun page(context: Context) = MaterialColors.getColor(context, android.R.attr.colorBackground, 0)
    fun surface(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0)
    fun primary(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0)
    fun text(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)
    fun secondary(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
    fun outline(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0)
    fun container(context: Context) = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0)
}
