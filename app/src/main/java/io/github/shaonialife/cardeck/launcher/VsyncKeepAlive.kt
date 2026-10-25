package io.github.shaonialife.cardeck.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 虚拟屏 VSYNC 保活（2026-10-23，待办 d：车机桌面不自动刷新、需手动滑动才显示最新内容）。
 *
 * 机制：View.invalidate() 后的绘制遍历要等**所在显示屏的下一个 VSYNC**；SurfaceFlinger
 * 对长期无内容产出的虚拟屏会停发 VSYNC（省电），于是 setText 已执行但遍历挂起不画，
 * 直到注入触摸唤醒该屏才一次性补画——「滑一下才刷新」的根因。
 *
 * 对策：放一个 1×1px 离屏 SurfaceView，每 [INTERVAL_MS] 向它的独立 layer 推一帧
 * （透明清屏）。显示屏持续有产出 → SurfaceFlinger 保持 VSYNC 流动 → 正常 invalidate
 * 即时绘制。代价约等于零（1 像素、1Hz）。真实显示屏（手机主屏）VSYNC 由硬件产生，
 * 无需保活——install() 按 displayId 自动跳过。
 *
 * 2026-10-25 追加：空闲整页重绘（[onIdleRefresh]）。虚拟屏长时间无触摸时，卡片上的
 * 裁剪层（clipToOutline）/模糊层（RenderEffect）会被系统回收、渲染成纯黑块，触摸
 * 才恢复（用户实测「音乐卡/地图卡不触摸一段时间就变黑」）。每 [IDLE_REFRESH_MS]
 * 无交互就整页 invalidate 一次，强制这些层重建；交互期间不打扰（省电、避免干扰
 * 正在操作的应用）。
 */
class VsyncKeepAlive(
    private val activity: Activity,
) {
    /**
     * 空闲整页重绘回调（可选）。调用方应在其中 invalidate 根视图；本类保证只在
     * 距上次 [noteInteraction] ≥ [IDLE_REFRESH_MS] 时触发。
     */
    var onIdleRefresh: (() -> Unit)? = null

    /** 最近一次用户交互时间（uptimeMillis）；[noteInteraction] 由 Activity 转发。 */
    @Volatile
    private var lastInteractionAt = android.os.SystemClock.uptimeMillis()

    /** 记录一次用户交互（Activity.onUserInteraction 转发），推迟空闲重绘。 */
    fun noteInteraction() {
        lastInteractionAt = android.os.SystemClock.uptimeMillis()
    }

    private var surfaceView: SurfaceView? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    private val tick =
        object : Runnable {
            override fun run() {
                if (!running) return
                poke()
                val idleMs = android.os.SystemClock.uptimeMillis() - lastInteractionAt
                if (idleMs >= IDLE_REFRESH_MS) onIdleRefresh?.invoke()
                handler.postDelayed(this, INTERVAL_MS)
            }
        }

    private val holderCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                running = true
                handler.removeCallbacks(tick)
                handler.post(tick)
                // 诊断：确认保活循环在虚拟屏上真的跑起来了（否则空闲重绘不会发生）
                VirtualLauncher.logSink?.invoke(
                    "VSYNC: 保活启动 display=${activity.display?.displayId}",
                )
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                running = false
                handler.removeCallbacks(tick)
                // 诊断：保活中断后「空闲重绘」也会一起停——出现此日志可解释变黑原因
                VirtualLauncher.logSink?.invoke("VSYNC: 保活停止（surface 销毁）")
            }
        }

    /** 挂进 Activity 内容树（虚拟屏才生效；真实屏自动跳过）。幂等。 */
    fun install() {
        if (surfaceView != null) return
        if (activity.display?.displayId == Display.DEFAULT_DISPLAY) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view =
            SurfaceView(activity).apply {
                layoutParams =
                    FrameLayout
                        .LayoutParams(1, 1)
                        .apply {
                            // 移出可视区：保活帧不参与观感
                            leftMargin = -4
                            topMargin = -4
                        }
                holder.addCallback(holderCallback)
            }
        root.addView(view)
        surfaceView = view
    }

    /** 与 Activity.onDestroy 对称调用。 */
    fun release() {
        running = false
        handler.removeCallbacks(tick)
        surfaceView?.let { view ->
            view.holder.removeCallback(holderCallback)
            (view.parent as? ViewGroup)?.removeView(view)
        }
        surfaceView = null
    }

    /** 推一帧：透明清屏后 post——只是为了让该显示屏保持「有产出」。 */
    private fun poke() {
        val holder = surfaceView?.holder ?: return
        val canvas =
            try {
                holder.lockCanvas()
            } catch (_: Throwable) {
                null
            } ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (_: Throwable) {
                // surface 刚销毁等竞态：下一拍自然恢复
            }
        }
    }

    private companion object {
        /** 保活节拍：1Hz 足以让 SurfaceFlinger 认定显示屏持续活跃。 */
        const val INTERVAL_MS = 1000L

        /** 无交互多久后开始整页重绘（用户实测 1 分钟内变黑，3s 足够跑在变黑之前）。 */
        const val IDLE_REFRESH_MS = 3_000L
    }
}
