package io.github.shaonialife.cardeck.bt

import android.os.SystemClock

/**
 * 阶梯退避器（对照 CarDroid O5：0s → 5s → 30s → 2min → 10min → 30min）。
 *
 * 每次重挂失败调用 [nextDelayMs] 取下一次静默时长；成功连接后调 [reset] 归零。
 * 连续失败达到 [MAX_ATTEMPTS] 后 [isExhausted] 为 true，由调用方决定是否放弃。
 */
class ResidentBackoff {
    private var attemptIndex = 0
    private var lastAttemptAt = 0L

    /** 已用完所有阶梯（连续失败次数达到上限）。 */
    val isExhausted: Boolean get() = attemptIndex >= MAX_ATTEMPTS

    /** 当前阶梯序号（供日志展示）。 */
    val currentStep: Int get() = attemptIndex

    /**
     * 取下一次重挂前应静默的毫秒数。
     * 首次调用返回 0（立即重试），之后按阶梯递增。
     */
    @Synchronized
    fun nextDelayMs(): Long {
        val now = SystemClock.elapsedRealtime()
        // 距上次尝试不足 2s 视为同一轮震荡，不额外加退避
        if (now - lastAttemptAt < 2_000L && attemptIndex > 0) {
            return LADDER_MS[(attemptIndex - 1).coerceAtMost(LADDER_MS.lastIndex)]
        }
        lastAttemptAt = now
        val delay = LADDER_MS[attemptIndex.coerceAtMost(LADDER_MS.lastIndex)]
        if (attemptIndex < MAX_ATTEMPTS) attemptIndex++
        return delay
    }

    /** 连接成功：归零重试计数，下次异常断链从头开始。 */
    @Synchronized
    fun reset() {
        attemptIndex = 0
        lastAttemptAt = 0L
    }

    companion object {
        /** 阶梯：立即 → 5s → 30s → 2min → 10min → 30min（与 CarDroid O5 一致）。 */
        private val LADDER_MS = longArrayOf(0L, 5_000L, 30_000L, 120_000L, 600_000L, 1_800_000L)

        /** 最大重试次数（超过后 isExhausted=true）。 */
        private const val MAX_ATTEMPTS = 6
    }
}
