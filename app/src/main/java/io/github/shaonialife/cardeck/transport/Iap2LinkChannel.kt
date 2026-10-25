package io.github.shaonialife.cardeck.transport

import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 链路通道：一个读线程 + 一个节奏线程驱动 [Iap2LinkEngine]，向会话层暴露
 * 阻塞式的 sendControl/recvControl 与 awaitReady。
 */
class Iap2LinkChannel(
    private val stream: DuplexStream,
    name: String = "iap2-link",
    /**
     * 应答者模式：对端（车机）连入时由**对端**发起 SYN，本方只回 marker + SYN|ACK。
     * 真实无线 CarPlay 的蓝牙链路由车机主动发起（iPhone 抓包：车机发 marker → 手机
     * 回 marker → 车机 SYN → 手机 SYN|ACK）；若本方抢先发 SYN，就会与车机抢协商。
     */
    private val responder: Boolean = false,
    private val log: (String) -> Unit = {},
) : Closeable {
    private val engine = Iap2LinkEngine()
    private val lock = Object()
    private val controls = LinkedBlockingQueue<ByteArray?>()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var terminated = false
    private var readySignalled = false

    /** 应答者模式下「等不到对端 SYN 就改当发起方」的截止时刻（持 [lock] 访问）。 */
    private var responderFallbackAt: Long? = null

    /** 原始字节转储预算（每方向每连接），真车字节级对照用。 */
    private var rxDumpBudget = RAW_DUMP_LIMIT
    private var txDumpBudget = RAW_DUMP_LIMIT_TX

    /** 逐帧摘要日志预算（每方向每连接）。 */
    private var rxFrameLogs = 0
    private var txFrameLogs = 0

    val isAlive: Boolean get() = !terminated

    init {
        engine.echoMarker = responder
        engine.onPeerSynchronizationAdopted = { sync ->
            log(
                "LINK: 采纳对端会话表 maxOutgoing=${sync.maxOutgoing} maxLen=${sync.maxLength} " +
                    "sessions=" + sync.sessions.joinToString { "${it.id}/k${it.kind}/v${it.version}" },
            )
        }
        engine.onUnknownSessionData = { sessionId, size ->
            log("LINK: 丢弃未知会话 $sessionId 的数据帧（$size 字节）")
        }
        engine.onFrameReceived = { control, sequence, ack, sessionId, size ->
            // 真车调试关键观测：车机在识别阶段是否回帧、回什么（静默分歧全靠它定位）
            if (rxFrameLogs < FRAME_LOG_LIMIT) {
                rxFrameLogs++
                log(
                    "LINK rx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        engine.onFrameSent = { control, sequence, ack, sessionId, size ->
            if (txFrameLogs < FRAME_LOG_LIMIT) {
                txFrameLogs++
                log(
                    "LINK tx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        Thread({ runReader() }, "$name-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ runPacer() }, "$name-pacer").apply {
            isDaemon = true
            start()
        }
    }

    /** 等待链路协商完成（会话 10 可写）。 */
    fun awaitReady(timeoutMillis: Long): Boolean {
        if (!readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        return !terminated
    }

    /** 对端协商的最大控制载荷（已扣除帧头与校验和），协商前为 null。 */
    fun peerMaxControlPayload(): Int? = synchronized(lock) { engine.peerSynchronization?.maxLength?.minus(Iap2LinkEngine.HEADER_BYTES + 1) }

    /**
     * 已回显 SYN|ACK 的次数（含重放）。会话层在等上层消息时用它判断
     * 「对端是否在等待期间重新协商了链路」——同步边界前发出的消息会被对端丢弃。
     */
    val syncEchoCount: Int get() = engine.synchronizationEchoCount

    /** 排入一个完整控制载荷；链路已死返回 false。窗口满时内部积压，由 ACK 驱动发出。 */
    fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean = true,
    ): Boolean {
        synchronized(lock) {
            if (terminated) return false
            val accepted = engine.sendControl(bytes, nowMillis(), awaitAck)
            drainOutputLocked()
            return accepted
        }
    }

    /** 取下一个控制载荷；超时或链路结束返回 null。 */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        if (timeoutMillis == 0L && terminated && controls.isEmpty()) return null
        val polled =
            try {
                controls.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        return if (polled === null || polled === POISON) null else polled
    }

    override fun close() {
        finish(null)
    }

    // ---- 内部 ----

    private fun runReader() {
        try {
            synchronized(lock) {
                val now = nowMillis()
                if (responder) {
                    engine.startResponder(now)
                    responderFallbackAt = now + RESPONDER_FALLBACK_MILLIS
                    log("LINK: 应答者模式——先回 marker，等车机发 SYN")
                } else {
                    engine.start(now)
                }
                drainOutputLocked()
            }
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) {
                    finish(null)
                    return
                }
                if (count == 0) continue
                synchronized(lock) {
                    if (terminated) return
                    dumpInbound(buffer.copyOf(count))
                    engine.feed(buffer.copyOf(count), nowMillis())
                    processEventsLocked()
                    drainOutputLocked()
                    signalReadyLocked()
                }
            }
        } catch (error: Throwable) {
            finish(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * 原始字节抓包（真车字节级对照的唯一手段）：每方向仅转储前
     * [RAW_DUMP_LIMIT] 字节，防止长期会话日志爆炸。
     */
    private fun dumpInbound(bytes: ByteArray) {
        if (bytes.isEmpty() || rxDumpBudget <= 0) return
        val shown = minOf(bytes.size, rxDumpBudget)
        log("RAW rx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        rxDumpBudget -= shown
    }

    private fun runPacer() {
        while (!terminated) {
            val deadline: Long?
            synchronized(lock) {
                if (terminated) return
                engine.advanceTime(nowMillis())
                maybeInitiateFallbackLocked()
                processEventsLocked()
                drainOutputLocked()
                signalReadyLocked()
                deadline = engine.nextDeadlineMillis()
                if (deadline == null) {
                    try {
                        lock.wait(PACER_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
            if (deadline != null) {
                val sleep = (deadline - nowMillis()).coerceIn(1, PACER_POLL_MILLIS)
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /**
     * 持锁调用：应答者模式的兜底——[RESPONDER_FALLBACK_MILLIS] 内未收到车机的 SYN
     * （例如测试台把手机当发起方），改为发起方补发 marker + SYN，避免链路空等。
     */
    private fun maybeInitiateFallbackLocked() {
        val deadline = responderFallbackAt ?: return
        val now = nowMillis()
        if (engine.state != Iap2LinkEngine.State.NEGOTIATING) {
            responderFallbackAt = null
            return
        }
        if (now < deadline) return
        responderFallbackAt = null
        log("LINK: 对端未主动发起（${RESPONDER_FALLBACK_MILLIS}ms），改为发起方补发 marker+SYN")
        engine.initiate(now)
    }

    /** 持锁调用：抽取引擎事件并搬运输出。 */
    private fun processEventsLocked() {
        while (true) {
            when (val event = engine.pollEvent() ?: return) {
                is Iap2LinkEngine.Event.Control -> {
                    if (!controls.offer(event.bytes)) {
                        engine.feedEof()
                        return
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finishLocked(event.reason?.let { IOException(it) })
                    signalReadyLocked()
                    return
                }
            }
        }
    }

    private fun drainOutputLocked() {
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return
        dumpOutbound(bytes)
        try {
            stream.write(bytes)
        } catch (error: IOException) {
            finishLocked(error)
        }
        signalReadyLocked()
    }

    private fun dumpOutbound(bytes: ByteArray) {
        if (txDumpBudget <= 0) return
        val shown = minOf(bytes.size, txDumpBudget)
        log("RAW tx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        txDumpBudget -= shown
    }

    private fun signalReadyLocked() {
        if (!readySignalled && engine.state == Iap2LinkEngine.State.NORMAL) {
            readySignalled = true
            readyLatch.countDown()
        }
    }

    private fun finish(reason: String?) {
        synchronized(lock) { finishLocked(reason?.let { IOException(it) }) }
    }

    private fun finishLocked(failure: IOException?) {
        if (terminated) return
        terminated = true
        controls.offer(POISON) // 哨兵：唤醒阻塞的接收方（LBQ 不接受 null）
        readyLatch.countDown()
        lock.notifyAll()
        runCatching { stream.close() }
        if (failure != null) {
            log("LINK closed: ${failure.message}")
        } else {
            log("LINK closed")
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val PACER_POLL_MILLIS = 20L

        /** 原始字节转储预算：rx 需覆盖车机全部协商帧（Audi 实测约 140B），给 512B 余量。 */
        const val RAW_DUMP_LIMIT = 512
        const val RAW_DUMP_LIMIT_TX = 256

        /** 逐帧摘要日志上限（每方向每连接）。 */
        const val FRAME_LOG_LIMIT = 40

        /** 应答者模式等待车机 SYN 的时长；超时后改当发起方（兼容测试台时序）。 */
        const val RESPONDER_FALLBACK_MILLIS = 1_500L

        /** 链路终结哨兵；真实控制载荷至少包含一段 CSM 帧，不会为空。 */
        val POISON = ByteArray(0)
    }
}
