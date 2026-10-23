package io.github.lujinxin.nextep.config

import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import io.github.lujinxin.nextep.R

internal object AboutScreen {
    fun create(activity: AppCompatActivity): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        fun paragraph(value: String, size: Float = 14f) = TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(SettingsPalette.secondary(activity))
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(12))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.navigation_about)
                textSize = 28f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(SettingsPalette.text(activity))
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_launcher_artwork)
                contentDescription = activity.getString(R.string.app_icon_description)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.about_tagline)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(SettingsPalette.text(activity))
                setPadding(0, dp(12), 0, 0)
            })
            addView(paragraph(activity.getString(R.string.about_tribute)))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.about_windows_title)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(SettingsPalette.text(activity))
                setPadding(0, dp(8), 0, 0)
            })
            addView(paragraph(activity.getString(R.string.about_features)))
            addView(paragraph(activity.getString(R.string.about_compatibility), 13f))
            addView(View(activity).apply { setBackgroundColor(SettingsPalette.outline(activity)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
            addView(UpdateSection.create(activity))
            addView(View(activity).apply { setBackgroundColor(SettingsPalette.outline(activity)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
            addView(AcknowledgementsSection.create(activity))
            addView(paragraph(activity.getString(R.string.about_credits), 12f))
        }
    }
}
