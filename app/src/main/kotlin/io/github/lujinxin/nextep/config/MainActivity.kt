package io.github.lujinxin.nextep.config

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import io.github.lujinxin.nextep.R
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private var navigation: GlassBottomNavigation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !SettingsPalette.isDark(this@MainActivity)
            isAppearanceLightNavigationBars = !SettingsPalette.isDark(this@MainActivity)
        }
        fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
        val pages = listOf(SettingsScreen.create(this, SettingsRepository(this)), AboutScreen.create(this))
            .mapIndexed { index, page -> ScrollView(this).apply {
                id = if (index == 0) R.id.settings_scroll else R.id.about_scroll
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                addView(page)
            } }
        val initial = savedInstanceState?.getInt("settings_tab", 0)?.coerceIn(pages.indices) ?: 0
        val pager = ViewPager(this).apply {
            id = R.id.configuration_pager
            offscreenPageLimit = 1
            adapter = object : PagerAdapter() {
                override fun getCount() = pages.size
                override fun isViewFromObject(view: View, item: Any) = view === item
                override fun instantiateItem(container: ViewGroup, position: Int): Any = pages[position].also {
                    container.addView(it)
                }
                override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                    container.removeView(item as View)
                }
            }
            setCurrentItem(initial, false)
        }
        // This layer contains only pages; the glass never records itself.
        val content = FrameLayout(this).apply {
            setBackgroundColor(SettingsPalette.page(this@MainActivity))
            addView(pager, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(SettingsPalette.page(this@MainActivity))
            clipChildren = false
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        var navigationTransition = false
        var pagerScrollState = ViewPager.SCROLL_STATE_IDLE
        val bottomNavigation = GlassBottomNavigation(this, content) { position ->
            navigationTransition = true
            pager.setCurrentItem(position, android.animation.ValueAnimator.areAnimatorsEnabled())
            if (pagerScrollState == ViewPager.SCROLL_STATE_IDLE) navigationTransition = false
        }
        navigation = bottomNavigation
        bottomNavigation.select(initial, animate = false)
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {
                if (!navigationTransition) bottomNavigation.followPageProgress(position + offset)
                bottomNavigation.refreshBackdrop()
            }
            override fun onPageSelected(position: Int) {
                bottomNavigation.select(position)
            }
            override fun onPageScrollStateChanged(state: Int) {
                pagerScrollState = state
                if (state == ViewPager.SCROLL_STATE_DRAGGING) navigationTransition = false
                if (state == ViewPager.SCROLL_STATE_IDLE) {
                    navigationTransition = false
                    bottomNavigation.select(pager.currentItem)
                }
            }
        })
        // Space above/below the 64dp bar lets the pressed 78dp lens grow without being cropped.
        val navigationHeight = dp(76) + (12 * resources.displayMetrics.scaledDensity).roundToInt()
        val navigationParams = FrameLayout.LayoutParams(
            dp(280), navigationHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        )
        root.addView(bottomNavigation, navigationParams)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val available = (root.width - root.paddingLeft - root.paddingRight).coerceAtLeast(0)
            if (available == 0) return@addOnLayoutChangeListener
            val pageParams = pager.layoutParams as FrameLayout.LayoutParams
            val pageWidth = minOf(available, dp(720))
            if (pageParams.width != pageWidth) {
                pageParams.width = pageWidth
                pager.layoutParams = pageParams
            }
            val barWidth = minOf((available - dp(48)).coerceAtLeast(0), dp(280))
            if (navigationParams.width != barWidth) {
                navigationParams.width = barWidth
                bottomNavigation.layoutParams = navigationParams
            }
            bottomNavigation.refreshBackdrop()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safe = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            view.updatePadding(left = safe.left, right = safe.right, top = safe.top, bottom = maxOf(safe.bottom, ime.bottom))
            bottomNavigation.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            pages.forEach { page ->
                page.updatePadding(bottom = if (keyboardVisible) dp(16) else navigationHeight + dp(16))
            }
            bottomNavigation.refreshBackdrop()
            windowInsets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("settings_tab", navigation?.selectedPosition ?: 0)
        super.onSaveInstanceState(outState)
    }
}
