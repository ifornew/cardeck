package io.github.shaonialife.cardeck.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.shaonialife.cardeck.R

/**
 * CarPlay 状态栏电池字形（自绘）。
 *
 * 为什么不用 FrameLayout + 描边 shape + 闪电 View 拼：dock 只有 42dp 宽，电池实际
 * 只有 ~30×15px、闪电 ~6×10px，用 View 组合抗锯齿后闪电会糊成一个「十字」。
 * 这里按像素直接画：1~2px 描边、按电量宽度的圆角填充、充电时实心闪电，与原生
 * iOS / CarPlay 状态栏字形一致。
 *
 * 颜色全部跟随主题文字色（白天黑 / 夜间白）：平时单色，充电 iOS 绿，≤20% iOS 红。
 */
class BatteryGlyphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var percent: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var charging: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val boltPath = Path()

    private val textColor: Int
        get() = ContextCompat.getColor(context, R.color.car_text_strong)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val stroke = (h * 0.10f).coerceAtLeast(1f)
        val nubW = w * 0.055f
        val gap = w * 0.035f
        val bodyW = w - nubW - gap
        val radius = h * 0.32f
        val half = stroke / 2f
        val fg = textColor

        // 外框（与填充同色，原生字形是单色的）
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = fg
        val body = RectF(half, half, bodyW - half, h - half)
        canvas.drawRoundRect(body, radius, radius, paint)

        // 电量填充：圆角、按百分比宽度；充电绿 / 低电量红 / 平时与文字同色
        val inner =
            RectF(
                body.left + stroke,
                body.top + stroke,
                body.right - stroke,
                body.bottom - stroke,
            )
        val innerRadius = (radius - stroke).coerceAtLeast(0.5f)
        val fillW = inner.width() * percent / 100f
        if (fillW >= 1f) {
            paint.style = Paint.Style.FILL
            paint.color =
                when {
                    charging -> COLOR_CHARGING
                    percent <= 20 -> COLOR_LOW
                    else -> fg
                }
            canvas.drawRoundRect(
                RectF(inner.left, inner.top, inner.left + fillW, inner.bottom),
                innerRadius,
                innerRadius,
                paint,
            )
        }

        // 正极触点
        val nubH = h * 0.34f
        paint.style = Paint.Style.FILL
        paint.color = fg
        canvas.drawRoundRect(
            RectF(bodyW + gap, (h - nubH) / 2f, w, (h + nubH) / 2f),
            nubH * 0.35f,
            nubH * 0.35f,
            paint,
        )

        if (charging) {
            drawBolt(canvas, inner, fg)
        }
    }

    /**
     * 电池内部闪电（原生充电图标）：Material「flash_on」的实心轮廓，
     * 按电池内高缩放到整数像素附近（小尺寸下描边折线会糊成十字，实心块才清晰）。
     */
    private fun drawBolt(
        canvas: Canvas,
        inner: RectF,
        fg: Int,
    ) {
        val boltH = inner.height() * 0.94f
        val boltW = boltH * 0.5f
        val left = inner.centerX() - boltW / 2f
        val top = inner.centerY() - boltH / 2f

        boltPath.reset()
        boltPath.moveTo(left, top)
        boltPath.lineTo(left, top + boltH * 0.55f)
        boltPath.lineTo(left + boltW * 0.30f, top + boltH * 0.55f)
        boltPath.lineTo(left + boltW * 0.30f, top + boltH)
        boltPath.lineTo(left + boltW, top + boltH * 0.40f)
        boltPath.lineTo(left + boltW * 0.60f, top + boltH * 0.40f)
        boltPath.lineTo(left + boltW * 0.60f, top)
        boltPath.close()

        paint.style = Paint.Style.FILL
        paint.color = fg
        canvas.drawPath(boltPath, paint)
    }

    private companion object {
        const val COLOR_CHARGING = 0xFF34C759.toInt()
        const val COLOR_LOW = 0xFFFF453A.toInt()
    }
}
