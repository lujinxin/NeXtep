package io.github.lujinxin.nextep.config

import android.os.Bundle
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.graphics.Color
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = SettingsPalette.page(this)
        window.navigationBarColor = SettingsPalette.page(this)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val pages = listOf(SettingsScreen.create(this, SettingsRepository(this)), AboutScreen.create(this))
            .map { page -> ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(page)
            } }
        val pageHost = FrameLayout(this).apply {
            pages.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
        val tabs = TabLayout(this).apply {
            tabMode = TabLayout.MODE_FIXED
            setBackgroundColor(SettingsPalette.page(this@MainActivity))
            addTab(newTab().setText("设置"))
            addTab(newTab().setText("关于"))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    pages.forEachIndexed { index, view -> view.visibility = if (index == tab.position) View.VISIBLE else View.GONE }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        val initial = savedInstanceState?.getInt("settings_tab", 0)?.coerceIn(0, 1) ?: 0
        pages.forEachIndexed { index, view -> view.visibility = if (index == initial) View.VISIBLE else View.GONE }
        tabs.selectTab(tabs.getTabAt(initial))
        currentTabs = tabs
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SettingsPalette.page(this@MainActivity))
            addView(TextView(this@MainActivity).apply {
                text = "NeXtep"
                textSize = 26f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(SettingsPalette.text(this@MainActivity))
                setPadding(dp(24), dp(18), dp(24), dp(10))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(tabs, LinearLayout.LayoutParams(-1, dp(52)))
            addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(left = systemBars.left, right = systemBars.right,
                top = systemBars.top, bottom = maxOf(systemBars.bottom, ime.bottom))
            windowInsets
        }
        setContentView(root)
    }

    private var currentTabs: TabLayout? = null
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("settings_tab", currentTabs?.selectedTabPosition ?: 0)
        super.onSaveInstanceState(outState)
    }
}
