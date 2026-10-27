package io.github.shaonialife.cardeck.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.input.KnobNav
import io.github.shaonialife.cardeck.launcher.settings.ThemePrefs
import io.github.shaonialife.cardeck.media.MediaSessionsState
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.render.SPLIT_MAP_WIDTH_RATIO
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * CarPlay 主区桌面：纯黑背景 + 图标分页网格（横屏 4×3 CarPlay 规格；竖屏按格子
 * 高宽比自适应、最多 6 行，见 computeCellMetrics）+ 底部页点。
 * 布局声明在 res/layout（activity_grid / item_grid_page / item_app_cell），
 * 代码只做动态格子尺寸计算与数据绑定。无字母导航（对照 CarPlay 主屏）。
 *
 * 默认只显示地图类应用（DesktopApps 默认选择），第一格固定「车机设置」入口；
 * 用户可在设置页勾选要显示的应用，返回桌面（onResume）即时刷新。
 */
class GridActivity : AppCompatActivity() {
    /** 桌面格子：仪表盘页（第 0 页）/ 固定设置入口 / 普通应用。 */
    private sealed interface Cell {
        object Dashboard : Cell

        object Pinned : Cell

        data class App(
            val entry: VirtualLauncher.AppEntry,
        ) : Cell
    }

    private var pages: List<List<Cell>> = emptyList()
    private var pager: ViewPager2? = null
    private var dotsRow: LinearLayout? = null
    private var cellWidthPx = 0
    private var cellHeightPx = 0
    private var iconSizePx = 0

    /** 格子间距（px）：格子尺寸从可用宽高里扣除 gap，避免图标贴在一起（2026-10-25 用户要求）。 */
    private var cellGapPx = 0

    /** 当前行数 / 列数（按屏幕尺寸自适应，分页容量 = columns × rows）。 */
    private var rows = ROWS_MIN
    private var columns = COLUMNS_DEFAULT

    /**
     * 旋钮/方向键当前选中的格子下标（-1 = 没有光标）。
     *
     * 语义（2026-10-25 用户定）：iDrive 旋钮在**应用列表内**转动 → 移动选中格；
     * 光标越过列表边界、或当前页不是应用页（仪表盘页）→ 左右翻页。
     */
    private var selectedIndex = -1

    /** 高亮中的格子视图（换页/重建时按引用清掉）。 */
    private var highlightView: View? = null

    /**
     * 翻页后要落到的边界格：0 = 首格，-1 = 末格，[PENDING_NONE] = 不选。
     * ViewPager2 的选中回调在滚动落定后触发，所以翻页时的落点记在这里等回调消费。
     */
    private var pendingSelectBoundary = PENDING_NONE

    /** 应用清单缓存（每次 resume 重扫 packageManager 会拖慢回桌面——仅在首次载入时扫描）。 */
    private var appsCache: List<VirtualLauncher.AppEntry>? = null

    /** 壁纸背景层（对齐全流画布的切片；版本变化时后台换图）。 */
    private var wallpaperView: ImageView? = null
    private var wallpaperShownVersion = -1L

    /** 壁纸变更监听（WallpaperStore 主线程回调；解码在本类自行切后台线程）。 */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    /** 仪表盘模式面板（分屏时显示在应用区右半；左半由地图 tile 覆盖）。 */
    private var dashboardView: android.view.View? = null

    /** 仪表盘模式变化（VirtualLauncher 通知）。 */
    private val dashboardListener = { dashboard: Boolean ->
        runOnUiThread { applyDashboardMode(dashboard) }
    }

    /** 媒体状态监听（MediaSessionsState 主线程回调）。 */
    private val mediaStateListener = {
        runOnUiThread { renderMediaCard() }
    }

    /** 全画布位图就绪（WallpaperStore 首次构建/重建后回调）。 */
    private val canvasReadyListener = { _: Long -> runOnUiThread { refreshDashWallpaper() } }

    /** 卡片视觉（暗幕透明度/模糊）——与 dock 一致（DockPrefs）。 */
    private val dockPrefsListener = {
        runOnUiThread {
            VirtualLauncher.refreshSplitRatio()
            // 仅重绑 position 0（仪表盘音乐卡），full reload 太贵（重扫 app + 重建网格）
            if (VirtualLauncher.dashboardMode) pager?.adapter?.notifyItemChanged(0)
            applyDashCardChrome()
            refreshDashWallpaper()
        }
    }

    /** 虚拟屏 VSYNC 保活（桌面不自动刷新的修复；真实屏自动跳过）。 */
    private val vsyncKeepAlive = VsyncKeepAlive(this)

    /** 「自动」档下手机系统深浅色变化：昼夜资源不一致时重建本页（虚拟屏不自动收到该变化）。 */
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

    /** 地图布局变化（全屏/分屏切换改变音乐卡壁纸切片区域）。
     *  具名字段（勿内联匿名 lambda）：onDestroy 才能对称移除，否则桌面重建累积泄漏。 */
    private val mapLayoutListener: (Boolean) -> Unit =
        { _ -> runOnUiThread { refreshDashWallpaper() } }

    /**
     * 应用卡片视觉：暗幕 alpha（与 dock 一致，按主题压白/压黑）。
     *
     * 模糊不再用 RenderEffect：它在虚拟屏上长时间空闲后会被系统回收，整张音乐卡
     * 渲染成黑块（触摸才恢复，2026-10-25 实车实测；每秒空闲重绘也救不回来）。
     * 改为在 [refreshDashWallpaper] 里**预模糊位图**，ImageView 只持有普通位图。
     */
    private fun applyDashCardChrome() {
        val panel = dashboardView ?: return
        val scrim = panel.findViewById<View>(R.id.dashScrim)
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        // 暗幕基色随主题：白天压白、夜间压黑（否则白底壁纸被黑幕压暗，深色文字看不清）
        scrim.background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dashCardRadiusPx()
                setColor(
                    with(androidx.core.content.ContextCompat.getColor(this@GridActivity, R.color.car_scrim)) {
                        android.graphics.Color.argb(
                            alpha,
                            android.graphics.Color.red(this),
                            android.graphics.Color.green(this),
                            android.graphics.Color.blue(this),
                        )
                    },
                )
            }
    }

    /** 音乐卡圆角（与 DashboardHolder.bind 的 outline 同源：边距 × 1.4）。 */
    private fun dashCardRadiusPx(): Float =
        (CARD_MARGIN_PX * VirtualLauncher.gridScale()).toInt() * 1.4f

    /** 最近一次生成的音乐卡壁纸切片（模糊+圆角）；空闲重绘时重设它以强制重新上传纹理。 */
    private var dashBitmap: Bitmap? = null

    /**
     * 音乐卡壁纸：全画布切片中「音乐卡区域」的裁剪（与全屏壁纸同映射，
     * 区域 = 地图卡右侧至卡片右边距）。
     */
    private fun refreshDashWallpaper() {
        val panel = dashboardView ?: return
        val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper) ?: return
        val params = wallpaper.layoutParams ?: return
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        Bg.run("wallpaper-dash") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        canvasWidth,
                        canvasHeight,
                    )
                }.getOrNull() ?: return@run
            val margin = (CARD_MARGIN_PX * scale).toInt()
            // 裁出音乐卡对应区域的壁纸切片（与卡片实际位置一致）：
            //  横屏：右半（左缘=中线+半边距 8，与地图 tile 右缘形成 16px 中缝）
            //  竖屏：下半（音乐卡在地图卡下方；顶 = 边距+tile 高+中缝）
            val cropX: Int
            val cropY: Int
            if (VirtualLauncher.dockAtBottom) {
                cropX = margin
                cropY =
                    (margin * 2 + VirtualLauncher.mapTileHeight * scale)
                        .toInt()
                        .coerceAtMost(bitmap.height - 2)
            } else {
                cropX =
                    ((VirtualLauncher.dockWidthPx + VirtualLauncher.appAreaWidth / 2 + CARD_MARGIN_PX / 2) * scale)
                        .toInt()
                        .coerceAtMost(bitmap.width - 2)
                cropY = margin.coerceAtMost(bitmap.height - 2)
            }
            val cropW = (bitmap.width - cropX - margin).coerceAtLeast(2)
            val cropH = (bitmap.height - cropY - margin).coerceAtLeast(2)
            val crop = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
            // 预模糊 + 圆角：不再依赖 RenderEffect / clipToOutline（这两个硬件层在
            // 虚拟屏空闲时会被回收，整块渲染成黑）
            val percent = DockPrefs.blur(this@GridActivity)
            val radius = percent / 100f * 40f * resources.displayMetrics.density
            val blurred = if (percent > 0) blurBitmap(crop, radius) else crop
            val rounded = roundBitmap(blurred, dashCardRadiusPx())
            runOnUiThread {
                dashBitmap = rounded
                wallpaper.setImageBitmap(rounded)
                applyDashCardChrome()
            }
        }
    }

    /**
     * 近似高斯模糊：先缩小再用双线性放大。
     * 在后台线程执行，结果位图直接交给 ImageView——省掉 RenderEffect 层。
     */
    private fun blurBitmap(
        src: Bitmap,
        radiusPx: Float,
    ): Bitmap {
        val factor = (radiusPx / 2.5f).coerceIn(1f, 48f)
        val smallW = (src.width / factor).toInt().coerceAtLeast(1)
        val smallH = (src.height / factor).toInt().coerceAtLeast(1)
        if (smallW >= src.width && smallH >= src.height) return src
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val out = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (small !== out && small !== src) small.recycle()
        return out
    }

    /** 把位图四角裁成圆角（替代 clipToOutline，避免硬件裁剪层被回收后变黑）。 */
    private fun roundBitmap(
        src: Bitmap,
        radiusPx: Float,
    ): Bitmap {
        if (radiusPx <= 0f) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        // 用 BitmapShader + drawRoundRect 而不是 clipPath：软件 Canvas 的 clipPath 不做抗锯齿，圆角会有锯齿
        val paint =
            android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG,
            ).apply {
                shader =
                    android.graphics.BitmapShader(
                        src,
                        android.graphics.Shader.TileMode.CLAMP,
                        android.graphics.Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawRoundRect(
            android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
            radiusPx,
            radiusPx,
            paint,
        )
        return out
    }

    /** 音乐卡进度自刷新（1s；渲染尾部续调度，位置由锚点+速度推算）。 */
    private val mediaTick =
        object : Runnable {
            override fun run() {
                renderMediaCard()
                tickHandler.postDelayed(this, 1_000)
            }
        }

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 空闲保活重绘日志节流（uptimeMillis）。 */
    private var lastIdleRefreshLogAt = 0L

    /** 地图卡空闲保活：上次观察到的地图屏帧号与时刻。 */
    private var lastMapFrameCount = -1
    private var lastMapFrameAt = 0L

    /**
     * 地图卡空闲保活：地图屏（高德等）长时间没有新帧时，其虚拟屏内容会被系统回收、
     * 卡片渲染成黑块（触摸才恢复，用户 2026-10-25 实测）。每隔
     * [MAP_KEEP_ALIVE_CHECK_MS] 检查帧号，静止超过 [MAP_IDLE_MS] 就逼地图重绘一次。
     */
    private val mapKeepAliveTick =
        object : Runnable {
            override fun run() {
                runCatching { checkMapKeepAlive() }
                tickHandler.postDelayed(this, MAP_KEEP_ALIVE_CHECK_MS)
            }
        }

    private fun checkMapKeepAlive() {
        val frames = VirtualLauncher.mapFrameCount()
        val now = android.os.SystemClock.uptimeMillis()
        if (frames != lastMapFrameCount) {
            lastMapFrameCount = frames
            lastMapFrameAt = now
            return
        }
        // 地图屏未创建 / 不在仪表盘页（地图卡不可见）时不打扰
        if (frames <= 0 || !VirtualLauncher.dashboardMode) return
        if (now - lastMapFrameAt < MAP_IDLE_MS) return
        lastMapFrameAt = now
        VirtualLauncher.nudgeMapDisplay()
    }

    /**
     * 空闲整页重绘（[VsyncKeepAlive.onIdleRefresh] 回调）：虚拟屏长时间无触摸时，
     * 位图纹理 / 裁剪层 / 模糊层会被系统回收，表现成「音乐卡 + 背景 + 翻页圆点变黑，
     * 触摸才恢复」。单纯 invalidate 在虚拟屏上不足以让 Surface 产出新帧（用户实测
     * 带 1px poke 的版本仍会黑），这里做完整重绘：requestLayout + invalidate 根视图，
     * 并重新 setImageBitmap 两张壁纸（同名位图重设也会逼 ImageView 重新上传纹理）。
     * 只重绘，不改数据/布局语义；日志 30s 节流。
     */
    private fun refreshIdleContent() {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        root.requestLayout()
        root.invalidate()
        reapplyBitmap(wallpaperView)
        dashboardView?.let { panel ->
            panel.invalidate()
            panel.findViewById<View>(R.id.musicCard)?.invalidate()
            // 重设同一张位图 → ImageView 重新上传纹理；同时按当前主题重建暗幕
            val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper)
            val cached = dashBitmap
            if (wallpaper != null && cached != null && !cached.isRecycled) {
                wallpaper.setImageBitmap(cached)
                wallpaper.invalidate()
                applyDashCardChrome()
            } else {
                reapplyBitmap(wallpaper)
            }
        }
        // 翻页圆点：重建每个圆点的 drawable（沿用旧 drawable 时其显示列表可能已被回收呈黑色）
        dotsRow?.let { row ->
            val current = pager?.currentItem ?: 0
            val idle = androidx.core.content.ContextCompat.getColor(this, R.color.car_page_dot)
            val sel = androidx.core.content.ContextCompat.getColor(this, R.color.car_page_dot_sel)
            for (i in 0 until row.childCount) {
                row.getChildAt(i).background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (i == current) sel else idle)
                    }
            }
            row.requestLayout()
            row.invalidate()
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastIdleRefreshLogAt >= 30_000L) {
            lastIdleRefreshLogAt = now
            VirtualLauncher.logSink?.invoke("GRID: 空闲保活重绘（卡片/背景变黑防护）")
        }
    }

    /** 重新设置同一个壁纸位图：触发 ImageView 重新上传纹理（纹理被回收时画面会变黑）。 */
    private fun reapplyBitmap(view: ImageView?) {
        val bmp = (view?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        if (bmp.isRecycled) return
        view.setImageBitmap(bmp)
        view.invalidate()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        vsyncKeepAlive.noteInteraction()
    }

    /** mm:ss（超 1 小时 h:mm:ss）。 */
    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1_000
        val h = totalSec / 3_600
        val m = totalSec % 3_600 / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }

    /**
     * 音乐卡渲染：封面/标题/歌手/进度/播放暂停按钮（播放中=暂停图标，暂停=播放图标）。
     *
     * 文案要区分三种「没有歌名」的成因，否则会误导：
     *  1. 有会话在播、但**没拿到通知使用权** → metadata 恒为 null（会话与播放状态
     *     仍然可见）。此时显示「无法读取播放信息」并提示去哪里开；
     *  2. 有会话但暂停/无元数据 → 「未在播放」；
     *  3. 完全没有会话 → 同样「未在播放」。
     *
     * 标题与副标题必须成对决定：曾出现标题「正在播放」（布局静态文案）配副标题
     * 「未在播放」的自相矛盾显示（截图实测），静态文案已改为「媒体」，这里再按
     * 状态统一写死两行，避免二者来自不同来源。
     */
    private fun renderMediaCard() {
        val panel = dashboardView ?: return
        val now = MediaSessionsState.nowPlaying
        val title = now?.title?.takeIf { it.isNotBlank() }
        val artist = now?.artist?.takeIf { it.isNotBlank() }
        // 有会话但读不到任何元数据 + 未授权 → 权限问题，不是「没在播放」
        val missingDueToPermission =
            now != null && title == null && !MediaSessionsState.accessGranted
        panel.findViewById<TextView>(R.id.dashMediaTitle).text =
            title ?: if (missingDueToPermission) "无法读取播放信息" else "未在播放"
        panel.findViewById<TextView>(R.id.dashMediaArtist).text =
            artist
                ?: when {
                    missingDueToPermission -> "未授予「通知使用权」——请在系统设置中开启"
                    now?.playing == true -> "正在播放"
                    else -> ""
                }
        panel.findViewById<ImageView>(R.id.dashPlayPause).setImageResource(
            if (now?.playing == true) R.drawable.ic_media_pause else R.drawable.ic_media_play,
        )
        panel.findViewById<TextView>(R.id.dashMediaTime)?.let { time ->
            if (now != null && now.durationMs > 0) {
                time.visibility = View.VISIBLE
                time.text =
                    "${formatTime(now.displayPositionAt(android.os.SystemClock.elapsedRealtime()))} / " +
                    formatTime(now.durationMs)
            } else {
                time.visibility = View.GONE
            }
        }
        panel.findViewById<ProgressBar>(R.id.dashMediaProgress)?.let { bar ->
            if (now != null && now.durationMs > 0) {
                bar.visibility = View.VISIBLE
                bar.progress =
                    (now.displayPositionAt(android.os.SystemClock.elapsedRealtime()) * 1000 / now.durationMs)
                        .toInt()
                        .coerceIn(0, 1000)
            } else {
                bar.visibility = View.GONE
            }
        }
        panel.findViewById<ImageView>(R.id.dashCover)?.let { cover ->
            val art = now?.art
            if (art != null) {
                cover.setImageBitmap(art)
                cover.visibility = View.VISIBLE
                // 封面圆角裁剪（outline 一次性设置）
                if (cover.clipToOutline != true) {
                    cover.clipToOutline = true
                    val dp = resources.displayMetrics.density
                    cover.outlineProvider =
                        object : android.view.ViewOutlineProvider() {
                            override fun getOutline(
                                view: View,
                                outline: android.graphics.Outline,
                            ) {
                                outline.setRoundRect(
                                    0,
                                    0,
                                    view.width,
                                    view.height,
                                    10 * dp,
                                )
                            }
                        }
                }
                // 点击封面 → 拉回正在播放的应用（任务复用：回到其播放界面）
                cover.setOnClickListener {
                    val pkg = now.pkg ?: return@setOnClickListener
                    val component =
                        packageManager.getLaunchIntentForPackage(pkg)?.component
                            ?: run {
                                VirtualLauncher.logSink
                                    ?.invoke("MEDIA: $pkg 无可启动入口，忽略封面点击")
                                return@setOnClickListener
                            }
                    Bg.run("launch-app") { VirtualLauncher.launchApp(component) }
                }
            } else {
                // 占位不消失（INVISIBLE）：封面异步加载/切歌间隙内容不上下跳动
                cover.visibility = View.INVISIBLE
                cover.setOnClickListener(null)
            }
        }
        // 进度显示需要持续走秒：渲染尾部续调度（防重：先移除）
        tickHandler.removeCallbacks(mediaTick)
        tickHandler.postDelayed(mediaTick, 1_000)
    }

    /** 仪表盘模式 → pager 位置同步（0=仪表盘，1+=桌面；已在目标位则不动）。 */
    private fun applyDashboardMode(dashboard: Boolean) {
        val pager = findViewById<ViewPager2>(R.id.gridPager)
        if (dashboard) {
            if (pager.currentItem != 0) pager.setCurrentItem(0, false)
            return
        }
        // 先消费 home 键登记的落点（即使当前不需要移动，也要清掉，避免残留到下次翻页）
        val target = exitDashboardPage((pager.adapter?.itemCount ?: 1) - 1)
        if (pager.currentItem != 0) return
        pager.setCurrentItem(target, false)
    }

    /**
     * 退出仪表盘后要落的网格页码（>=1）：
     *  - home 键从应用回来、且打开应用前不在仪表盘 → 回打开应用前那一页；
     *  - 其它情况 → 回用户最近停留的网格页。都不再固定回第 1 个网格页。
     */
    private fun exitDashboardPage(last: Int): Int {
        val restore = VirtualLauncher.consumeRestorePage()
        val target = if (restore > 0) restore else VirtualLauncher.desktopGridPage
        return target.coerceIn(1, last.coerceAtLeast(1))
    }

    /** 音乐控制：系统媒体键（AudioManager 全局路由给活跃媒体会话，无需权限）。 */
    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePrefs.applyToActivity(this)
        ThemePrefs.addNightListener(themeNightListener)
        DockPrefs.syncRatio(this) // 冷启动时把持久化的 splitRatio 加载到全局变量（SPLIT_MAP_WIDTH_RATIO）
        setContentView(R.layout.activity_grid)
        vsyncKeepAlive.install()
        // 空闲整页重绘：防卡片（音乐卡）长时间无触摸后被系统回收渲染成黑块
        vsyncKeepAlive.onIdleRefresh = { refreshIdleContent() }
        // 地图卡空闲保活轮询（地图屏静止过久 → 逼地图应用重绘）
        tickHandler.postDelayed(mapKeepAliveTick, MAP_KEEP_ALIVE_CHECK_MS)

        // 格子尺寸/行数按屏幕动态计算（布局关系在 XML，尺寸测量在代码）
        computeCellMetrics()
        // 虚拟屏重建竞态：dm 短暂为 0 时 cell/icon 尺寸会算出 0，首帧绑定即崩
        // （Bitmap.createBitmap 0 宽高）。夹最小值保底，并在 pager 首帧布局后重算+重绑。
        if (iconSizePx <= 0) {
            iconSizePx = 48
            findViewById<ViewPager2>(R.id.gridPager).post {
                computeCellMetrics()
                iconSizePx = iconSizePx.coerceAtLeast(48)
                if (cellWidthPx > 0) reload()
            }
        }

        // 壁纸背景（index 0 = 最底）：窗口必须不透明——透明窗口压在应用窗口前面时
        // 会把应用内容透出来（点 home 后桌面图标叠在头条上的根因）。
        // 切片映射：把「整个视频流画布」的 aspectFill 壁纸按 dock 位置偏移，
        // 本窗口正好露出应用区的切片，与 dock 处的切片逐像素连续（同一渲染路径）。
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth > 0 && canvasHeight > 0) {
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            wallpaperView =
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams =
                        android.widget.FrameLayout
                            .LayoutParams(
                                canvasWidth,
                                canvasHeight,
                            ).apply {
                                if (VirtualLauncher.dockAtBottom) {
                                    // 竖屏车机：dock 在底部，桌面切片 = 画布顶部
                                    leftMargin = 0
                                    topMargin = 0
                                } else {
                                    // 横屏车机：dock 在左侧，桌面切片 = 画布右部
                                    leftMargin = -(VirtualLauncher.dockWidthPx * scale).toInt()
                                    topMargin = 0
                                }
                            }
                }
            root.addView(wallpaperView, 0)
            // 注册变更通知 + 立即对账一次（兜底注册前发生的更换——事件不补发）
            WallpaperStore
                .addListener(wallpaperListener)
            refreshWallpaperIfChanged()

            // 仪表盘面板已改为 ViewPager2 首页（PageAdapter 绑定时创建），
            // 此处仅注册全局监听：卡片视觉/媒体状态/壁纸切片/仪表盘模式。
            DockPrefs.addListener(dockPrefsListener)
            MediaSessionsState
                .addListener(mediaStateListener)
            WallpaperStore
                .addCanvasReadyListener(canvasReadyListener)
            VirtualLauncher.addDashboardListener(dashboardListener)
            VirtualLauncher.addMapLayoutListener(mapLayoutListener)
            applyDashboardMode(VirtualLauncher.dashboardMode)
        }

        pager =
            findViewById<ViewPager2>(R.id.gridPager).apply {
                offscreenPageLimit = 2
                adapter = PageAdapter()
            }
        dotsRow = findViewById<LinearLayout>(R.id.dotsRow).apply {
            // 软件层：圆点是极小的矢量/形状，走 CPU 光栅化可避免硬件显示列表
            // 长时间空闲后被回收（表现为整排圆点变黑）
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        pager?.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(position)
                    // 记下用户所在的桌面页（含仪表盘页 0）：home 键从应用回来按此还原
                    VirtualLauncher.rememberDesktopPage(position)
                    // 翻页落定：清掉旧页高亮，按旋钮方向把光标落在新页的边界格上
                    clearHighlight()
                    selectedIndex = -1
                    if (pendingSelectBoundary != PENDING_NONE) {
                        val boundary = pendingSelectBoundary
                        pendingSelectBoundary = PENDING_NONE
                        pager?.post { selectBoundaryCell(boundary) }
                    }
                    // 用户滑动切换仪表盘/桌面：回写 launcher（含地图任务与 tile 布局）
                    if (position == 0 && !VirtualLauncher.dashboardMode) VirtualLauncher.showDashboard()
                    if (position != 0 && VirtualLauncher.dashboardMode) VirtualLauncher.showDesktop()
                }

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    offsetPixels: Int,
                ) {
                    // 滑动过程中 tile 跟随手指平移（合成器采样位置随进度左移/右移）；
                    // 回滑进仪表盘时提前显示 tile（纯视觉，状态在落定 IDLE 时统一对账）
                    if (position == 0) {
                        VirtualLauncher.setTileSlide(positionOffset)
                        if (positionOffset < 1f) VirtualLauncher.showMapTile()
                    }
                }

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                        VirtualLauncher.setTileSlide(0f)
                        // 落定对账：仪表盘模式与所在页一致（含异常中断的对账恢复）
                        VirtualLauncher.syncDashboard(pager?.currentItem == 0)
                    }
                }
            },
        )
    }

    /** 壁纸版本变化时后台重解码并换图（手机侧换壁纸后 ≤2s 桌面更新）。 */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val current = WallpaperStore.version
        if (current == wallpaperShownVersion) return
        wallpaperShownVersion = current
        val params = view.layoutParams as? android.widget.FrameLayout.LayoutParams
        Bg.run("wallpaper-grid") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        params?.width ?: 0,
                        params?.height ?: 0,
                    )
                }.getOrNull()
            if (bitmap != null) {
                // 诊断：bitmap 三点采样（中线上 25%/50%/75% 宽）——区分「bitmap 内容缺失」
                // 与「视图/合成问题」（本机 logcat 受抑制，走 logSink 落文件日志）
                val samples =
                    listOf(0.25f, 0.5f, 0.75f).joinToString(",") {
                        "%06x".format(bitmap.getPixel((bitmap.width * it).toInt(), bitmap.height / 2))
                    }
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-GRID: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params?.width}x${params?.height} left=${params?.leftMargin} " +
                        "samples=[$samples]",
                )
                runOnUiThread { view.setImageBitmap(bitmap) }
            } else {
                VirtualLauncher.logSink?.invoke("WALLPAPER-GRID: 解码失败")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 也覆盖从设置页返回：勾选变化即时反映
        reload()
        refreshWallpaperIfChanged()
        KnobNav.register(knobListener)
    }

    override fun onPause() {
        KnobNav.unregister(knobListener)
        super.onPause()
    }

    /**
     * 旋钮导航回调（主控在 HID 读取线程里调，必须切回主线程）。
     * 桌面不在前台时监听已注销，主控自动回退到按键注入。
     */
    private val knobListener =
        object : KnobNav.Listener {
            override fun onRotate(direction: Int): Boolean {
                runOnUiThread { moveByKnob(direction) }
                return true
            }

            override fun onPress(): Boolean {
                if (selectedIndex < 0) return false
                runOnUiThread { activateSelected() }
                return true
            }
        }

    /**
     * 计算格子尺寸与列/行数（自动适配）：
     * 在 [COLUMNS_MIN, COLUMNS_MAX] × [ROWS_MIN, ROWS_MAX] 里搜索，使格子高宽比最接近
     * CELL_HEIGHT_RATIO（1.25）；同等偏差下取容量更大的一组（少留白）。
     * 原来横屏固定 4 列 × 3 行，宽屏上格子被拉成扁平条、图标显得小；自动适配后
     * 宽屏会自然多出几列，竖屏则多出行。图标尺寸跟随格子宽高取小值，避免比例失衡。
     */
    private fun computeCellMetrics() {
        val dm = resources.displayMetrics
        val density = dm.density
        val chromeHorizontalPx = (2 * 10 * density).toInt() // item_grid_page 左右 padding
        val chromeVerticalPx = ((8 + 26 + 16) * density).toInt() // 上/下 padding + 页点区
        val usableW = dm.widthPixels - chromeHorizontalPx
        val usableH = dm.heightPixels - chromeVerticalPx
        if (usableW <= 0 || usableH <= 0) {
            cellWidthPx = 0
            cellHeightPx = 0
            iconSizePx = 0
            return
        }
        val gapPx = (GRID_GAP_DP * density).toInt()
        cellGapPx = gapPx
        val minCellPx = (MIN_CELL_SIZE_DP * density).toInt()
        var bestColumns = COLUMNS_DEFAULT
        var bestRows = ROWS_MIN
        var bestScore = Float.MAX_VALUE
        for (c in COLUMNS_MIN..COLUMNS_MAX) {
            // 格宽 = (可用宽 - 列间 gap) / 列数（首列无左外边距，其余列左侧 gap）
            val cw = (usableW - (c - 1) * gapPx) / c
            if (cw < minCellPx) continue
            for (r in ROWS_MIN..ROWS_MAX) {
                val ch = (usableH - (r - 1) * gapPx) / r
                if (ch < minCellPx) continue
                val score = kotlin.math.abs(ch.toFloat() / cw.toFloat() - CELL_HEIGHT_RATIO)
                val better =
                    score < bestScore - 1e-4f ||
                        (kotlin.math.abs(score - bestScore) <= 1e-4f && c * r > bestColumns * bestRows)
                if (better) {
                    bestScore = score
                    bestColumns = c
                    bestRows = r
                }
            }
        }
        columns = bestColumns
        rows = bestRows
        cellWidthPx = (usableW - (columns - 1) * gapPx) / columns
        cellHeightPx = (usableH - (rows - 1) * gapPx) / rows
        // CarPlay 规格（2026-10-26）：图标在格子里占比略放大（0.63→0.65 / 0.72→0.76）
        iconSizePx =
            minOf(
                (cellHeightPx * 0.65f).toInt(),
                (cellWidthPx * 0.76f).toInt(),
            )
        VirtualLauncher.logSink?.invoke(
            "GRID: 自适应 ${columns}列×${rows}行（每页 ${columns * rows} 个）" +
                " 屏=${dm.widthPixels}×${dm.heightPixels}@${dm.densityDpi}dpi",
        )
    }

    /**
     * 仪表盘面板的自适应密度上下文。
     *
     * 面板内容按固定 dp 排版，但卡片实际像素尺寸随车机分辨率变化：窄屏（如 1024×600）
     * 上音乐卡只有 ~240dp 宽，而按钮行固定宽 158dp + 内边距，仍可能被裁；右上角
     * 导航入口的文案也会换行（2026-10-25 用户反馈「显示会乱/按钮显示不全」）。
     *
     * 对策：按卡片真实像素尺寸反推一个「fit 密度」——保证卡片逻辑宽度 ≥
     * [DASH_REF_WIDTH_DP]、逻辑高度 ≥ [DASH_REF_HEIGHT_DP]，整张面板等比缩小；
     * 宽车机（卡片本来就够大）不覆盖，保持原生密度与原有观感。
     */
    private fun dashboardContext(): Context {
        val dm = resources.displayMetrics
        val scale = VirtualLauncher.gridScale()
        val cardMargin = (CARD_MARGIN_PX * scale).toInt()
        val bottomInset = maxOf(cardMargin, (VirtualLauncher.dotsBandStreamPx() * scale).toInt())
        // 与 DashboardHolder.bind 的 MarginLayoutParams 同源推导，保证换算的卡片尺寸一致
        val cardWidthPx: Int
        val cardHeightPx: Int
        if (VirtualLauncher.dockAtBottom) {
            cardWidthPx = dm.widthPixels - cardMargin * 2
            cardHeightPx =
                dm.heightPixels -
                (cardMargin * 2 + (VirtualLauncher.mapTileHeight * scale).toInt()) -
                bottomInset
        } else {
            val leftMargin =
                ((VirtualLauncher.appAreaWidth * SPLIT_MAP_WIDTH_RATIO + CARD_MARGIN_PX / 2) * scale).toInt()
            cardWidthPx = dm.widthPixels - leftMargin - cardMargin
            cardHeightPx = dm.heightPixels - cardMargin - bottomInset
        }
        if (cardWidthPx <= 0 || cardHeightPx <= 0) return this
        val fitDpi =
            (160f * minOf(cardWidthPx / DASH_REF_WIDTH_DP, cardHeightPx / DASH_REF_HEIGHT_DP)).toInt()
        if (fitDpi <= 0 || fitDpi >= dm.densityDpi * 0.97f) return this
        val config = android.content.res.Configuration(resources.configuration).apply { densityDpi = fitDpi }
        return androidx.appcompat.view.ContextThemeWrapper(
            createConfigurationContext(config),
            R.style.Theme_CarPlaySender_Launcher,
        )
    }

    /** 重新读取应用清单与桌面选择（首帧与每次回到桌面时调用）。 */
    private fun reload() {
        val apps =
            appsCache ?: run {
                val scanned = VirtualLauncher.launchableApps(this)
                // iconCache 供 dock 运行中图标使用：整表换新——dock 在另一块屏的主线程
                // 并发读，clear+逐条写入共享 HashMap 无同步会损坏（坏味道审查 P1）
                VirtualLauncher.replaceIconCache(
                    scanned.associate { it.component to it.icon },
                )
                appsCache = scanned
                // 纳入进程级共享缓存：桌面扫过之后，车机设置首次打开即秒开
                VirtualLauncher.adoptAppsCache(scanned)
                scanned
            }

        val selected = DesktopApps.selected(this, apps)
        val cells =
            buildList {
                add(Cell.Pinned) // 桌面第一格：车机设置（固定，不可取消）
                apps.filter { it.component.flattenToString() in selected }.forEach { add(Cell.App(it)) }
            }
        // 第 0 页 = 仪表盘，其后为桌面网格页：左右滑动即互相切换
        pages = listOf(listOf<Cell>(Cell.Dashboard)) + cells.chunked(columns * rows)

        val current = pager?.currentItem ?: 0
        // 数据重建后旧高亮视图已失效（ViewHolder 会复用/销毁），光标复位
        clearHighlight()
        selectedIndex = -1
        pendingSelectBoundary = PENDING_NONE
        pager?.adapter?.notifyDataSetChanged()
        rebuildDots()
        // 位置与仪表盘模式对账（home 键循环/应用启动会改 dashboardMode）：
        // 落点见 exitDashboardPage——回到打开应用前的页 / 最近停留的网格页，不固定第 1 页
        if (VirtualLauncher.dashboardMode && current != 0) pager?.setCurrentItem(0, false)
        if (!VirtualLauncher.dashboardMode) {
            val at = pager?.currentItem ?: 0
            val restore = VirtualLauncher.consumeRestorePage()
            // 停在仪表盘页、或 home 键登记了落点时才移动；用户自己停在网格第 N 页就不拽
            val want = when {
                restore > 0 -> restore
                at == 0 -> VirtualLauncher.desktopGridPage
                else -> at
            }
            val target = want.coerceIn(1, (pages.size - 1).coerceAtLeast(1))
            if (at != target) pager?.setCurrentItem(target, false)
        }
        if ((pager?.currentItem ?: 0) >= pages.size) pager?.setCurrentItem(pages.size - 1, false)
        updateDots(pager?.currentItem ?: 0)
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = if (pages.getOrNull(position)?.firstOrNull() is Cell.Dashboard) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == 0) {
                // 仪表盘面板用自适应密度上下文 inflate：窄屏上面板整体等比缩小
                // （固定 dp 布局在窄卡片里会溢出，见 dashboardContext 注释）
                DashboardHolder(
                    android.view.LayoutInflater
                        .from(dashboardContext())
                        .inflate(R.layout.view_dashboard, parent, false),
                )
            } else {
                val grid = layoutInflater.inflate(R.layout.item_grid_page, parent, false) as GridLayout
                grid.columnCount = columns
                // 行数由 computeCellMetrics 按屏幕自适应（宽容许多列、窄屏多行）
                grid.rowCount = rows
                PageHolder(grid)
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (holder) {
                is DashboardHolder -> holder.bind()
                is PageHolder -> holder.bind(position)
            }
        }
    }

    /**
     * 导航快捷入口：用「车机设置 → 默认地图」里选定的应用，在车机地图屏拉起家/单位路线。
     *
     * 高德车机版走它自己的「回家/去公司」widget 广播（实测 `androidauto://route?dname=`
     * 只带名称会提示「路线请求失败」，而广播能直接规划到车机版里配置的地址）；
     * 其它地图走 URI（高德手机版 amapuri / 百度 baidumap）。
     */
    private fun launchNavDestination(home: Boolean) {
        val defaultPkg = VirtualLauncher.defaultMapComponent(this)?.packageName
        // 高德车机版：地址保存在它自己的设置里，广播最可靠（2026-10-26 实车验证）
        if (defaultPkg == "com.autonavi.amapauto") {
            val action =
                if (home) {
                    "com.autonavi.amapauto.AUTO_WIDGET_GO_HOME"
                } else {
                    "com.autonavi.amapauto.AUTO_WIDGET_GO_COMPANY"
                }
            Bg.run("launch-nav") {
                // 先确保车机版在地图屏前台（否则广播可能把导航落到手机屏/无任务）
                VirtualLauncher.launchApp(
                    android.content.ComponentName(defaultPkg, "com.autonavi.amapauto.MainMapActivity"),
                )
                Thread.sleep(1_200)
                val result = io.github.shaonialife.cardeck.shizuku.Shell.exec("am broadcast -a $action", timeoutMs = 5_000)
                VirtualLauncher.logSink?.invoke(
                    "NAV: 高德车机版${if (home) "回家" else "回单位"}广播 ok=${result.ok} out=${result.output.take(80)}",
                )
            }
            return
        }
        val address =
            if (home) {
                DockPrefs.navHomeAddress(this)
            } else {
                DockPrefs.navWorkAddress(this)
            }
        val label = if (home) "家" else "单位"
        if (address.isBlank()) {
            android.widget.Toast
                .makeText(
                    this,
                    "快捷回家/回单位走高德车机版；请在「默认地图」中选用高德车机版",
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
            return
        }
        val encoded = java.net.URLEncoder.encode(address, "UTF-8")
        // 不同地图的 scheme 不同：
        //  高德手机版 amapuri://（只由 com.autonavi.minimap 的跳板处理）
        //  百度 baidumap://
        val uri =
            if (defaultPkg == "com.baidu.BaiduMap") {
                "baidumap://map/direction?destination=$encoded&mode=driving"
            } else {
                "amapuri://route/plan/?dname=$encoded&dev=0&t=0"
            }
        // 只把默认地图包交给 launchNavUri（不兜底到其它地图，避免落到未选中的版本）
        val preferPackages = listOfNotNull(defaultPkg)
        Bg.run("launch-nav") { VirtualLauncher.launchNavUri(uri, preferPackages) }
    }

    /** 仪表盘页 Holder：面板随页面创建（卡片边距/圆角/媒体控制/壁纸切片）。 */
    private inner class DashboardHolder(
        val panel: View,
    ) : RecyclerView.ViewHolder(panel) {
        fun bind() {
            dashboardView = panel
            val scale = VirtualLauncher.gridScale()
            val cardMargin = (CARD_MARGIN_PX * scale).toInt()
            // 底部页点占位：与地图卡共用同一契约（VirtualLauncher.dotsBandStreamPx，流坐标 × 倍率）。
            // 卡片底边原来固定 cardMargin(≈16px)，页点顶边在 14dp，二者视觉上会叠一起。
            val dotsBandPx = (VirtualLauncher.dotsBandStreamPx() * scale).toInt()
            val bottomInset = maxOf(cardMargin, dotsBandPx)
            panel.layoutParams =
                android.view.ViewGroup
                    .MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        if (VirtualLauncher.dockAtBottom) {
                            // 竖屏：音乐卡在地图卡下方（地图上/音乐下），中缝 16
                            leftMargin = cardMargin
                            rightMargin = cardMargin
                            topMargin = cardMargin * 2 + (VirtualLauncher.mapTileHeight * scale).toInt()
                            bottomMargin = bottomInset
                        } else {
                            // 横屏：音乐卡左缘 = 地图 tile 右缘 + CARD_MARGIN_PX(16) 中缝。
                            // 合成器 tileW = appAreaW * RATIO - 24（左缘 16 + 右缘半距 8），
                            // tile 右缘 = appAreaW * RATIO - 8；音乐卡左缘 = 右缘 + 16 = appAreaW * RATIO + 8。
                            leftMargin =
                                ((VirtualLauncher.appAreaWidth * SPLIT_MAP_WIDTH_RATIO + CARD_MARGIN_PX / 2) * scale).toInt()
                            topMargin = cardMargin
                            rightMargin = cardMargin
                            bottomMargin = bottomInset
                        }
                    }
            // 根视图改为垂直 LinearLayout（导航卡+音乐卡）后，圆角裁剪要落在两张卡片
            // 上而不是整个 panel（panel 本身透明，含两卡之间的间距带）。
            val roundRadius = cardMargin * 1.4f
            val roundOutline =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: android.graphics.Outline,
                    ) {
                        outline.setRoundRect(0, 0, view.width, view.height, roundRadius)
                    }
                }
            panel.findViewById<View>(R.id.navCard).apply {
                outlineProvider = roundOutline
                clipToOutline = true
            }
            panel.findViewById<View>(R.id.musicCard).apply {
                // 圆角由「预裁圆角的壁纸位图 + 圆角暗幕」表达：不再用 clipToOutline
                // （裁剪层长时间空闲会被回收，整卡变黑）
                outlineProvider = roundOutline
                clipToOutline = false
            }
            panel.findViewById<View>(R.id.navHome).setOnClickListener {
                launchNavDestination(home = true)
            }
            panel.findViewById<View>(R.id.navWork).setOnClickListener {
                launchNavDestination(home = false)
            }
            panel.findViewById<ImageView>(R.id.dashPrev).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            panel.findViewById<ImageView>(R.id.dashNext).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            panel.findViewById<ImageView>(R.id.dashPlayPause).setOnClickListener {
                // 按当前状态显式发播放/暂停；乐观翻转图标（状态回调可能滞后），
                // 400ms 后请求监听服务重新同步真实状态
                val state = MediaSessionsState.nowPlaying
                val playing = state?.playing == true
                dispatchMediaKey(
                    if (playing) {
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    } else {
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    },
                )
                // 乐观更新保留封面等其它字段（copy）；400ms 后重同步真实状态
                MediaSessionsState.publish(
                    state
                        ?.copy(playing = !playing)
                        ?: MediaSessionsState.NowPlaying(
                            null,
                            null,
                            !playing,
                        ),
                )
                panel.postDelayed(
                    {
                        MediaSessionsState
                            .requestResync()
                    },
                    400,
                )
            }
            // 全屏地图的退出走 dock 的 home 按钮（showDashboard 路由闸门会自动
            // setMapFullscreen(false)），仪表盘卡上不再放「全屏地图」按钮
            applyDashCardChrome()
            renderMediaCard()
            refreshDashWallpaper()
        }
    }

    private inner class PageHolder(
        val grid: GridLayout,
    ) : RecyclerView.ViewHolder(grid) {
        fun bind(page: Int) {
            grid.removeAllViews()
            pages[page].forEachIndexed { index, cell ->
                val view = layoutInflater.inflate(R.layout.item_app_cell, grid, false)
                val icon =
                    view.findViewById<ImageView>(R.id.cellIcon).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    }
                val label = view.findViewById<TextView>(R.id.cellLabel)
                when (cell) {
                    is Cell.Dashboard -> Unit // 仪表盘独立成页，不进网格
                    is Cell.Pinned -> {
                        icon.setImageResource(R.drawable.ic_app_settings)
                        label.text = getString(R.string.car_settings)
                        view.setOnClickListener { openSettings() }
                    }

                    is Cell.App -> {
                        icon.setImageBitmap(AppIcons.rounded(cell.entry.icon, iconSizePx))
                        label.text = cell.entry.label
                        view.setOnClickListener {
                            // 不用系统 Toast：虚拟屏上显示位置不可控（SettingsToastHost 的由来）；
                            // 应用拉起本身就是反馈
                            Bg.run("launch-app") { VirtualLauncher.launchApp(cell.entry.component) }
                        }
                    }
                }
                grid.addView(
                    view,
                    GridLayout.LayoutParams().apply {
                        width = cellWidthPx
                        height = cellHeightPx
                        // 间距：第 0 列/行的格不设起始外边距，其余格左侧/上方留 [cellGapPx]——
                        // 总宽高仍等于可用区域（格子尺寸已按 gap 扣除）。
                        if (columns > 0) {
                            if (index % columns != 0) marginStart = cellGapPx
                            if (index / columns != 0) topMargin = cellGapPx
                        }
                    },
                )
            }
        }
    }

    /**
     * iDrive 旋钮 / 方向键导航：一齿一次调用，direction=+1 顺时针。
     *
     * 光标在应用列表内 → 移动选中格；选中格已在列表边界还要继续转 → 翻页并落到
     * 新页的边界格；当前页没有应用格（仪表盘页）→ 直接左右翻页。
     */
    private fun moveByKnob(direction: Int) {
        val pager = pager ?: return
        val cells = appCellsAt(pager.currentItem)
        if (cells.isEmpty()) {
            flipPage(direction)
            return
        }
        if (selectedIndex < 0) {
            selectCell(if (direction > 0) 0 else cells.lastIndex)
            return
        }
        val next = selectedIndex + direction
        if (next in cells.indices) {
            selectCell(next)
            return
        }
        // 越过应用列表边界：翻页，光标落到新页的首格/末格
        val target = pager.currentItem + direction
        if (target in pages.indices) {
            clearHighlight()
            selectedIndex = -1
            pendingSelectBoundary = if (direction > 0) 0 else -1
            pager.setCurrentItem(target, true)
        }
    }

    /** 左右翻页（光标不在应用列表里时旋钮的动作）。 */
    private fun flipPage(direction: Int) {
        val pager = pager ?: return
        val last = (pages.size - 1).coerceAtLeast(0)
        val target = (pager.currentItem + direction).coerceIn(0, last)
        if (target == pager.currentItem) return
        clearHighlight()
        selectedIndex = -1
        pendingSelectBoundary = PENDING_NONE
        pager.setCurrentItem(target, true)
    }

    /** 某页里的应用格（仪表盘页返回空）。 */
    private fun appCellsAt(page: Int): List<Cell> =
        pages.getOrNull(page).orEmpty().filter { it !is Cell.Dashboard }

    private fun selectBoundaryCell(boundary: Int) {
        val cells = appCellsAt(pager?.currentItem ?: return)
        if (cells.isEmpty()) return
        selectCell(if (boundary == 0) 0 else cells.lastIndex)
    }

    private fun selectCell(
        index: Int,
        retry: Boolean = true,
    ) {
        val grid = currentPageGrid()
        if (grid == null) {
            // 新页可能还没布局完：下一帧再试一次
            if (retry) pager?.post { selectCell(index, false) }
            return
        }
        if (index !in 0 until grid.childCount) return
        clearHighlight()
        val view = grid.getChildAt(index)
        view.foreground = highlight()
        highlightView = view
        selectedIndex = index
    }

    private fun clearHighlight() {
        highlightView?.foreground = null
        highlightView = null
    }

    private fun highlight(): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * density
            setColor(androidx.core.content.ContextCompat.getColor(this@GridActivity, R.color.car_focus_fill))
            setStroke(
                (2 * density).toInt(),
                androidx.core.content.ContextCompat.getColor(this@GridActivity, R.color.car_focus_stroke),
            )
        }
    }

    /** 当前页的网格容器（仪表盘页/尚未绑定时为 null）。 */
    private fun currentPageGrid(): GridLayout? {
        val pager = pager ?: return null
        val recycler = pager.getChildAt(0) as? RecyclerView ?: return null
        val holder = recycler.findViewHolderForAdapterPosition(pager.currentItem) as? PageHolder ?: return null
        return holder.grid
    }

    /** 旋钮按下：打开光标所在的应用。 */
    private fun activateSelected() {
        val index = selectedIndex
        if (index < 0) return
        currentPageGrid()?.getChildAt(index)?.performClick()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    moveByKnob(1)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                -> {
                    moveByKnob(-1)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                -> if (selectedIndex >= 0) {
                    activateSelected()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 兜底：某些机型仍会发 ACTION_SCROLL（蓝牙滚轮路径已改发方向键）。 */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val delta =
                event.getAxisValue(MotionEvent.AXIS_VSCROLL) +
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (delta != 0f) {
                moveByKnob(if (delta > 0f) 1 else -1)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun openSettings() {
        // 滑动转场由 SettingsActivity 主题的 windowAnimationStyle 提供
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun rebuildDots() {
        val row = dotsRow ?: return
        row.removeAllViews()
        if (pages.size < 2) return // 单页不显示页点（对照原型）
        val density = resources.displayMetrics.density
        repeat(pages.size) {
            row.addView(
                android.view.View(this).apply {
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(androidx.core.content.ContextCompat.getColor(this@GridActivity, R.color.car_page_dot))
                        }
                },
                LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                },
            )
        }
        updateDots(0)
    }

    private fun updateDots(selected: Int) {
        val row = dotsRow ?: return
        val dotIdle = androidx.core.content.ContextCompat.getColor(this, R.color.car_page_dot)
        val dotSel = androidx.core.content.ContextCompat.getColor(this, R.color.car_page_dot_sel)
        for (i in 0 until row.childCount) {
            (row.getChildAt(i).background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(if (i == selected) dotSel else dotIdle)
        }
    }

    override fun onDestroy() {
        tickHandler.removeCallbacks(mediaTick)
        tickHandler.removeCallbacks(mapKeepAliveTick)
        WallpaperStore
            .removeListener(wallpaperListener)
        VirtualLauncher.removeDashboardListener(dashboardListener)
        VirtualLauncher.removeMapLayoutListener(mapLayoutListener)
        MediaSessionsState
            .removeListener(mediaStateListener)
        DockPrefs.removeListener(dockPrefsListener)
        WallpaperStore
            .removeCanvasReadyListener(canvasReadyListener)
        ThemePrefs.removeNightListener(themeNightListener)
        vsyncKeepAlive.release()
        super.onDestroy()
    }

    override fun onBackPressed() = Unit // 桌面不退出

    private companion object {
        /** 默认列数（搜索无解时的兜底；CarPlay 横屏常见解为 4–8 列）。 */
        const val COLUMNS_DEFAULT = 4

        /** 自动适配：列数搜索范围。 */
        const val COLUMNS_MIN = 3
        const val COLUMNS_MAX = 8

        /** 自动适配：行数搜索范围。 */
        const val ROWS_MIN = 3
        const val ROWS_MAX = 8

        /** 格子最小边长（dp）：低于此值图标+标签会挤成一团，搜索时跳过该组合。 */
        const val MIN_CELL_SIZE_DP = 72

        /** 格子目标高宽比（高/宽）：搜索时最接近此比例的组合胜出。 */
        const val CELL_HEIGHT_RATIO = 1.25f

        /** 格子间距（dp）：从可用宽高里扣除，避免图标/文字挤在一起（2026-10-25 用户要求）。 */
        const val GRID_GAP_DP = 8

        /** 仪表盘面板的设计基准卡宽/卡高（dp）：fit 密度保证卡片逻辑尺寸不小于此值。 */
        const val DASH_REF_WIDTH_DP = 260f
        const val DASH_REF_HEIGHT_DP = 300f

        /** 地图卡空闲保活：检查间隔与判定阈值（地图屏帧号静止超过阈值即逼其重绘）。 */
        const val MAP_KEEP_ALIVE_CHECK_MS = 10_000L
        const val MAP_IDLE_MS = 15_000L

        /** [pendingSelectBoundary] 的空值：翻页后不自动选格。 */
        const val PENDING_NONE = Int.MIN_VALUE
    }
}
