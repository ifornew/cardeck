package io.github.shaonialife.cardeck.launcher

import android.content.Context
import io.github.shaonialife.cardeck.render.SPLIT_MAP_WIDTH_RATIO

/**
 * dock 栏 / 仪表盘偏好（SharedPreferences 持久化 + 监听器通知）。
 *
 *  - scrimOpacity：dock 暗幕强度 0..100（%黑；0=壁纸清晰可见，100=全黑）
 *  - blur：背景高斯模糊强度 0..100（0=不模糊；映射到 RenderEffect 模糊半径）
 *  - splitRatio：横屏仪表盘地图/音乐卡分屏比例 50..80（%；地图占应用区宽度，音乐卡占剩余）
 *
 * splitRatio 同时写入 DualDisplayComposer.SPLIT_MAP_WIDTH_RATIO（@Volatile，GL 线程每帧重绘读一次），
 * 无需重启虚拟屏即可生效。监听器在主线程回调。
 */
object DockPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_SCRIM = "dockScrimOpacity"
    private const val KEY_BLUR = "dockBlur"
    private const val KEY_SPLIT = "dockSplitRatio"

    /** 暗幕强度默认值（%）。 */
    const val DEFAULT_SCRIM = 20

    /** 模糊强度默认值（%）。 */
    const val DEFAULT_BLUR = 40

    /** 横屏仪表盘分屏比例默认值（%地图占比；50..80）。 */
    const val DEFAULT_SPLIT = 75

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun scrimOpacity(context: Context): Int = prefs(context).getInt(KEY_SCRIM, DEFAULT_SCRIM).coerceIn(0, 100)

    fun blur(context: Context): Int = prefs(context).getInt(KEY_BLUR, DEFAULT_BLUR).coerceIn(0, 100)

    /** 横屏仪表盘地图占比（%；50..80）。 */
    fun splitRatio(context: Context): Int = prefs(context).getInt(KEY_SPLIT, DEFAULT_SPLIT).coerceIn(50, 80)

    fun setScrimOpacity(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit().putInt(KEY_SCRIM, value.coerceIn(0, 100)).apply()
        notifyListeners()
    }

    fun setBlur(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit().putInt(KEY_BLUR, value.coerceIn(0, 100)).apply()
        notifyListeners()
    }

    /** 设置横屏仪表盘地图占比（50..80）；同时更新 DualDisplayComposer.SPLIT_MAP_WIDTH_RATIO。 */
    fun setSplitRatio(
        context: Context,
        value: Int,
    ) {
        val clamped = value.coerceIn(50, 80)
        prefs(context).edit().putInt(KEY_SPLIT, clamped).apply()
        SPLIT_MAP_WIDTH_RATIO = clamped / 100f
        notifyListeners()
    }

    /** 进程启动时调用：把持久化的 splitRatio 同步到 DualDisplayComposer 全局变量。 */
    fun syncRatio(context: Context) {
        SPLIT_MAP_WIDTH_RATIO = splitRatio(context) / 100f
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }
}
