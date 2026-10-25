package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * 车机外观主题：「自动 / 白天 / 黑夜」三档，默认自动。
 *
 * 自动档当前跟随**手机系统**的深浅色（AppCompat 的 FOLLOW_SYSTEM 即此语义）；
 * 车机侧的昼夜检测信号接入后，由 [carNightSignal] 覆盖系统判断——届时只需在
 * 收到车机 night mode 时调用 [setCarNightSignal]，其余逻辑不用改。
 *
 * 主题通过 AppCompatDelegate 的全局 night mode 生效：车机侧布局/drawable 引用
 * `car_*` 颜色令牌（values = 白天，values-night = 黑夜），切换后相关 Activity
 * 会重建一次，壁纸版本同时自增以让桌面/dock 重新解码对应昼夜的默认壁纸。
 */
object ThemePrefs {
    const val AUTO = 0
    const val DAY = 1
    const val NIGHT = 2

    private const val PREFS_NAME = "settings"
    private const val KEY_MODE = "theme_mode"

    /** 显式选择白天/黑夜时，是否把手机系统的深色开关也切到同一档。 */
    private const val KEY_SYNC_SYSTEM = "theme_sync_system_night"

    /** 车机昼夜信号（true=夜间，false=白天，null=尚未收到）。 */
    @Volatile
    private var carNightSignal: Boolean? = null

    fun mode(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, AUTO)
            .takeIf { it in AUTO..NIGHT } ?: AUTO

    fun setMode(
        context: Context,
        mode: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MODE, mode.coerceIn(AUTO, NIGHT))
            .apply()
        apply(context)
        applySystemNight(context)
    }

    /** 「同步手机系统深色」开关（默认关；开启后高德车机版等应用才会跟着变）。 */
    fun syncSystemNight(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYNC_SYSTEM, false)

    fun setSyncSystemNight(
        context: Context,
        on: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SYNC_SYSTEM, on)
            .apply()
        applySystemNight(context)
    }

    /**
     * 把显式的白天/黑夜同步到**手机系统**的深色开关（`cmd uimode night`）。
     *
     * 为什么需要它：高德车机版这类被镜像到虚拟屏的应用只读自己进程的
     * uiMode，而本应用的主题只改自己的 Resources/AppCompat，穿不过去；
     * Android 12 没有可用的 per-app 夜间模式（`cmd uimode` 无该子命令），
     * 唯一可靠通路是系统级切换。默认关闭，由用户在「外观主题」里显式开启。
     * 自动档不写系统（自动本来就是跟随系统，写了会互相打架）。
     */
    fun applySystemNight(context: Context) {
        if (!syncSystemNight(context)) return
        val mode = mode(context)
        if (mode == AUTO) return
        val want = if (mode == NIGHT) "yes" else "no"
        Bg.run("system-night") {
            val result =
                runCatching {
                    io.github.shaonialife.cardeck.shizuku.Shell.exec("cmd uimode night $want", 5_000)
                }.getOrNull()
            io.github.shaonialife.cardeck.SessionController.appendLog(
                "主题: 系统深色同步 → $want" +
                    (
                        if (result == null) {
                            "（Shizuku 不可用）"
                        } else {
                            "（exit=${result.exitCode} ${result.output.trim().take(80)}）"
                        }
                    ),
            )
        }
    }

    /**
     * 车机昼夜检测信号接入点（预留）：传 null 表示信号丢失、退回跟随手机系统。
     * 自动档下立即重新应用；白天/黑夜档不受影响（用户显式选择优先）。
     */
    fun setCarNightSignal(
        context: Context,
        night: Boolean?,
    ) {
        if (carNightSignal == night) return
        carNightSignal = night
        apply(context)
    }

    /** 当前是否夜间（按已生效的资源配置判断——AppCompat 已把 night mode 落到 config）。 */
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 手机系统当前是否夜间。
     *
     * 不能用应用/Activity 的 Resources：`applyToActivity` 为了兜底会调用
     * `Resources.updateConfiguration`，而 Activities/Application 共享同一个
     * ResourcesImpl——改完再读就是「自证」，自动档会永远停在最后一次强制的值。
     * 因此先读 UiModeManager 的显式档位，自动/定时档再读系统资源（两者都不受本应用改写影响）。
     */
    private fun systemNight(context: Context): Boolean {
        // 1) 用户显式设过深浅色：UiModeManager 的档位就是答案（不受本应用 Resources 改写影响）
        when (
            context.getSystemService(android.app.UiModeManager::class.java)?.nightMode
        ) {
            android.app.UiModeManager.MODE_NIGHT_YES -> return true
            android.app.UiModeManager.MODE_NIGHT_NO -> return false
        }
        // 2) 自动/定时档：读系统资源里已计算的 uiMode（同样独立的 ResourcesImpl）
        val uiMode = android.content.res.Resources.getSystem().configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 偏好对应的目标昼夜（不读已生效配置，避免被下面的强制覆盖污染）：
     * 白天/夜间档取用户选择；自动档取车机信号，没有信号时跟随手机系统。
     */
    private fun desiredNight(context: Context): Boolean =
        when (mode(context)) {
            DAY -> false
            NIGHT -> true
            else -> carNightSignal ?: systemNight(context)
        }

    /** 目标昼夜（供车机 Activity 判断是否需要重建；[desiredNight] 的公开只读版）。 */
    fun targetNight(context: Context): Boolean = desiredNight(context)

    private val nightListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** 注册「自动档下手机系统昼夜变化」监听（车机 Activity 收到后按需重建）。 */
    fun addNightListener(listener: (Boolean) -> Unit) {
        nightListeners.addIfAbsent(listener)
    }

    fun removeNightListener(listener: (Boolean) -> Unit) {
        nightListeners.remove(listener)
    }

    /** 上次已广播的系统昼夜（防重复：配置回调可能被自身 updateConfiguration 再次触发）。 */
    @Volatile
    private var lastBroadcastNight: Boolean? = null

    /**
     * 系统配置变化回调（SenderApp 注册 ComponentCallbacks）：
     * 自动档下手机深浅色变了就重新落一次主题并通知车机界面重建；显式档不跟随。
     */
    fun refreshFromSystem(context: Context) {
        if (mode(context) != AUTO || carNightSignal != null) return
        val night = systemNight(context)
        if (lastBroadcastNight == night) return
        lastBroadcastNight = night
        apply(context)
        nightListeners.forEach { it(night) }
    }

    /**
     * 车机 Activity 在 onCreate（super 之后、inflate 之前）调用：把本 Activity 的
     * 昼夜资源**显式**落到 Resources 上。
     *
     * 为什么需要它：车机界面跑在 VirtualDisplay 上，其 Resources 的配置来自该显示屏
     * （跟随系统夜间模式）。AppCompatDelegate 的 night 覆盖不一定能传到虚拟屏的资源，
     * 届时「白天」选了仍是深色——看起来像昼夜配色搞反。这里以偏好为准兜底，
     * 且只在配置不一致时才改（AppCompat 正常生效时是空操作）。
     */
    fun applyToActivity(activity: android.app.Activity) {
        val res = activity.resources
        val conf = Configuration(res.configuration)
        val night = desiredNight(activity)
        val mask =
            if (night) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
        if ((conf.uiMode and Configuration.UI_MODE_NIGHT_MASK) == mask) return
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mask
        @Suppress("DEPRECATION")
        res.updateConfiguration(conf, res.displayMetrics)
        io.github.shaonialife.cardeck.SessionController.appendLog(
            "主题: 车机界面资源强制为${if (night) "夜间" else "白天"}（偏好=${mode(activity)}）",
        )
    }

    /** 把偏好落到 AppCompat 全局 night mode（Application onCreate 与设置页切换时调用）。 */
    fun apply(context: Context) {
        // 默认壁纸选哪张由这里显式下发：与各调用方 Context 的 uiMode 解耦，
        // 避免主题切换时旧 Activity 用过期配置污染壁纸缓存（见 WallpaperStore.nightMode）
        WallpaperStore.setNight(desiredNight(context))
        if (mode(context) == AUTO && carNightSignal == null) {
            // 记下已落地的系统昼夜：避免下一次无关的配置回调被当成「系统昼夜变了」
            lastBroadcastNight = desiredNight(context)
        }
        val target =
            when (mode(context)) {
                DAY -> AppCompatDelegate.MODE_NIGHT_NO
                NIGHT -> AppCompatDelegate.MODE_NIGHT_YES
                // 自动：把当下系统昼夜**解析后显式下发**（而不是 FOLLOW_SYSTEM）。
                // 虚拟屏 Activity 的配置未必等于系统配置，FOLLOW_SYSTEM 会让各屏各算一套；
                // 显式档保证三块屏 + 壁纸用同一个值，系统变化时由 refreshFromSystem 重算。
                else ->
                    if (desiredNight(context)) {
                        AppCompatDelegate.MODE_NIGHT_YES
                    } else {
                        AppCompatDelegate.MODE_NIGHT_NO
                    }
            }
        if (AppCompatDelegate.getDefaultNightMode() == target) return
        AppCompatDelegate.setDefaultNightMode(target)
    }
}
