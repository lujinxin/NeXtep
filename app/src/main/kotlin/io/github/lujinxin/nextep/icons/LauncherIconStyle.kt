package io.github.lujinxin.nextep.icons

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.lujinxin.nextep.R

internal enum class LauncherIconStyle(
    val key: String,
    @StringRes val label: Int,
    @DrawableRes val artwork: Int,
    val aliasName: String,
) {
    DEFAULT("default", R.string.launcher_icon_default,
        R.drawable.ic_launcher_square_artwork, "DefaultLauncher"),
    SPRING("spring", R.string.launcher_icon_spring,
        R.drawable.ic_launcher_spring_artwork, "SpringLauncher"),
    SUMMER("summer", R.string.launcher_icon_summer,
        R.drawable.ic_launcher_summer_artwork, "SummerLauncher"),
    AUTUMN("autumn", R.string.launcher_icon_autumn,
        R.drawable.ic_launcher_autumn_artwork, "AutumnLauncher"),
    WINTER("winter", R.string.launcher_icon_winter,
        R.drawable.ic_launcher_winter_artwork, "WinterLauncher");

    companion object {
        fun fromKey(key: String?): LauncherIconStyle? = entries.firstOrNull { it.key == key }
    }
}
