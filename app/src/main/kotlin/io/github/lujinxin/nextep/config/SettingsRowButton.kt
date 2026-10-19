package io.github.lujinxin.nextep.config

import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

internal object SettingsRowButton {
    fun create(activity: AppCompatActivity): MaterialButton {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        return MaterialButton(activity, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            minimumWidth = 0
            minWidth = 0
            maxWidth = dp(144)
            minHeight = dp(48)
            maxLines = 2
            gravity = Gravity.CENTER
            insetTop = dp(4)
            insetBottom = dp(4)
            cornerRadius = dp(12)
            setPaddingRelative(dp(12), paddingTop, dp(12), paddingBottom)
        }
    }
}
