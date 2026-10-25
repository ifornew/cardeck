package io.github.shaonialife.cardeck.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.util.Bg

/**
 * 常亮 + 暗幕：替代息屏的锁屏规避方案。
 *
 * 车机场景下手机锁屏会冻结非默认屏的输入（系统硬边界），且安全锁无法被命令解除。
 * 本方案让屏幕永不自动息屏（屏幕超时设为极大值，断开时恢复原值），
 * 无操作 45 秒后盖一层近黑全屏 View（模拟息屏观感），触摸手机任意位置即移除并重新计时。
 *
 * 触摸探针：1px 窗口 + FLAG_WATCH_OUTSIDE_TOUCH，捕获屏幕任意处的真实触摸（含第三方 app），
 * 仅用于重置计时，不拦截、不消费任何事件。
 */
object ScreenDimmer {
    private const val TAG = "ScreenDimmer"
    private const val IDLE_TIMEOUT_MS = 45_000L
    private const val TIMEOUT_MAX = 2_147_483_647

    /** 由设置项控制（默认开）。 */
    @Volatile
    var enabled: Boolean = true

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var handler: Handler? = null
    private var dimView: View? = null
    private var watchView: View? = null

    @Volatile
    private var dimShown = false

    /**
     * 当前是否有活跃的投屏会话。
     *
     * **自动暗幕与常亮只在会话期间生效**（2026-10-19 用户实测反馈）：
     * 应用启动时就会 attach（为了让「手动假息屏」随时可用），但车机侧断开
     * （`VideoSender.onEnded`）不会释放 VirtualLauncher，暗幕的 45s 定时器仍在——
     * 表现为「会话早就断了，过一会儿手机屏突然变黑」。所以自动调度必须由会话驱动，
     * 手动 [enterDimNow] 不受此限制（用户显式操作）。
     */
    @Volatile
    private var sessionActive = false

    private val showRunnable = Runnable { showDim() }

    /** 会话开始/结束（由 SessionController 在推流起停时调用）。 */
    fun setSessionActive(active: Boolean) {
        if (sessionActive == active) return
        sessionActive = active
        val ctx = appContext ?: return
        val h = handler
        if (active) {
            if (!enabled) return
            enableStayAwake(ctx)
            h?.post { schedule() }
            Log.i("CarPlaySender", "DIMMER: 会话开始——常亮/自动暗幕生效（${IDLE_TIMEOUT_MS / 1000}s 无操作后变暗）")
        } else {
            h?.removeCallbacks(showRunnable)
            // 会话结束时如果暗幕正盖着（车机自己断开），立即撤掉，让用户看到手机正常界面
            h?.post { hideDim() }
            restoreTimeout(ctx)
            Log.i("CarPlaySender", "DIMMER: 会话结束——自动暗幕已停、屏幕超时已恢复")
        }
    }

    fun attach(context: Context) {
        if (appContext != null) {
            // 已附着：本次若为「启用」切换，补齐常亮与自动计时（触摸探针此前已挂）
            if (enabled && sessionActive) {
                enableStayAwake(context)
                handler?.post { schedule() }
            }
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "缺少悬浮窗权限，暗幕/触摸探针不可用（常亮不可用）")
            appContext = context.applicationContext
            windowManager = null
            handler = Handler(Looper.getMainLooper())
            return
        }
        appContext = context.applicationContext
        windowManager = defaultDisplayWindowManager(context)
        handler = Handler(Looper.getMainLooper())
        // 常亮只在会话期间打开：应用启动时 attach 不把屏幕超时改成「永不」，
        // 否则未连接时手机也永不熄屏（车机断开后同样收不回来）。
        if (enabled && sessionActive) enableStayAwake(context)
        handler?.post {
            addWatcher()
            if (enabled && sessionActive) schedule()
        }
        if (!enabled) {
            Log.i("CarPlaySender", "DIMMER: 触摸探针已启动（常亮/暗幕关闭）")
        } else if (sessionActive) {
            Log.i("CarPlaySender", "DIMMER: 已启动（常亮 + ${IDLE_TIMEOUT_MS / 1000}s 无操作后暗幕，点击恢复）")
        } else {
            Log.i("CarPlaySender", "DIMMER: 触摸探针已启动（未在投屏——常亮/自动暗幕待会话开始后生效）")
        }
    }

    /** 关闭常亮/暗幕但保留触摸探针（重新开启时无需重建窗口）。 */
    fun disableDim() {
        enabled = false
        val ctx = appContext ?: return
        restoreTimeout(ctx)
        handler?.removeCallbacks(showRunnable)
        handler?.post { hideDim() }
        Log.i("CarPlaySender", "DIMMER: 常亮/暗幕已关闭（触摸探针保留）")
    }

    /**
     * 自动暗幕是否应当生效（纯函数，见 [shouldAutoDim]）。
     *
     * 拆出来是为了单测：这三者任一为假都必须**不**弹暗幕。用户实测的 bug 正是
     * 「会话已结束（sessionActive=false）但定时器还在」——手机屏无故变黑。
     */
    fun shouldAutoDim(
        enabled: Boolean,
        sessionActive: Boolean,
        attached: Boolean,
    ): Boolean = enabled && sessionActive && attached

    fun detach() {
        val h = handler
        handler = null
        // 会话标记必须一并清零：否则下次 attach（未连接时启动 App）会沿用上一轮的
        // true 而立即排程，又变回「没会话也自动变暗」的老问题。
        sessionActive = false
        h?.removeCallbacksAndMessages(null)
        val wm = windowManager
        val dim = dimView
        val watch = watchView
        dimView = null
        watchView = null
        dimShown = false
        windowManager = null
        h?.post {
            dim?.let { v -> runCatching { wm?.removeView(v) } }
            watch?.let { v -> runCatching { wm?.removeView(v) } }
        }
        val ctx = appContext
        appContext = null
        if (ctx != null) restoreTimeout(ctx)
        Log.i("CarPlaySender", "DIMMER: 已停止（屏幕超时恢复）")
    }

    /** 暗幕是否已就绪（attach 完成且具备悬浮窗能力）。 */
    fun isAttached(): Boolean = handler != null && windowManager != null

    /** 手动进入假息屏（主页面按钮触发；点击暗幕或手机屏幕任意处恢复）。 */
    fun enterDimNow() {
        val h = handler ?: return
        // attach 时缺权限、之后才授予的：此处补建窗口管理器与触摸探针
        if (windowManager == null) {
            val ctx = appContext ?: return
            if (!android.provider.Settings.canDrawOverlays(ctx)) return
            windowManager = defaultDisplayWindowManager(ctx)
            h.post { addWatcher() }
        }
        // 手动假息屏要有意义，屏幕必须保持常亮（否则真睡下去就没法「点击恢复」）
        appContext?.let { if (enabled) enableStayAwake(it) }
        h.post { showDim(force = true) }
    }

    /** 物理触摸（任意 app/界面）或暗幕点击时调用：重置自动暗幕计时。 */
    fun onUserInteraction() {
        val h = handler ?: return
        if (dimShown) hideDim()
        if (enabled && sessionActive) schedule()
    }

    private fun schedule() {
        if (!sessionActive) return
        handler?.removeCallbacks(showRunnable)
        handler?.postDelayed(showRunnable, IDLE_TIMEOUT_MS)
    }

    private fun showDim(force: Boolean = false) {
        // 自动路径必须有活跃会话：会话已结束（尤其车机侧断开）时绝不弹暗幕
        if (!force && !shouldAutoDim(enabled, sessionActive, isAttached())) return
        val ctx = appContext
        if (ctx == null) {
            android.util.Log.w("ScreenDimmer", "showDim: appContext 为空（attach 未执行）")
            return
        }
        val wm = windowManager
        if (wm == null) {
            android.util.Log.w("ScreenDimmer", "showDim: windowManager 为空（悬浮窗权限缺失？）")
            return
        }
        if (dimShown) return
        val view =
            View(ctx).apply {
                setBackgroundColor(Color.argb(0xF8, 0, 0, 0))
                isClickable = true
                setOnClickListener { onUserInteraction() }
            }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            )
        runCatching { wm.addView(view, params) }
            .onSuccess {
                dimView = view
                dimShown = true
                Log.i("CarPlaySender", "DIMMER: 暗幕已显示")
                // 兜底：万一系统仍把暗幕投到虚拟屏（车机应用区），立刻撤掉——
                // 宁可手机不暗，也不能整块盖黑车机画面（2026-10-26 实车黑屏根因）。
                view.post {
                    val id = view.display?.displayId ?: Display.DEFAULT_DISPLAY
                    if (id != Display.DEFAULT_DISPLAY) {
                        Log.w(TAG, "暗幕被投到非默认屏 display=$id，已撤下")
                        hideDim()
                    }
                }
            }.onFailure { Log.w(TAG, "暗幕添加失败：${it.message}") }
    }

    private fun hideDim() {
        val view = dimView ?: return
        dimView = null
        dimShown = false
        runCatching { windowManager?.removeView(view) }
    }

    /**
     * 只作用于手机默认屏（display 0）的 WindowManager。
     *
     * 直接用传入 context 的 `getSystemService(WINDOW_SERVICE)` 在本机（鸿蒙）实测会
     * 漂到 CarPlay 虚拟屏上：45s 无操作后弹出的全屏黑幕盖在应用屏
     * （GridActivity，displayId 2748）之上，桌面/卡片/页点整体变黑，触摸一下
     * （暗幕 onClick 撤下）才恢复——用户反馈的「黑屏」即此（2026-10-26 实车验证：
     * dumpsys window 里本包的 fillxfill APPLICATION_OVERLAY 窗口 mDisplayId=2748）。
     * 暗幕/触摸探针的语义本来只针对手机屏，故显式取默认屏的 DisplayContext。
     */
    private fun defaultDisplayWindowManager(context: Context): WindowManager? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        return context
            .createDisplayContext(display)
            .getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    /** 1px 触摸探针：捕获屏幕上任意处的 DOWN 事件用于重置计时，不消费事件。 */
    private fun addWatcher() {
        val ctx = appContext ?: return
        val wm = windowManager ?: return
        val view =
            View(ctx).apply {
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN ||
                        event.action == MotionEvent.ACTION_OUTSIDE
                    ) {
                        onUserInteraction()
                    }
                    false
                }
            }
        val params =
            WindowManager
                .LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
        runCatching { wm.addView(view, params) }
            .onSuccess { watchView = view }
            .onFailure { Log.w(TAG, "触摸探针添加失败：${it.message}") }
    }

    // ---- 屏幕常亮：改系统屏幕超时为永不，断开时恢复 ----

    private fun enableStayAwake(ctx: Context) {
        Bg.run("dimmer-stayawake") {
            val current =
                Shell
                    .exec("settings get system screen_off_timeout")
                    .output
                    .trim()
                    .toIntOrNull()
            if (current == null) {
                Log.w(TAG, "读取屏幕超时失败（Shizuku 未就绪？）")
                return@run
            }
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (current < TIMEOUT_MAX && !prefs.contains("screen_off_timeout_backup")) {
                prefs.edit().putInt("screen_off_timeout_backup", current).apply()
            }
            Shell.exec("settings put system screen_off_timeout $TIMEOUT_MAX")
            Log.i("CarPlaySender", "DIMMER: 屏幕超时设为永不（原值 $current）")
        }
    }

    private fun restoreTimeout(ctx: Context) {
        Bg.run("dimmer-restore") {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.contains("screen_off_timeout_backup")) return@run
            val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
            prefs.edit().remove("screen_off_timeout_backup").apply()
            Shell.exec("settings put system screen_off_timeout $backup")
            Log.i("CarPlaySender", "DIMMER: 屏幕超时恢复为 $backup")
        }
    }
}
