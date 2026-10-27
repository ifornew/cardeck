package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * 车机外观主题：「自动 / 白天 / 黑夜」三档，默认自动。
 *
 * 自动档优先跟随**车机**传来的昼夜信号（/command type=setNightMode，会话内有效），
 * 没有信号时退回跟随**手机系统**的深浅色。信号入口是 [setCarNightSignal]。
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

    /** 高德车机版昼夜广播协议（KEY_TYPE=10048，额外字段 0=自动/1=白天/2=黑夜）。 */
    private const val AMAP_PKG = "com.autonavi.amapauto"
    private const val AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_RECV"
    private const val AMAP_KEY_TYPE = 10048

    /** 已下发过高德的昼夜档（null=从未下发，用于避免开机/无信号时无谓启动高德进程）。 */
    @Volatile
    private var amapSentMode: Int? = null

    /**
     * 车机信号覆盖**系统**深色前的 UiModeManager 档位（null=未覆盖）。
     * 断开连接时据此还原，避免手机系统深色被车机信号「粘住」。
     */
    @Volatile
    private var systemNightBeforeCar: Int? = null

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
     * 把当前昼夜同步到**手机系统**的深色开关（`cmd uimode night`）。
     *
     * 为什么需要它：高德车机版这类被镜像到虚拟屏的应用只读自己进程的
     * uiMode，而本应用的主题只改自己的 Resources/AppCompat，穿不过去；
     * Android 12 没有可用的 per-app 夜间模式（`cmd uimode` 无该子命令），
     * 唯一可靠通路是系统级切换。默认关闭，由用户在「外观主题」里显式开启。
     *
     * 写入范围：
     *  - 显式「白天/黑夜」：按用户选择写；
     *  - 「自动」：只有收到车机昼夜信号时才写（此时"自动"指跟随车机）；
     *    没有信号时自动本就跟随手机系统，再写会互相打架。
     */
    fun applySystemNight(context: Context) {
        if (!syncSystemNight(context)) {
            // 用户关掉同步：把车机信号改过的系统档位还原
            restoreSystemNightForCar(context)
            return
        }
        val mode = mode(context)
        val night =
            when (mode) {
                DAY -> false
                NIGHT -> true
                else -> {
                    if (carNightSignal == null) return
                    desiredNight(context)
                }
            }
        if (mode == AUTO) {
            // 记下覆盖前的档位：断开后还原（只在首次覆盖时取）
            if (systemNightBeforeCar == null) {
                systemNightBeforeCar =
                    context.getSystemService(android.app.UiModeManager::class.java)?.nightMode
            }
        } else {
            // 显式档由用户接管，不再替车机还原
            systemNightBeforeCar = null
        }
        val want = if (night) "yes" else "no"
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
     * 还原车机信号覆盖前的系统昼夜档位（断开连接 / 关闭同步时）。
     * 只在确实由车机信号改过系统时执行，其余情况空操作。
     */
    private fun restoreSystemNightForCar(context: Context) {
        val original = systemNightBeforeCar ?: return
        systemNightBeforeCar = null
        val arg =
            when (original) {
                android.app.UiModeManager.MODE_NIGHT_YES -> "yes"
                android.app.UiModeManager.MODE_NIGHT_NO -> "no"
                android.app.UiModeManager.MODE_NIGHT_CUSTOM -> "custom"
                else -> "auto"
            }
        Bg.run("system-night-restore") {
            val result =
                runCatching {
                    io.github.shaonialife.cardeck.shizuku.Shell.exec("cmd uimode night $arg", 5_000)
                }.getOrNull()
            io.github.shaonialife.cardeck.SessionController.appendLog(
                "主题: 车机信号结束，系统深色还原 → $arg" +
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
     * 车机昼夜检测信号接入点：传 null 表示信号丢失、退回跟随手机系统。
     * 自动档下立即重新应用；白天/黑夜档不受影响（用户显式选择优先）。
     * 开启「同步系统深色」时，自动档下本信号也会驱动手机系统深色（高德等镜像应用据此变色）。
     */
    fun setCarNightSignal(
        context: Context,
        night: Boolean?,
    ) {
        if (carNightSignal == night) return
        carNightSignal = night
        apply(context)
        if (night == null) {
            restoreSystemNightForCar(context)
        } else if (mode(context) == AUTO) {
            applySystemNight(context)
        }
    }

    /**
     * 把当前昼夜**直接下发给高德车机版**（官方车机对接广播协议）。
     *
     * 为什么不能只切系统 uiMode：2026-10-27 实测，`cmd uimode night yes` 对虚拟屏上
     * **正在运行**的高德车机版完全无效（前后截图逐字节相同）；只有重启进程才读到新配置，
     * 而重启会中断导航。高德自己注册了标准广播接口，运行中即时生效。
     *
     * 协议：action=AUTONAVI_STANDARD_BROADCAST_RECV，KEY_TYPE=10048，
     * EXTRA_DAY_NIGHT_MODE：0=自动、1=白天、2=黑夜。
     */
    private fun pushAmapDayNight(
        context: Context,
        force: Boolean = false,
    ) {
        val target =
            when (mode(context)) {
                DAY -> 1
                NIGHT -> 2
                // 自动档：优先车机昼夜信号；没有信号时跟随手机系统深色。
                // 首次下发（force=false 且从未发过）不打扰高德，避免开机就拉起它的进程。
                else ->
                    when (carNightSignal) {
                        true -> 2
                        false -> 1
                        null ->
                            when {
                                force -> if (desiredNight(context)) 2 else 1
                                amapSentMode == null -> return
                                else -> 0
                            }
                    }
            }
        if (!force && amapSentMode == target) return
        amapSentMode = target
        Bg.run("amap-daynight") {
            val sent =
                runCatching {
                    context.sendBroadcast(
                        android.content.Intent(AMAP_ACTION)
                            .setPackage(AMAP_PKG)
                            .putExtra("KEY_TYPE", AMAP_KEY_TYPE)
                            .putExtra("EXTRA_DAY_NIGHT_MODE", target),
                    )
                    true
                }.getOrElse { false }
            io.github.shaonialife.cardeck.SessionController.appendLog(
                "主题: 高德昼夜广播 → " +
                    listOf("自动", "白天", "黑夜")[target] +
                    if (sent) "" else "（发送失败：高德车机版未安装？）",
            )
        }
    }

    /**
     * 高德刚（重新）启动时对齐一次当前昼夜：收到广播前启动的实例按它自己的设置渲染，
     * 运行中又收不到系统 uiMode 变化，只能重发协议广播。
     * 「自动」且没有车机信号时按手机系统深浅色下发，避免高德停在它自己的自动档。
     */
    fun reassertAmapDayNight(context: Context) {
        amapSentMode = null
        pushAmapDayNight(context, force = true)
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
        // 高德等镜像应用收不到系统 uiMode 变化，昼夜一律用高德自己的广播接口下发
        pushAmapDayNight(context)
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
