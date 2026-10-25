package io.github.lujinxin.nextep.icons

import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager

/** Night artwork is a presentation override; it never changes a launcher component. */
internal object AppIconAppearance {
    fun usesNightIcon(context: Context, configuration: Configuration = context.resources.configuration): Boolean {
        val dark = configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return dark || context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }
}
