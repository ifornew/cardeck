package io.github.shaonialife.cardeck.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.launcher.settings.ThemePrefs
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * CarPlay dock（独立虚拟屏，含时钟/电量/运行中应用/回桌面）。
 *
 * 两种形态（布局见 res/layout）：
 *  - 横屏车机：dock 是左侧竖条 → activity_dock_sidebar.xml（时钟居顶、回桌面居底）
 *  - 竖屏车机：dock 是底部横条 → activity_dock_bar.xml（⌂ | 应用横排 | 时间/电量）
 * 形态由 dock 虚拟屏自身宽高决定（竖条 height>width；横条 width>height），与车机方向自动对齐。
 */
class DockActivity : AppCompatActivity() {
    private var appsContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var batteryView: BatteryGlyphView? = null
    private var signalView: LinearLayout? = null

    /** 状态行（信号 + 电池）整体：按「时钟文字宽度」等比缩放（见 alignStatusToClock）。 */
    private var statusView: View? = null
    private var barForm = false

    /**
     * 设计基准密度（420dpi）的当前倍率。dock 屏密度由 SessionController 按画布短边等比
     * 下发（奥迪 1160×540→420 不变；测试台 2320×1080→840，dock 与文字/图标整体 ×2）。
     * 布局 XML 用 dp 自动随密度缩放；字号/模糊半径等按「设计 px × 本倍率」在代码里设，
     * 保证高分辨率车机下 dock 内容与画布同比例（2026-10-17 修复「高分辨率下显得小」）。
     */
    private val designScale: Float
        get() = resources.displayMetrics.densityDpi / 420f

    private val refreshListener: () -> Unit = { runOnUiThread { refresh() } }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                refresh()
                updateSignal()
                pollHandler.postDelayed(this, 3_000)
            }
        }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker =
        object : Runnable {
            override fun run() {
                // 无秒显示（用户要求）：对齐到下一分钟边界刷新，秒内不做无用重绘
                clockView?.text =
                    android.text.format.DateFormat
                        .format("HH:mm", System.currentTimeMillis())
                clockView?.let { fitClockText(it) }
                val now = System.currentTimeMillis()
                clockHandler.postDelayed(this, 60_000L - now % 60_000L + 80L)
            }
        }

    /** 壁纸背景（全流画布切片，与桌面同源同路径——交界处逐像素连续）。 */
    private var wallpaperView: ImageView? = null
    private var wallpaperShownVersion = -1L

    /** 壁纸变更监听（WallpaperStore 主线程回调；解码在本类自行切后台线程）。 */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    /** dock 外观偏好监听（设置页拖动滑条即时生效）。 */
    private val dockPrefsListener: () -> Unit = { runOnUiThread { applyDockPrefs() } }

    /** dock 主页按钮（布局 inflate 后赋值；模式监听用它换图标）。 */
    private var dockHomeView: ImageView? = null

    /**
     * 「仪表盘 / 应用网格」切换时换 dock 主页按钮图标（原生 CarPlay 语义）：
     *  - 仪表盘激活 → 3×3 小圆角方块（点按回应用网格）
     *  - 应用网格/应用内 → 单个空心圆角方块（点按进仪表盘）
     */
    private val dockModeListener: (Boolean) -> Unit = { dashboard ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                dockHomeView?.setImageResource(
                    if (dashboard) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
                )
            }
        }
    }

    /** 「自动」档下手机系统深浅色变化：昼夜资源不一致时重建（虚拟屏不自动收到该变化）。 */
    private val themeNightListener: (Boolean) -> Unit = { _ ->
        runOnUiThread {
            if (!isFinishing &&
                !isDestroyed &&
                ThemePrefs.isNight(this) != ThemePrefs.targetNight(this)
            ) {
                recreate()
            }
        }
    }

    private val batteryReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = updateBattery(intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePrefs.applyToActivity(this)
        ThemePrefs.addNightListener(themeNightListener)
        val dm = resources.displayMetrics
        barForm = dm.widthPixels > dm.heightPixels
        setContentView(if (barForm) R.layout.activity_dock_bar else R.layout.activity_dock_sidebar)

        clockView = findViewById(R.id.dockClock)
        batteryView = findViewById(R.id.dockBattery)
        signalView = findViewById(R.id.dockSignal)
        statusView = findViewById(R.id.dockStatus)
        appsContainer = findViewById(R.id.dockApps)
        wallpaperView = findViewById(R.id.dockWallpaper)
        findViewById<ImageView>(R.id.dockHome).apply {
            dockHomeView = this
            setOnClickListener { Bg.run("dock-home") { VirtualLauncher.onHomePressed() } }
            // 图标随模式切换：仪表盘激活=3×3 网格（点按回应用网格）；
            // 否则=单个空心圆角方块（点按进仪表盘）。与原生 CarPlay 左下角按钮一致。
            setImageResource(
                if (VirtualLauncher.dashboardMode) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
            )
        }
        VirtualLauncher.addDashboardListener(dockModeListener)

        VirtualLauncher.addRunningAppsListener(refreshListener)
        pollHandler.postDelayed(pollRunnable, 3_000)
        clockHandler.post(clockTicker)
        // 字号按「设计 px × designScale」设置（不进 XML：荣耀 ROM 上 autoSize 对窄 dock
        // 不生效，仍需布局完成后按实测宽度手工缩钟表——见 fitClockText；13/22 为原型值）
        clockView?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 22f * designScale)
        clockView?.post { fitClockText(clockView!!) }
        setupWallpaper()
        // 注册变更通知 + 立即对账一次（兜底注册前发生的更换——事件不补发）
        WallpaperStore
            .addListener(wallpaperListener)
        refreshWallpaperIfChanged()
        // dock 外观（暗幕/模糊）：应用当前值 + 监听设置页调整
        DockPrefs.addListener(dockPrefsListener)
        applyDockPrefs()
        // 系统受保护广播：ACTION_BATTERY_CHANGED 为 sticky，注册即回填当前电量
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        VirtualLauncher.removeRunningAppsListener(refreshListener)
        VirtualLauncher.removeDashboardListener(dockModeListener)
        pollHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacks(clockTicker)
        WallpaperStore
            .removeListener(wallpaperListener)
        DockPrefs.removeListener(dockPrefsListener)
        ThemePrefs.removeNightListener(themeNightListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateSignal()
    }

    /**
     * 壁纸切片：dock 窗口内画「全流画布壁纸」中本条带对应的部分。
     * dock 虚拟屏与流 1:1（420dpi），canvas 视图 = 流画布尺寸：
     *  - 侧栏（横屏车机）：流左条，偏移 (0, 0)
     *  - 底栏（竖屏车机）：流底条，偏移 (0, -(appHeight))
     * 窗口必须不透明：半透明窗口下系统会往这块虚拟屏合成手机自身壁纸
     * （曾致 dock 显示手机壁纸、与桌面壁纸错位——2026-10-12 实测根因）。
     */
    private fun setupWallpaper() {
        val launcher = VirtualLauncher
        val streamW = launcher.streamWidthPx
        val streamH = launcher.streamHeightPx
        if (streamW <= 0 || streamH <= 0) return
        val view = wallpaperView ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (barForm) {
            params.topMargin = -(streamH - launcher.dockHeightPx)
        } else {
            params.leftMargin = 0
            params.topMargin = 0
        }
        params.width = streamW
        params.height = streamH
        view.layoutParams = params
        refreshWallpaperIfChanged()
    }

    /**
     * 应用 dock 外观偏好：暗幕强度（scrim View 的黑 alpha）+ 背景高斯模糊
     * （RenderEffect，API 31+；半径按 dock 物理像素，最大 40px——乘 designScale
     * 保证高分辨率车机上模糊观感与奥迪基准一致）。
     *
     * 2026-10-26 CarPlay 化：暗幕改为圆角面板；2026-10-27 用户要求 dock 紧贴屏幕
     * 左缘并与右侧内容区相接，面板改为**通栏直角**（XML 外边距 0）。
     */
    private fun applyDockPrefs() {
        val scrim = findViewById<View>(R.id.dockScrim) ?: return
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        // 暗幕基色随主题：白天压白、夜间压黑（否则白底壁纸被黑幕压暗，深色文字看不清）
        val base = androidx.core.content.ContextCompat.getColor(this, R.color.car_scrim)
        val border = androidx.core.content.ContextCompat.getColor(this, R.color.car_divider)
        scrim.background =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 0f
                setColor(
                    android.graphics.Color.argb(
                        alpha,
                        android.graphics.Color.red(base),
                        android.graphics.Color.green(base),
                        android.graphics.Color.blue(base),
                    ),
                )
                setStroke((1f * designScale).toInt().coerceAtLeast(1), border)
            }
        val wallpaper = wallpaperView ?: return
        val percent = DockPrefs.blur(this)
        if (percent <= 0) {
            wallpaper.setRenderEffect(null)
        } else {
            val radius = percent / 100f * 40f * designScale
            wallpaper.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    /** 壁纸版本变化时后台重解码并换图（监听器模式下与桌面同步更新）。 */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val current = WallpaperStore.version
        if (current == wallpaperShownVersion) return
        wallpaperShownVersion = current
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        Bg.run("wallpaper-dock") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@DockActivity,
                        params.width,
                        params.height,
                    )
                }.getOrNull()
            if (bitmap != null) {
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-DOCK: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params.width}x${params.height} top=${params.topMargin}",
                )
                runOnUiThread { view.setImageBitmap(bitmap) }
            } else {
                VirtualLauncher.logSink?.invoke("WALLPAPER-DOCK: 解码失败")
            }
        }
    }

    /** 逐级缩小字号直至文本宽度放得下（设计 px × designScale；行程 22→12，对照设计原型）。 */
    private fun fitClockText(view: TextView) {
        val available = view.width - view.paddingLeft - view.paddingRight
        if (available <= 0) return
        val scale = designScale
        var size = 22f * scale
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        val paint = view.paint
        while (size > 12f * scale && paint.measureText(view.text.toString()) > available) {
            size -= 0.5f * scale
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        }
        alignStatusToClock(view)
    }

    /**
     * 状态行（信号 + 电池）总宽对齐时钟文字宽（2026-10-28 用户要求）。
     *
     * 用等比缩放而非改布局尺寸：字形不变形，且不动 LinearLayout 的高度（不会挤动
     * 下方应用列表）。pivot 取行中心 → 放大后仍居中在时钟正下方。
     * 目标宽度含 letterSpacing 近似（paint.measureText 不计字间距）。
     */
    private fun alignStatusToClock(clock: TextView) {
        val row = statusView ?: return
        if (row.width <= 0 || row.height <= 0) {
            row.post { if (!isFinishing && !isDestroyed) alignStatusToClock(clock) }
            return
        }
        val text = clock.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val glyphWidth = clock.paint.measureText(text)
        val letterSpacing = clock.letterSpacing * clock.textSize * (text.length - 1).coerceAtLeast(0)
        // ×0.94：实测与时间等宽时，电池描边 + 信号条外缘视觉上仍略超出时间一截
        // （2026-10-28 用户复看），收一点让整体落在时间宽度之内。
        val target = (glyphWidth + letterSpacing) * 0.94f
        val scale = (target / row.width).coerceIn(0.8f, 2.4f)
        row.pivotX = row.width / 2f
        row.pivotY = row.height / 2f
        row.scaleX = scale
        row.scaleY = scale
    }

    /**
     * 电量/充电状态 → 原生 CarPlay 电池字形（见 [BatteryGlyphView]）：
     * 原生状态栏不显示百分比；平时单色，充电 iOS 绿 + 电池内部闪电，≤20% iOS 红。
     */
    private fun updateBattery(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        runOnUiThread {
            batteryView?.apply {
                this.percent = percent
                this.charging = charging
            }
        }
    }

    /**
     * dock 状态栏信号区（对照原生 CarPlay）：4 格圆角信号条 = 蜂窝信号强度
     * （读不到时全部保持暗淡，不伪造格数）。
     *
     * Wi-Fi 图标已移除（2026-10-28 用户反馈「比信号/电池小、也没信息量」）：
     * 车机热点是投射的前提，投影期间它必然处于连接状态，图标永远点亮 → 无信息量；
     * 状态行只保留信号 + 电池。
     */
    private fun updateSignal() {
        val container = signalView
        val level =
            runCatching {
                val tm = getSystemService(android.telephony.TelephonyManager::class.java)
                (tm?.signalStrength?.level ?: 0) + 1
            }.getOrDefault(0)
        runOnUiThread {
            container?.let {
                for (i in 0 until it.childCount) {
                    it.getChildAt(i).alpha = if (i < level) 1f else 0.25f
                }
            }
        }
    }

    private var lastRendered: List<String> = emptyList()

    private fun refresh() {
        val container = appsContainer ?: return
        val current =
            synchronized(VirtualLauncher.runningApps) {
                VirtualLauncher.runningApps.map { it.flattenToString() }
            }
        if (current == lastRendered) return
        lastRendered = current
        container.removeAllViews()
        val density = resources.displayMetrics.density
        // dock 内宽 ≈ 28.2dp（侧栏 32dp 减去左右 padding 1.9dp×2；底栏高 32dp 减去
        // 上下 padding）。图标盒 = iconPx + 2×2dp 内边距（点击热区），取 24dp 时
        // 盒宽 28dp 正好放进 32dp 窄条（2026-10-28 dock 再收窄：42→36→32dp）。
        val iconPx = (24 * density).toInt()
        val pad = (2 * density).toInt()
        // 图标间距 6dp→3dp（2026-10-28 用户反馈「间隔大了点」：盒内还有各 2dp 点击热区，
        // 视觉间距 ≈ 3 + 4 = 7dp）
        val gap = (3 * density).toInt()
        val apps = synchronized(VirtualLauncher.runningApps) { VirtualLauncher.runningApps.toList() }
        val iconCache = VirtualLauncher.iconCache
        apps.forEachIndexed { index, component ->
            val drawable = iconCache[component] ?: packageManager.defaultActivityIcon
            // 位图化渲染（与桌面网格同一路径）：直接 setImageDrawable 会让 ImageView
            // 长期持有共享 Drawable 实例，其 bounds 被网格重绑改写后渲染错位
            // （实机故障：点 home 后 dock 图标变小且偏左）
            val bitmap = AppIcons.rounded(drawable, iconPx)
            container.addView(
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = component.shortClassName
                    setOnClickListener {
                        Bg.run("launch-app") { VirtualLauncher.launchApp(component) }
                    }
                    // 长按关闭：清掉车机侧任务（不杀进程）并移出 dock 列表
                    setOnLongClickListener {
                        Bg.run("close-app") { VirtualLauncher.closeApp(component) }
                        true
                    }
                },
                LinearLayout
                    .LayoutParams(iconPx + pad * 2, iconPx + pad * 2)
                    .apply {
                        if (index > 0) {
                            if (barForm) marginStart = gap else topMargin = gap
                        }
                    },
            )
        }
    }

    override fun onBackPressed() = Unit // dock 常驻，不响应返回
}
