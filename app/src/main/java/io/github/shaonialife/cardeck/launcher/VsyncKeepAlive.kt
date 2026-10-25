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
 */
class VsyncKeepAlive(
    private val activity: Activity,
) {
    private var surfaceView: SurfaceView? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    private val tick =
        object : Runnable {
            override fun run() {
                if (!running) return
                poke()
                handler.postDelayed(this, INTERVAL_MS)
            }
        }

    private val holderCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                running = true
                handler.removeCallbacks(tick)
                handler.post(tick)
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
    }
}
