package io.github.shaonialife.cardeck.launcher

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.github.shaonialife.cardeck.PhoneSettingsActivity
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.launcher.settings.DefaultMapPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.DesktopPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.DisplayPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.DockPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.OrientationPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.SettingsMenus
import io.github.shaonialife.cardeck.launcher.settings.SettingsToastHost
import io.github.shaonialife.cardeck.launcher.settings.SupersamplePanelFragment
import io.github.shaonialife.cardeck.launcher.settings.ThemePanelFragment
import io.github.shaonialife.cardeck.launcher.settings.ThemePrefs

/**
 * 车机设置（双栏宿主：左菜单 / 右内容 Fragment 容器）。
 * 内容页以 Fragment 实现（settings 包），车机双栏与手机侧二级菜单共用同一套面板。
 */
class SettingsActivity :
    AppCompatActivity(),
    SettingsToastHost {
    private var toastView: TextView? = null
    private val menus = mutableListOf<LinearLayout>()
    private var currentMenu = -1

    /** 「自动」档下手机系统深浅色变化：昼夜资源不一致时重建（虚拟屏不自动收到该变化）。 */
    private val themeNightListener: (Boolean) -> Unit = { _ ->
        if (!isFinishing && !isDestroyed && ThemePrefs.isNight(this) != ThemePrefs.targetNight(this)) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePrefs.applyToActivity(this)
        ThemePrefs.addNightListener(themeNightListener)
        if (VirtualLauncher.dockAtBottom) {
            // 竖屏窄屏：双栏挤压——转发手机侧二级菜单（列表 → 内容页）
            startActivity(
                android.content.Intent(
                    this,
                    PhoneSettingsActivity::class.java,
                ),
            )
            finish()
            return
        }
        setContentView(R.layout.activity_settings)
        toastView = findViewById(R.id.settingsToast)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { close() }

        collectMenus()
        if (savedInstanceState == null) showMenu(0) else restoreCurrentMenu()
    }

    /** 从 XML 菜单条目收集（按 id 顺序即菜单顺序），并改成 iOS/CarPlay 逐行列表。 */
    private fun collectMenus() {
        val ids =
            intArrayOf(
                R.id.menuDesktopApps,
                R.id.menuSupersample,
                R.id.menuOrientation,
                R.id.menuDock,
                R.id.menuDefaultMap,
                R.id.menuDisplay,
                R.id.menuTheme,
            )
        ids.forEach { id -> menus.add(findViewById<LinearLayout>(id)) }
        val density = resources.displayMetrics.density
        val container = findViewById<LinearLayout>(R.id.settingsMenu)
        // 行高 46dp、文案撑满剩余宽度、右侧箭头、行间 1px 分隔线（左缘与文案对齐）
        menus.forEachIndexed { index, menu ->
            menu.layoutParams =
                (menu.layoutParams as LinearLayout.LayoutParams).apply {
                    height = (46 * density).toInt()
                    topMargin = if (index == 0) (7 * density).toInt() else 0
                }
            for (c in 0 until menu.childCount) {
                val child = menu.getChildAt(c)
                if (child is TextView) {
                    child.layoutParams =
                        (child.layoutParams as LinearLayout.LayoutParams).apply {
                            width = 0
                            weight = 1f
                        }
                }
            }
            menu.addView(
                android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    setColorFilter(getColor(R.color.car_text_tertiary))
                    tag = CHEVRON_TAG
                    layoutParams =
                        LinearLayout
                            .LayoutParams((14 * density).toInt(), (14 * density).toInt())
                            .apply { marginStart = (4 * density).toInt() }
                },
            )
            menu.setOnClickListener { showMenu(index) }
            if (index > 0) {
                // 分隔线插在本行之前（每行 index 现场取，避免插入后下标漂移）
                container.addView(
                    android.view.View(this).apply {
                        setBackgroundColor(getColor(R.color.car_divider))
                    },
                    container.indexOfChild(menu),
                    LinearLayout
                        .LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (0.5f * density).toInt().coerceAtLeast(1))
                        .apply { marginStart = (41 * density).toInt() },
                )
            }
        }
    }

    /** 菜单切换：选中态样式 + 替换右栏 Fragment。 */
    private fun showMenu(index: Int) {
        if (index == currentMenu) {
            applyMenuStyle(index)
            return
        }
        currentMenu = index
        applyMenuStyle(index)
        val fragment = fragmentFor(index)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settingsContainer, fragment)
            .commit()
    }

    private fun restoreCurrentMenu() {
        currentMenu = -1
        val restored = supportFragmentManager.findFragmentById(R.id.settingsContainer)
        val index =
            when (restored) {
                is DesktopPanelFragment -> SettingsMenus.DESKTOP
                is SupersamplePanelFragment -> SettingsMenus.SUPERSAMPLE
                is OrientationPanelFragment -> SettingsMenus.ORIENTATION
                is DockPanelFragment -> SettingsMenus.DOCK
                is DefaultMapPanelFragment -> SettingsMenus.DEFAULT_MAP
                is DisplayPanelFragment -> SettingsMenus.DISPLAY
                is ThemePanelFragment -> SettingsMenus.THEME
                else -> 0
            }
        showMenu(index)
    }

    private fun applyMenuStyle(index: Int) {
        val ripple =
            runCatching {
                val out = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                getDrawable(out.resourceId)
            }.getOrNull()
        menus.forEachIndexed { i, menu ->
            val selected = i == index
            menu.background = if (selected) getDrawable(R.drawable.bg_menu_item) else ripple
            for (c in 0 until menu.childCount) {
                val child = menu.getChildAt(c)
                if (child is TextView) {
                    child.setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            this@SettingsActivity,
                            // 选中行是蓝底（CarPlay/iOS 设置），文字必须用白字；未选中用三级文字
                            if (selected) R.color.car_on_accent else R.color.car_text_tertiary,
                        ),
                    )
                    child.paint.isFakeBoldText = selected
                } else if (child is android.widget.ImageView) {
                    if (child.tag == CHEVRON_TAG) {
                        // 右侧箭头只是指示器：不套图标片，颜色跟随选中态
                        child.setColorFilter(
                            getColor(if (selected) R.color.car_on_accent else R.color.car_text_tertiary),
                        )
                    } else {
                        // 选中时去掉蓝色图标片——蓝片叠在蓝底上会整块消失（白线图标直接压在蓝底上）
                        child.background =
                            if (selected) {
                                null
                            } else {
                                androidx.core.content.ContextCompat.getDrawable(
                                    this@SettingsActivity,
                                    R.drawable.bg_menu_chip,
                                )
                            }
                    }
                }
            }
        }
    }

    private fun fragmentFor(index: Int): Fragment = SettingsMenus.createFragment(index)

    private fun close() {
        finish()
    }

    override fun onBackPressed() = close()

    /** 页内轻提示（不用系统 Toast：虚拟屏上显示位置不可控）。 */
    override fun showToast(text: String) {
        val view = toastView ?: return
        view.text = text
        view.animate().cancel()
        view.alpha = 0f
        view
            .animate()
            .alpha(1f)
            .setDuration(160)
            .withEndAction {
                view.postDelayed({
                    view
                        .animate()
                        .alpha(0f)
                        .setDuration(220)
                        .start()
                }, 1_250)
            }.start()
    }

    override fun onDestroy() {
        ThemePrefs.removeNightListener(themeNightListener)
        super.onDestroy()
    }

    private companion object {
        /** 设置行右侧箭头 ImageView 的标记（applyMenuStyle 不把它当图标片处理）。 */
        const val CHEVRON_TAG = "chevron"
    }
}
