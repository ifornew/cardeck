package io.github.shaonialife.cardeck.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）**主动连接车机**的无线 iAP2 RFCOMM 客户端 —— 兜底通道。
 *
 * 两条真实建链方向并存（均已在真车验证）：
 *  - **车机回连手机**（BMW 82772，2026-10-24 iPhone 抓包定谳）：HFP 完成 SLC 后，
 *    车机 SDP 查询手机并回连 `…cafe`（由 [Iap2BluetoothServer] 监听并应答）。
 *    这是真 iPhone 的形态，也是主路径。
 *  - **手机主动连入车机**（奥迪 MIB3）：连车机 SDP 广播的配件端服务
 *    `…caff`。仅作兜底：主路径等待宽限期结束后仍未收到回连才启用。
 */
class Iap2BluetoothClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 目标车机（动态读取：选择后无需重启链路，下一轮尝试即生效）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    /**
     * 本轮是否已收到过车机回连（…cafe 主路径）。为 true 时客户端**不再主动拨号**：
     * BMW 实测 …caff 方向必被 DM，而每轮兜底都要先断开 HFP/A2DP——那正是车机回连
     * 的触发前提，反复断开只会把车机的配对/重连窗口掐掉。
     */
    private val inboundSeen: () -> Boolean = { false },
) {
    companion object {
        /**
         * 配件端（车机）无线 iAP2 服务 UUID。
         * 手机端 UUID 是 …cafe（xcertplay IAP2_IPHONE_UUID），配件端是 …caff。
         */
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SESSION_TIMEOUT_MILLIS = 90_000L
        private const val RETRY_DELAY_MILLIS = 3_000L
        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L

        /** 「蓝牙已连接→已断开」日志节流（车机可能反复自动重连，避免刷屏）。 */
        private const val PROFILE_LOG_THROTTLE_MILLIS = 15_000L

        /**
         * 保持 HFP/A2DP 在线、等待车机回连 …cafe 的宽限期。
         * iPhone 真机抓包：车机在 HFP SLC 完成后 1–2 秒即回连；20s 足够覆盖
         * 车机 CarPlay 栈较慢的固件，超时才退回主动连入兜底。
         */
        private const val PROFILE_GRACE_MILLIS = 20_000L

        /** SDP 服务探测等待窗（fetchUuidsWithSdp → ACTION_UUID 广播）。 */
        private const val SDP_PROBE_TIMEOUT_MILLIS = 2_500L
    }

    private var thread: Thread? = null
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)

    /**
     * 客户端线程世代号：start()/stop() 都会递增。卡在阻塞 connect() 里的线程被
     * stop() 解除阻塞后（关闭 connecting socket），会因世代不符直接退出——
     * 防止「stop 后线程残活、用户再次启动时新旧两代并发连车机」
     * （2026-10-11 实测：多个僵尸线程并发 RFCOMM 会让车机频繁拆链）。
     */
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0)

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    /** 目标设备不在已配对列表的提示节流（避免每 3 秒刷屏）。 */
    private var lastTargetMissingLogAt = 0L

    /** 「无 iAP2 服务→跳过」日志去重（每地址一次即可，重复轮询不刷屏）。 */
    private val skippedNoService = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 「是否提供 iAP2 服务」结论缓存（地址→结论）：SDP 查询不便宜，勿每 3s 重查。 */
    private val iap2ServiceCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 设备是否提供配件端 iAP2 服务（…caff）：先读系统 uuids 缓存，缺失再
     * `fetchUuidsWithSdp` 触发一次 SDP 查询并等 `ACTION_UUID` 广播（超时按无服务）。
     * 结论缓存到客户端停止——车机固件升级换 UUID 的场景由重启 App 覆盖。
     * 权限：start() 已校验 BLUETOOTH_CONNECT；uuids/fetchUuidsWithSdp 的
     * SecurityException 由 runCatching 吸收（探测失败按无服务处理，不断音频）。
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(device: BluetoothDevice): Boolean {
        iap2ServiceCache[device.address]?.let { return it }

        fun cachedHas(): Boolean =
            runCatching {
                device.uuids?.any { it == ACCESSORY_IAP2_UUID } == true
            }.getOrDefault(false)

        if (cachedHas()) {
            iap2ServiceCache[device.address] = true
            return true
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: android.content.Intent,
                ) {
                    @Suppress("DEPRECATION")
                    val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (hit?.address == device.address) latch.countDown()
                }
            }
        val started =
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    android.content.IntentFilter(BluetoothDevice.ACTION_UUID),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                device.fetchUuidsWithSdp()
            }.getOrDefault(false)
        if (started) {
            try {
                latch.await(SDP_PROBE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        runCatching { context.unregisterReceiver(receiver) }
        val has = cachedHas()
        iap2ServiceCache[device.address] = has
        return has
    }

    /** 「蓝牙已连接→已断开」日志节流时间戳。 */
    private var lastProfileLogAt = 0L

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT-C: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-C: 蓝牙不可用或未开启")
            return false
        }
        // 每轮启动重置 SDP 结论与跳过日志（新配对/换固件后重新探测）
        iap2ServiceCache.clear()
        skippedNoService.clear()
        running = true
        val myGeneration = generation.incrementAndGet()
        val target = targetProvider()
        log(
            "BT-C: 启动——将主动连入车机的无线 iAP2 服务（…caff）" +
                if (target != null) {
                    "，目标：「${target.name}」${target.address}（只连该设备）"
                } else {
                    "（未指定目标：依次尝试全部候选设备，可在「连接准备」中指定）"
                },
        )
        Thread({ connectLoop(myGeneration) }, "iap2-bt-client").apply {
            isDaemon = true
            thread = this
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop(myGeneration: Long) {
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            } ?: return
        var lastFailureLogAt = 0L
        // 宽限起点取**本轮启动时刻**，而不是「首次观察到配置文件在线」：后者存在竞态——
        // 预检 connectedNames 返回空、紧随其后的 disconnect() 内部再查已在线，于是
        // 车机刚连上 HFP 就被断开（2026-10-24 19:11:24 日志实测：HFP 上线 1.6s 即被断）。
        var graceStartedAt = android.os.SystemClock.uptimeMillis()
        /** 配置文件「本轮在线」的起点；每次上线都会重置宽限基准（见下方说明）。 */
        var profilesOnlineSince: Long? = null
        var graceOpenedLogged = false
        var inboundOnlyLogged = false
        while (running && generation.get() == myGeneration) {
            // 静默降噪（2026-10-10 用户实测后启用）——会话期间完全静默，不产生任何连接尝试/日志：
            //  - inboundActive：接收端正在连我们（两条 RFCOMM 会互相踩；Audi 实测双会话混乱）
            //  - sessionActive：CarPlay 会话已建立（已拿到端点）——与其它接收端的连接尝试全部暂停
            //    典型场景：连着 Redmi/DiPlay 时不再无脑探测车机 Audi（消除日志噪音与蓝牙churn）；
            //    会话结束（任一端 BT 会话终结 / 手动断开）时自动恢复。
            if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                sleepQuietly(2_000)
                continue
            }
            // 本轮已成功回连过（车机走 iPhone 形态）→ 只保持监听。
            // BMW 82772 实测：…caff 方向 100% 被 DM；而兜底每轮都会先断开 HFP/A2DP，
            // 那正是车机回连 …cafe 的触发前提——「断开→拨号→失败」循环会把车机的
            // 配对/重连窗口全部掐掉（2026-10-25：首次配对反复失败、车机把手机移出
            // 配对列表，用户手动「取消连接→启动连接」才恢复）。回连过一次就不再拨。
            if (inboundSeen()) {
                if (!inboundOnlyLogged) {
                    inboundOnlyLogged = true
                    log(
                        "BT-C: 车机已成功回连过——转为只等回连，不再主动拨 …caff" +
                            "（避免反复断开 HFP 干扰车机配对窗口）",
                    )
                }
                sleepQuietly(2_000)
                continue
            }
            val candidates = candidateDevices(adapter)
            if (candidates.isEmpty()) {
                log("BT-C: 没有已配对的蓝牙设备——请先在系统设置里与车机完成蓝牙配对")
                sleepQuietly(RETRY_DELAY_MILLIS * 3)
                continue
            }
            var connected: BluetoothSocket? = null
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                val name =
                    try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    } ?: device.address
                runCatching { adapter.cancelDiscovery() }
                // 自动模式（未选目标）先过 SDP 闸门：候选=全部已配对设备，耳机/手表
                // 没有 iAP2 服务，连不上还白白被断 HFP/A2DP（设备级断开兜底更会波及
                // 其它在线设备）——确认提供 …caff 才进入「断开+连接」流程。选定目标
                // 的设备不过闸（用户显式指定，保持原路径与时序）。
                if (targetProvider() == null && !awaitIap2Service(device)) {
                    if (skippedNoService.add(device.address)) {
                        log("BT-C: 跳过「$name」（未发现 iAP2 服务——非 CarPlay 设备，保持其音频连接）")
                    }
                    continue
                }
                // 真车时序（2026-10-24 iPhone 抓包定谳）：车机在 **HFP 完成 SLC 之后**
                // 才 SDP 查询并回连手机 …cafe。HFP 刚连上就把它断开，等于把触发条件
                // 掐掉——车机永不回连，旧策略（立即断开 + 主动拨 …caff）必然吃 DM。
                // 规则：**每一次**配置文件上线都给满宽限（保持 HFP/A2DP，等服务端收
                // 回连）；宽限过后才断开并走主动连入兜底。车机随后自动重连又会获得
                // 新的宽限窗口——避免「宽限一过就秒断、把后续每次 SLC 都掐掉」的循环
                // （2026-10-24 20:34/20:39 日志实测）。
                val online = BtProfiles.connectedNames(context, device.address)
                val now = android.os.SystemClock.uptimeMillis()
                if (online.isEmpty()) {
                    profilesOnlineSince = null
                } else if (profilesOnlineSince == null) {
                    profilesOnlineSince = now
                    graceStartedAt = now
                    log(
                        "BT-C: $name 蓝牙在线（${online.joinToString("/")}）——保持连接，" +
                            "等待车机回连 iAP2（…cafe，宽限 ${PROFILE_GRACE_MILLIS / 1000}s）",
                    )
                }
                if (now - graceStartedAt < PROFILE_GRACE_MILLIS) {
                    if (!graceOpenedLogged) {
                        graceOpenedLogged = true
                        log(
                            "BT-C: 先保持蓝牙配置文件在线 ${PROFILE_GRACE_MILLIS / 1000}s，" +
                                "期间不主动拨 …caff（车机需先完成 HFP SLC 才会回连）",
                        )
                    }
                    continue
                }
                if (online.isNotEmpty()) {
                    log(
                        "BT-C: 宽限 ${PROFILE_GRACE_MILLIS / 1000}s 内未收到车机回连——" +
                            "断开 ${online.joinToString("/")}，改走主动连入（…caff）兜底",
                    )
                }
                // 兜底路径：断开配置文件后主动连入车机（奥迪 MIB3 实测需要「只配对、不连接」）。
                run {
                    val dropped = BtProfiles.disconnect(context, device)
                    if (dropped.isNotEmpty()) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                            lastProfileLogAt = now
                            val left = BtProfiles.stillConnected(context, device.address)
                            log(
                                "BT-C: $name 处于蓝牙已连接状态（${dropped.joinToString("/")}）——已断开" +
                                    if (left.isEmpty()) "" else "（仍有 ${left.joinToString("/")} 在线）",
                            )
                            log("BT-C: 车机在「蓝牙已连接」状态下不接受 CarPlay——保持仅配对")
                        }
                    }
                }
                val socket = tryConnect(device)
                if (socket != null) {
                    log("BT-C: ✓ 已连入 $name 的无线 iAP2 服务（RFCOMM 建立）")
                    connected = socket
                    break
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        // 带上真实失败原因与配对状态：车机删了配对而手机仍留旧密钥时，
                        // 症状就是每轮「弹配对→验证密钥→不支持 CarPlay」，但错误行只写
                        // 「未就绪」会掩盖这条线索（2026-10-23 实车）。
                        val bond =
                            when (runCatching { device.bondState }.getOrDefault(-1)) {
                                BluetoothDevice.BOND_BONDED -> "已配对"
                                BluetoothDevice.BOND_NONE -> "未配对"
                                BluetoothDevice.BOND_BONDING -> "配对中"
                                else -> "未知"
                            }
                        // 顺带把对端的 SDP 服务清单一并打出来：车机若根本不广播
                        // …caff，connect 必然被拒（"read failed … read ret: -1"），
                        // 与「设备不支持 CarPlay」是同一件事的两面（2026-10-24 实车）。
                        runCatching { device.fetchUuidsWithSdp() }
                        val uuids = runCatching { device.uuids }.getOrNull()
                        val sdp =
                            when {
                                uuids == null -> "SDP 缓存为空"
                                uuids.any { it.uuid == ACCESSORY_IAP2_UUID } ->
                                    "SDP 含 iAP2 服务（共 ${uuids.size} 条）"
                                else -> "SDP 不含 iAP2 服务（共 ${uuids.size} 条）"
                            }
                        log(
                            "BT-C: 连接 $name 失败（$bond；$sdp${lastConnectFailure?.let { "；$it" } ?: ""}），3 秒后重试…",
                        )
                    }
                }
            }
            if (connected == null) {
                sleepQuietly(RETRY_DELAY_MILLIS)
                continue
            }
            if (generation.get() != myGeneration) {
                // stop() 与 connect() 成功竞争：本线程已过期，不建会话
                currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
                return
            }
            runSession(connected)
            sleepQuietly(RETRY_DELAY_MILLIS)
        }
        log("BT-C: 客户端已停止")
    }

    /**
     * 候选设备：优先 SDP 缓存里带配件端 UUID 的已配对设备；
     * 缓存缺失时退化为全部已配对设备（connect 时的 SDP 查询会自行过滤）。
     * 指定了目标车机时**只返回该设备**（用户配对了多台车机时避免误连）。
     */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: android.bluetooth.BluetoothAdapter): List<BluetoothDevice> {
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        targetProvider()?.let { target ->
            // 目标策略：地址命中即唯一候选——不再按 SDP 缓存过滤（缓存可能缺失，
            // 而用户显式指定就该尝试）；目标不在已配对列表时给一次提示并空转等待。
            val hit = bonded.filter { TargetDevicePrefs.matches(target, it.address) }
            if (hit.isEmpty()) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTargetMissingLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                    lastTargetMissingLogAt = now
                    log(
                        "BT-C: 目标设备「${target.name}」(${target.address}) 不在已配对列表" +
                            "——请到系统蓝牙设置完成配对，或在「连接准备」改选目标",
                    )
                }
                return emptyList()
            }
            return hit
        }
        val withService =
            bonded.filter { device ->
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }
        return if (withService.isNotEmpty()) withService else bonded
    }

    /** 最近一次 RFCOMM 失败原因（供节流后的失败日志带上真实错误，而非笼统的「未就绪」）。 */
    @Volatile
    private var lastConnectFailure: String? = null

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val socket =
            try {
                device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            } catch (error: IOException) {
                lastConnectFailure = "建 socket 失败：${error.message}"
                return null
            }
        // 连接前就登记：connect() 是阻塞调用（SDP 查询 + 建链最长可达数十秒），
        // stop() 只有能拿到 socket 引用才能 close() 打断它，否则线程会滞留。
        currentSocket.set(socket)
        return try {
            socket.connect()
            socket
        } catch (error: IOException) {
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            lastConnectFailure = "connect 失败：${error.message}"
            null
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt-car") { line -> log(line) }
        activeChannel = channel
        try {
            channel.runSenderSession(context, SESSION_TIMEOUT_MILLIS, log, onEndpoint, onWifiCredentials, onEndpointUpdate)
        } catch (error: IOException) {
            log("BT-C: SESSION 失败 - ${error.message}")
        } finally {
            activeChannel = null
            currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
            runCatching { socket.close() }
            channel.close()
            log("BT-C: 本轮会话结束（客户端仍在运行，将自动重连）")
        }
    }

    /** 主动结束当前 BT 控制会话（不影响重连循环）。供 AirPlay 配对前复现控制通道退役状态。 */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT-C: 已主动断开当前控制会话")
        }
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    fun stop() {
        running = false
        // 递增世代：即便有线程卡在 connect()，事后也会因世代不符自杀，不会在下次启动时复活
        generation.incrementAndGet()
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
        activeChannel?.let { runCatching { it.close() } }
        thread?.interrupt()
        thread = null
        log("BT-C: 已停止")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }
}

/**
 * 入站会话门闸：车机主动连入的 iAP2 会话活跃期间，出站客户端暂停连接尝试。
 * 目的：车机通常只有单条无线 iAP2 上下文，双向同时建链会让双方互相干扰。
 */
object BtSessionGate {
    /** 入站（车机主动连入）的 iAP2 会话活跃：出站暂停，避免两条 RFCOMM 互相踩。 */
    @Volatile
    var inboundActive: Boolean = false

    /**
     * CarPlay 会话已建立（任一路径成功拿到端点后置位，由 SessionController 维护）：
     * 暂停一切出站连接尝试（静默降噪）——典型表现为连着 DiPlay 时不再探测 Audi。
     * 会话结束/手动断开时由 SessionController 清除。
     */
    @Volatile
    var sessionActive: Boolean = false
}
