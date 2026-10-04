package io.github.lujinxin.nextep.config

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
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
            setPadding(dp(24), dp(24), dp(24), dp(32))
            addView(ImageView(activity).apply {
                val source = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_artwork)
                val cropped = android.graphics.Bitmap.createBitmap(source, source.width / 6, source.height / 6,
                    source.width * 2 / 3, source.height * 2 / 3)
                setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, cropped))
                if (cropped !== source) source.recycle()
                contentDescription = "NeXtep 图标"
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(TextView(activity).apply {
                text = "多任务，再进一步。"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(SettingsPalette.text(activity))
                setPadding(0, dp(12), 0, 0)
            })
            addView(paragraph("向 OneStep 致敬，在原生 ColorOS 桌面上延续侧边多任务的体验。"))
            addView(TextView(activity).apply {
                text = "一个主窗口，三个小窗"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(SettingsPalette.text(activity))
                setPadding(0, dp(8), 0, 0)
            })
            addView(paragraph("并排处理不同应用，拖动交换窗口，从后台卡片连续添加应用。把小窗拖到顶部控制区，即可移回后台。顶部还提供媒体控制、常用应用和自定义文字等功能。"))
            addView(paragraph("本项目通过 LSPosed 接入系统界面，保留手机原有桌面。系统升级后，部分功能可能需要适配。", 13f))
            addView(View(activity).apply { setBackgroundColor(SettingsPalette.outline(activity)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
            addView(UpdateSection.create(activity))
            addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "查看项目源码"
                minHeight = dp(48)
                setOnClickListener { UpdateSection.openBrowser(activity, "https://github.com/lujinxin/NeXtep") }
            }, LinearLayout.LayoutParams(-1, -2))
            addView(paragraph("开源协议 · Apache License 2.0\n作者 · lujinxin (Jensen Lu)", 12f))
        }
    }
}
