package io.github.lujinxin.nextep.config

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.ChipGroup
import io.github.lujinxin.nextep.R

internal object AcknowledgementsSection {
    fun create(activity: AppCompatActivity): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val names = activity.resources.getStringArray(R.array.acknowledgement_names)
            .map(String::trim).filter(String::isNotEmpty).distinct()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
            addView(TextView(activity).apply {
                text = "致谢"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(SettingsPalette.text(activity))
            })
            addView(TextView(activity).apply {
                text = "感谢为 NeXtep 提出建议、帮助测试的朋友。"
                textSize = 14f
                setTextColor(SettingsPalette.secondary(activity))
                setPadding(0, dp(8), 0, dp(8))
            })
            addView(ChipGroup(activity).apply {
                isSingleLine = false
                chipSpacingHorizontal = dp(6)
                chipSpacingVertical = dp(4)
                names.forEachIndexed { index, name ->
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        if (index > 0) addView(TextView(activity).apply {
                            text = "●"
                            textSize = 8f
                            setTextColor(SettingsPalette.secondary(activity))
                            setPadding(0, 0, dp(4), 0)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        })
                        addView(TextView(activity).apply {
                            text = name
                            textSize = 12f
                            gravity = Gravity.CENTER
                            includeFontPadding = false
                            minimumHeight = dp(24)
                            setPadding(dp(10), dp(3), dp(10), dp(3))
                            setTextColor(Color.rgb(35, 53, 64))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(100).toFloat()
                                // Stable per ID, so colors remain calm when reopening the page.
                                setColor(pastelColors[Math.floorMod(name.hashCode(), pastelColors.size)])
                            }
                        })
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private val pastelColors = intArrayOf(
        Color.rgb(218, 235, 247), Color.rgb(223, 239, 222), Color.rgb(250, 233, 208),
        Color.rgb(237, 225, 247), Color.rgb(249, 224, 231), Color.rgb(215, 239, 236),
    )
}
