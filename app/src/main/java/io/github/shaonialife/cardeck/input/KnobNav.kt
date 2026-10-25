package io.github.shaonialife.cardeck.input

/**
 * 旋钮导航的**进程内**派发口（不经过 Shizuku 输入注入）。
 *
 * 2026-10-25 实车定谳：Shizuku 的 `injectInputEvent` 对虚拟屏（display=2529）
 * 既拒 `ACTION_SCROLL`（ROTARY/MOUSE 两种 source 都试过）也拒 `KEYCODE_DPAD_*`
 * （日志 `方控滚轮 delta=1 → RIGHT×1 注入失败（注入服务=在线）`），而触摸注入正常。
 * 键/滚动事件要求目标屏存在输入焦点，虚拟屏上的窗口不满足；桌面是我们自己的
 * Activity、与主控同一进程，直接回调即可——零权限、零焦点依赖。
 *
 * 桌面不在前台（第三方应用在跑）时不注册监听，调用方回退到按键注入。
 */
object KnobNav {
    interface Listener {
        /** 一齿一次，direction=+1 顺时针；返回 true = 已消费。 */
        fun onRotate(direction: Int): Boolean

        /** 旋钮按下；返回 true = 已消费。 */
        fun onPress(): Boolean
    }

    @Volatile
    private var listener: Listener? = null

    fun register(listener: Listener) {
        this.listener = listener
    }

    fun unregister(listener: Listener) {
        if (this.listener === listener) this.listener = null
    }

    fun rotate(direction: Int): Boolean = listener?.onRotate(direction) == true

    fun press(): Boolean = listener?.onPress() == true
}
