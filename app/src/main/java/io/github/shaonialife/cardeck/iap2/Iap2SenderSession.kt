package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import java.io.IOException
import java.security.SecureRandom

/**
 * iAP2 发送端（iPhone 角色）控制会话：完整走通无线 CarPlay 的 bring-up 序列，
 * 最终从 0x5703 / 0x4301 解析出接收端热点与 AirPlay 端点 —— Phase 1 的 Go/No-Go 交付。
 *
 * 序列（对照 reference/xcertplay Iap2WirelessControlClient 的对端行为）：
 *   0x1D00 → 0x1D01 → 0x1D02（识别）
 *   0xAA00 → 0xAA01 → 0xAA02 → 0xAA03 → 0xAA05（MFi，跳过证书/签名校验）
 *   [配件发来 5 个订阅帧 + 0x6800 StartHID，回 0x6801]
 *   0x5702 → 0x5703（拿热点 SSID/密码/信道）
 *   0x4300 → 0x4301（拿 AirPlay 端口/公钥/无线参数）
 *   0x4E0D + 0x4E0E（通知无线可用与传输标识）
 */
class Iap2SenderSession(
    private val channel: Iap2LinkChannel,
    private val log: (String) -> Unit,
    /** 本机真实蓝牙 MAC（0x4300/0x4E0E 的传输标识与 _carplay-ctrl 广播的 id；真车会校验）。 */
    private val bluetoothMac: String = "02:00:00:00:00:00",
    /** 本机蓝牙名（0x4E09 设备信息）。 */
    private val deviceName: String = "iPhone",
    /**
     * 收到 0x5703（热点凭据）时回调 —— 主控须立即：绑定车机 WiFi + 广播
     * `_carplay-ctrl._tcp`（带真实蓝牙 MAC）。真车（Audi MIB3）流程：
     * 车机在 WiFi 上发现该服务并 `GET /ctrl-int/1/connect` 邀请手机，
     * **邀请被接受后才发 0x4301**；若等到 0x4301 之后才广播则双方互等死锁。
     */
    private val onWifiCredentials: ((WifiConfiguration) -> Unit)? = null,
    /**
     * 0x4301 在 Phase 1 结束后才到达时的回调（车机打开 CarPlay 窗口的晚信号）。
     *
     * 保活泵此前把所有消息都当心跳只记长度：Audi MMI 9310 实测 0x4301 在
     * Phase 1 结束后 **2 分 35 秒**才到（车机侧窗口刚开），被日志吞掉后我们
     * 没有发起 AirPlay 连接，车机等 15 秒无人连入便拆掉整条 RFCOMM——
     * 用户看到的就是「连上之后自动断开」。收到就必须交给主控热更新端点/重试配对。
     */
    private val onEndpointUpdate: ((CarPlayEndpoint) -> Unit)? = null,
    /**
     * 仅用于 MFi 挑战长度的按车机持久化记忆（[MfiLengthMemory]）。null = 只用进程内
     * 内存表（单测/纯 JVM），不影响协议流程。
     */
    private val context: android.content.Context? = null,
) {
    /** 从 0x5703 + 0x4301 解析出的接收端信息。 */
    data class CarPlayEndpoint(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
        val airPlayPort: Int,
        val deviceIdentifier: String?,
        val publicKey: String?,
        val sourceVersion: String?,
        val ipAddresses: List<String>,
        /**
         * `_airplay._tcp` TXT 的 features 位图（高 32 位已并入）。
         *
         * 关键位：bit38 `HK_PAIRING_AND_ENCRYPT` = 支持 HomeKit 配对/加密。
         * null = 未知（只有 0x4301、没发现 mDNS）——按「需要配对」处理，保持旧行为。
         * catplay 用它区分三个时代的接收端：pre-HomeKit（CarPlay 210.81，无该位，
         * 只能 `et=NONE` 明文会话 + AES-CTR 屏幕流）/ 支持但可跳过（280 时代）/
         * 强制配对（现代）。2026-10-19 奥迪 4xxx（4851）连不上与这条分类直接相关。
         */
        val advertisedFeatures: Long? = null,
    )

    private val parser = Iap2Csm.Parser()

    /** 刚解析、尚未分类的帧（到达顺序）。 */
    private val receivedFrames = ArrayDeque<Iap2Csm.Frame>()

    /**
     * [expectOrNull] 等待期间取到的「非预期」帧暂存区；[recvFrame] 优先从这里取。
     *
     * **必须与 [receivedFrames] 分开**：两者若混用，expectOrNull 把非目标帧塞回
     * 它自己下一轮优先读取的队列，就会同一帧反复取出—记录—塞回形成活锁
     * （2026-10-18 实测：车机 bring-up 期间发 0x5000 StartNowPlayingUpdates，
     * 日志以 ~1000 行/秒刷屏直到等待超时）。
     */
    private val pendingFrames = ArrayDeque<Iap2Csm.Frame>()

    /** 暂存区上限：车机订阅类消息可能在长等待期间持续到达，无上限会吃内存。 */
    private val maxPendingFrames = 256
    private var pendingOverflowLogged = false

    /** 车机反向请求设备识别（0x1D00）是否已应答——每会话只答一次。 */
    private var deviceIdentificationAnswered = false

    private val random = SecureRandom()

    /** 0x5703 解析结果（保活期间车机重发 0x5702 时需重新应答）。 */
    @Volatile
    private var wifi: WifiConfiguration? = null

    /** 0x4301 原始体是否已记录（每轮会话一次，避免周期补发刷屏）。 */
    private var startSessionRawLogged = false

    /** 最近一次晚到 0x4301 的端点指纹（车机周期重发相同内容时不重复打详情）。 */
    private var lastLateStartSessionSignature: Int? = null

    /**
     * 0x1D01 参数 6：车机声明「它可发送」的消息 ID 清单。
     * 用它判断 0x6800 (StartHID) / 0x4301 是否真的会来——声明里没有就不该盲等：
     * 奥迪实测只声明 0x5703/0xea03，旧流程因此在 0x4301 上空等 10s、StartHID 上空等 2s。
     */
    private var accessorySendIds: Set<Int> = emptySet()

    /** 0x1D01 参数 7：车机声明「它可接收」的消息 ID 清单。 */
    private var accessoryReceiveIds: Set<Int> = emptySet()

    /** 识别帧是否真的带了接收清单（空清单与「未提供」必须区分）。 */
    private var receiveListDeclared = false

    /** 车机是否声明会发某消息；null = 未提供清单（未知，按保守策略等待）。 */
    private fun accessoryDeclaresSend(messageId: Int): Boolean? = if (accessorySendIds.isEmpty()) null else messageId in accessorySendIds

    /** 车机是否声明能收某消息；null = 未提供清单（未知，按保守策略继续）。 */
    private fun accessoryDeclaresReceive(messageId: Int): Boolean? = if (!receiveListDeclared) null else messageId in accessoryReceiveIds

    private companion object {
        /** 等 StartHID 的窗口：xcertplay 会发（订阅后立即到），DiPlay 不发。 */
        const val HID_WAIT_NANOS = 2_000_000_000L

        /** 等车机身份时的分片窗口：每片结束后检查链路是否重新协商过（见 expectIdentification）。 */
        const val RESYNC_POLL_NANOS = 1_500_000_000L

        /** 识别请求最多补发次数（防车机持续重发 SYN 时无限重发）。 */
        const val MAX_IDENTIFICATION_RESENDS = 2

        /**
         * 设备侧自报身份（对照 catplay 的 iPhone persona：`_device-info` model=D84AP）。
         * 车机（BMW 尤其严格）用这些字段 + 收/发消息清单判定「这台设备支持 CarPlay」。
         */
        const val DEVICE_MODEL_IDENTIFIER = "D84AP"
        const val DEVICE_FIRMWARE_VERSION = "17.6.1"

        /**
         * 等 0x4301 的短窗口（声明会发的测试台毫秒级应答；没声明的车机直接跳过）。
         * 只在「车机没给声明清单」时才会用满窗口——从 10s 收到 3s：真车整个
         * bring-up 只有几十秒预算，且实测应答都在毫秒级。
         */
        const val START_SESSION_WAIT_NANOS = 3_000_000_000L

        /**
         * 新协议车机（没有 0x5703，AP 信息只在 0x4301 里）的等待窗口。
         * 这类车机必须先把热点信息给手机，手机才可能入网——窗口太短等于直接判死；
         * BMW iD9 实测整个 bring-up 在 10 秒量级，给 8 秒。
         */
        const val START_SESSION_WAIT_NEW_PROTOCOL_NANOS = 8_000_000_000L

        /** 0x4301 原始体日志上限（Audi 实测 261B，完整记录；异常大的只截前段）。 */
        const val START_SESSION_RAW_LOG_BYTES = 1_024

        /**
         * 已知「静默过」的档位重试窗口。首次试探保留原窗口（可能是慢车机），
         * 但已经失败过一次的档位没必要再等满 6 秒——真 iPhone 应答在 100ms 级。
         */
        const val MFI_SILENT_RETRY_WINDOW_MS = 1_500L

        /** 挑战档位（长度, 首次试探窗口）：顺序与窗口由 DiPlay/Audi 双端实测决定。 */
        private val MFI_ATTEMPTS = listOf(32 to 3_000L, 20 to 6_000L, 16 to 3_000L)

        /**
         * 认证硬超时（对照 CarDroid「配件认证超过 15 秒，本轮结束」）。
         *
         * 证书与挑战**各自**给 15s 预算：车机装死时不能靠全局 60s 超时兜底，
         * 否则用户看到的是长时间无响应。两段合计最坏 30s，仍在可接受范围；
         * 挑战段内部本就按档位分窗（3s/6s/3s），正常车机远早于此。
         */
        const val AUTH_TIMEOUT_MS = 15_000L

        /** 真车系接收端特征（0x1D01 name/model/manufacturer 子串，小写比对）。 */
        private val LEGACY_RECEIVER_MARKERS = listOf("audi", "delphi", "mib", "harman", "porsche")
    }

    /** 从 0x1D01 提取接收端身份文本（name/model/manufacturer 小写拼接）——MFi 挑战长度选择用。 */
    private fun identityText(frame: Iap2Csm.Frame): String {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return listOfNotNull(
            params[0]?.value?.asStringOrEmpty(),
            params[1]?.value?.asStringOrEmpty(),
            params[2]?.value?.asStringOrEmpty(),
        ).joinToString(" ").lowercase()
    }

    fun run(timeoutMillis: Long = 60_000L): CarPlayEndpoint {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        if (!channel.awaitReady(remainingMillis(deadline))) {
            throw IOException("iAP2 链路协商超时")
        }
        log("iap2 link ready peerMaxControl=${channel.peerMaxControlPayload()}")

        // ---- 识别 ----
        send(Iap2MessageIds.START_IDENTIFICATION)
        val identification = expectIdentification(deadline)
        logIdentification(identification)
        send(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
        log("iap2 tx=0x1d02 identification-accepted")

        // EA 协议解析（启动时机见下方 MFi 完成后——2026-10-11 DiPlay 实测：
        // 认证前发 0xEA00 会让测试台状态机停摆，不再回 0xAA01；奥迪对此不敏感）
        val eaProtocols = parseEaProtocols(identification)

        // ---- MFi（跳过校验：0xAA05 自报成功）----
        val identityText = identityText(identification)
        val legacyCar = LEGACY_RECEIVER_MARKERS.any { identityText.contains(it) }
        if (legacyCar) {
            log("iap2 识别为真车系（$identityText）——MFi 首选 20B（真 iPhone 实录长度；32B 对真车处置不稳定）")
        }
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
        // 认证硬超时（对照 CarDroid）：车机不回 0xAA01 时不再等满全局 60s。
        val certificate = expect(
            Iap2MessageIds.MFI_CERTIFICATE,
            minOf(deadline, System.nanoTime() + AUTH_TIMEOUT_MS * 1_000_000L),
        )
        log("iap2 rx=0xaa01 certificate bytes=${certificate.body.size}（跳过校验）")
        // 挑战长度按接收端身份选择（2026-10-11 奥迪字节级实测修正）：
        //  - 真车系（Audi/Delphi MIB 等）：**20B 首试**。真 iPhone 实录（carplay-wifi-extractor
        //    client）与 catplay 发送端都用 20B；奥迪对 20B 秒答。32B 先行会导致车机
        //    ACK 挑战帧后**整段静默**（不回 ACK、不回应答，会话变僵尸）——本日多轮实测，
        //    32B→20B 的会话全部卡死，20B→（记忆命中）的会话全部秒通。
        //  - 测试台 DiPlay（Carlinkit AuthV3 EC 身份）：**只接受精确 32B**，长度不符直接断链。
        // 全部无应答也不中止——我们不校验签名（0xAA05 自报），由 0x5702 是否应答判定放行。
        runMfiChallenge(
            minOf(deadline, System.nanoTime() + AUTH_TIMEOUT_MS * 1_000_000L),
            prefer20First = legacyCar,
            receiverKey = identityText,
        )
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
        log("iap2 tx=0xaa05 authentication-succeeded（自报）")

        // 0x4E0C 会话 UUID：真 iPhone 与 0xAA05 同帧发出（2026-10-22 iPhone↔BMW 蓝牙抓包
        // 实测：aa05+4e0c+5702 同帧），早于 WiFi 配置交换——不放问候批里。
        send(Iap2MessageIds.DEVICE_UUID_UPDATE, Iap2Csm.string(0, deviceUuid()))
        log("iap2 tx=0x4e0c 会话 UUID（紧随 0xaa05，对齐真机）")

        // ---- EA 会话启动（MFi 认证完成后）----
        // 奥迪 0x1D01 声明 de.audi.mmicconnectdatatransfer / com.audi.cpp.eadata0/1
        // 三个 EA 协议；触摸 HID 相关数据经 EA 会话。认证前发送会噎死 DiPlay（见上）。
        for ((protocolId, name) in eaProtocols) {
            sendFireAndForget(
                Iap2MessageIds.EA_OPEN_SESSION,
                Iap2Csm.u8(0, protocolId) + Iap2Csm.u16(1, protocolId),
            )
            log("iap2 tx=0xea00 startEASession protocol=$protocolId name=$name")
        }

        // ---- 订阅与 HID：0x6800 是可选步骤 ----
        // xcertplay 在订阅后发 StartHID；DiPlay 删掉了这一步（订阅完直接等 0x5702）。
        // 因此这里只短暂等待：收到 0x6800 就应答 0x6801，2 秒内没等到就直接继续。
        awaitOptionalHid(deadline)

        // ---- WiFi 配置交换（两代协议）----
        // 旧协议（0x5702 → 0x5703）：手机请求，车机回 AP 明文——Audi MIB3 走这条。
        // 新协议（0x4300 → 0x4301）：**AP 信息由 0x4301 自带**（Addendum 新版的
        // WirelessCarPlayParams），BMW iD9 走这条——它在 0x1D01 里既不声明能收 0x5702，
        // 也不声明会发 0x5703。旧流程对这类车机会在 0x5703 上死等 10 秒后拆链
        // （2026-10-19 BMW92438 实车日志：每轮都是「等待 0x5703 超时」）。
        // 因此按车机声明的能力清单分派：不支持旧协议就直接跳到 0x4300/0x4301。
        val declaresReceiveWifi =
            accessoryDeclaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
        val declaresSendWifi = accessoryDeclaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)
        val legacyWifiExchange = declaresReceiveWifi != false && declaresSendWifi != false
        val wifiFromExchange: WifiConfiguration? =
            if (!legacyWifiExchange) {
                log(
                    "iap2 车机未声明支持 0x5702/0x5703（收=${declaresReceiveWifi ?: "未知"} " +
                        "发=${declaresSendWifi ?: "未知"}）——跳过旧协议，AP 信息由 0x4301 提供",
                )
                null
            } else {
                send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                val wifiConfiguration = expectOrNull(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, deadline)
                if (wifiConfiguration == null) {
                    log("iap2 0x5703 未在窗口内到达——改走 0x4300/0x4301（新版协议自带 AP 信息）")
                    null
                } else {
                    val parsed = parseWifiConfiguration(wifiConfiguration)
                    log(
                        "iap2 rx=0x5703 ssid=${parsed.ssid} channel=${parsed.channel} " +
                            "security=${parsed.securityType} psk=${if (parsed.passphrase.isEmpty()) "(空)" else "***"}",
                    )
                    // 真车死锁解药（catplay CarPlayInviter 实证）：车机在 WiFi 网络上发现手机的
                    // _carplay-ctrl._tcp 后主动发 GET /ctrl-int/1/connect 邀请，**邀请成功才发 0x4301**。
                    // 因此这里必须立即通知主控绑 WiFi + 广播 ctrl（带真实蓝牙 MAC 作 TXT id）。
                    onWifiCredentials?.invoke(parsed)
                    parsed
                }
            }

        // ---- 设备状态问候（2026-10-22 iPhone↔BMW 蓝牙抓包实测顺序：
        // 0x5703 拿到凭证之后 → 0x4E0A 语言 → 0x4E0B 时间 → 0x4E0D CarPlay 状态 → 0x4E09 设备名；
        // 0x4E0C 已提前到 0xAA05 同帧，不在本批）----
        // Audi MIB3 实测：缺这批消息时，车机对 0x4300 只回链路 ACK、不出 0x4301。
        send(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE, Iap2Csm.string(0, "zh-CN"))
        val epochSeconds = System.currentTimeMillis() / 1000L
        val timezoneMinutes =
            java.util.TimeZone
                .getDefault()
                .getOffset(System.currentTimeMillis()) / 60_000
        send(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2Csm.param(0, u64Bytes(epochSeconds)) +
                Iap2Csm.u16(1, timezoneMinutes and 0xFFFF) +
                Iap2Csm.u8(2, 0),
        )
        send(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE, Iap2Csm.u8(0, 1))
        send(Iap2MessageIds.DEVICE_INFORMATION_UPDATE, Iap2Csm.string(0, deviceName))
        log(
            "iap2 tx=0x4e0a/0x4e0b/0x4e0d/0x4e09 设备状态问候（真机顺序）" +
                "（name=$deviceName mac=$bluetoothMac）",
        )

        // ---- CarPlay 会话启动 ----
        // 两种对端形态：
        //  - 测试台 DiPlay/部分车厂：收 0x4300 后回 0x4301（含 AirPlay 端口/IP/公钥）
        //  - Audi MIB3：识别清单只声明发送 0x5703/0xea03，**不认 0x4300、不发 0x4301**；
        //    AirPlay 端点走 WiFi 上的 mDNS（_airplay._tcp）。因此 0x4301 只给短窗口，
        //    超时返回无端点的 CarPlayEndpoint，由主控 mDNS 合成。
        // 0x4300 同样发后不管：2026-10-11 12:52 轮实测车机曾对它停止 ACK
        // （事件通道 SYN 洪水把它打懵的时段），链路看门狗险些再次自杀。
        sendFireAndForget(
            Iap2MessageIds.CARPLAY_AVAILABILITY,
            Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, bluetoothMac)),
        )
        // 车机声明里没有 0x4301 就直接跳过等待（Audi 实测每轮白等 10s）：
        // 真 iPhone 对这类车机也不等——AirPlay 端点经 mDNS/网关合成。
        val declaresStartSession = accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)
        // 没拿到 0x5703（新协议车机）时，0x4301 是**唯一**的 AP 信息来源——窗口给足；
        // 真有 0x5703 的旧协议车机保持 3 秒短窗口（应答都在毫秒级）。
        val startSessionWindow =
            if (wifiFromExchange == null) START_SESSION_WAIT_NEW_PROTOCOL_NANOS else START_SESSION_WAIT_NANOS
        var startSession: Iap2Csm.Frame? = null
        if (declaresStartSession == false) {
            log("iap2 车机未声明会发 0x4301——跳过等待（AirPlay 端点走 mDNS/网关）")
        } else {
            // 车机可能只发 0x4301 而完全不回答 0x5702：发送后先等一轮，
            // 若这段时间里 0x4301 先到了（新协议的正常顺序），直接采用。
            startSession =
                expectOrNull(
                    Iap2MessageIds.CARPLAY_START_SESSION,
                    minOf(deadline, System.nanoTime() + startSessionWindow),
                )
        }
        val endpoint =
            if (startSession != null) {
                parseStartSession(startSession, wifiFromExchange)
            } else {
                // 不能断言「该车机不发」：Audi MMI 9310 声明会发、也确实发，只是晚到
                // 2 分 35 秒（2026-10-18 日志）。措辞必须保留「稍后可能补发」的预期，
                // 否则排查时会被这句话误导成「这条路对本车不通」。
                log(
                    if (declaresStartSession == true) {
                        "iap2 0x4301 在 ${startSessionWindow / 1_000_000_000}s 窗口内未到" +
                            "（车机声明会发——可能稍后补发，保活期间收到会重新触发配对）——" +
                            "先用 mDNS/网关"
                    } else {
                        "iap2 0x4301 未到（车机未声明会发；AirPlay 端点走 mDNS _airplay._tcp）"
                    },
                )
                if (wifiFromExchange == null) {
                    // 既没有 0x5703、也没有 0x4301 = 一点 AP 信息都没有，继续等只会空转。
                    throw IOException("未拿到车机热点凭据：该车机既不支持 0x5703，0x4301 也未提供 AP 信息")
                }
                CarPlayEndpoint(
                    ssid = wifiFromExchange.ssid,
                    passphrase = wifiFromExchange.passphrase,
                    channel = wifiFromExchange.channel,
                    securityType = wifiFromExchange.securityType,
                    airPlayPort = 0,
                    deviceIdentifier = null,
                    publicKey = null,
                    sourceVersion = null,
                    ipAddresses = emptyList(),
                )
            }
        if (startSession != null) {
            logStartSessionRaw(startSession)
            log(
                "iap2 rx=0x4301 airPlayPort=${endpoint.airPlayPort} " +
                    "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                    "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}",
            )
        }

        // 真机顺序：0x4301 之后回 0x4E0E（传输标识确认）。
        // Audi 实测：车机对该消息**接受但从不 ACK**（其余包毫秒级 ACK）——必须发后不管，
        // 否则重传看门狗在 +20s 把整条蓝牙链路杀掉（EA 会话/触摸载体随之丢失）。
        sendFireAndForget(
            Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
            Iap2Csm.string(0, bluetoothMac),
        )
        log("iap2 0x4e0d/0x4e0e/0x4300 —— Phase 1 完成：拿到热点凭据与（可能的）AirPlay 端点")

        // 后续（Phase 2）：加入热点 → mDNS 解析 _airplay._tcp → AirPlay 主会话。
        // 0x5703 缺席时 AP 信息来自 0x4301（已并入 endpoint）——保活期车机重发
        // 0x5703/0x4301 都要用它解析，不能留空。
        this.wifi =
            wifiFromExchange ?: WifiConfiguration(
                ssid = endpoint.ssid,
                passphrase = endpoint.passphrase,
                channel = endpoint.channel,
                securityType = endpoint.securityType,
            )
        return endpoint
    }

    /**
     * Phase 2 过渡：bring-up 完成后保持链路打开，持续记录配件发来的控制消息
     * （如 0x4E0E 后重发的 0x5703、位置请求 0xFFFA 等），直到链路断开。
     * 真实无线 CarPlay 中这条控制通道与 AirPlay 会话并存于整个连接生命周期。
     */
    fun pumpIncomingForever() {
        log("SESSION 保活中（链路保持打开，持续记录配件消息）")
        // 同一消息 ID 连续到达时限流：车机进入媒体播放后会周期性发 NowPlaying 更新，
        // 逐条打日志会把文件日志撑爆（文件超 2MB 直接删，等于丢掉真正有用的上下文）。
        var lastLoggedId = -1
        var repeatCount = 0
        while (true) {
            val frame = recvFrame(30_000L)
            if (frame == null) {
                if (!channel.isAlive) break
                continue
            }
            // 生命周期消息必须先于日志限流处理：0x4301 可能作为「车机刚打开 CarPlay
            // 窗口」的晚信号在 Phase 1 之后才到。若跟着限流逻辑走，它要么被当成
            // keep-alive 只记长度，要么因与上一条同 ID 被 continue 掉——两种都等于
            // 丢弃（2026-10-18 Audi MMI 9310「连上后 15 秒自动断开」的根因）。
            if (frame.messageId == Iap2MessageIds.CARPLAY_START_SESSION) {
                handleLateStartSession(frame)
            }
            try {
                maybeAnswerIdentification(frame)
            } catch (e: IOException) {
                log("iap2 应答设备识别失败（链路已断开）：${e.message}")
                break
            }
            if (frame.messageId == lastLoggedId) {
                repeatCount++
                if (repeatCount % 100 != 0) continue
            } else {
                if (repeatCount >= 99) {
                    log("iap2 上一条 0x${lastLoggedId.toString(16)} 重复 ${repeatCount + 1} 次（同类已限流）")
                }
                lastLoggedId = frame.messageId
                repeatCount = 0
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}) keep-alive bytes=${frame.body.size}",
            )
        }
        log("SESSION 保活结束（链路断开）")
    }

    /**
     * 保活期间收到 0x4301：解析成端点交给主控（配对窗口内热更新，或上一轮失败后重走）。
     * 没有 0x5703 缓存时（新协议车机：BMW iD9 只有 0x4301）AP 信息直接从 0x4301 的
     * 无线组解析——这正是这类车机唯一的凭据来源，不能像旧代码那样「无 0x5703 就忽略」。
     */
    private fun handleLateStartSession(frame: Iap2Csm.Frame) {
        logStartSessionRaw(frame)
        val endpoint = parseStartSession(frame, wifi)
        if (wifi == null && endpoint.ssid.isNotEmpty()) {
            wifi =
                WifiConfiguration(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    securityType = endpoint.securityType,
                )
            log("iap2 0x4301 自带 AP 信息：ssid=${endpoint.ssid}——已缓存，供后续 0x5703/0x4301 复用")
        }
        if (endpoint.ssid.isEmpty()) {
            log("iap2 rx=0x4301（晚到）——无线组里没解析出 SSID，本条忽略")
            return
        }
        // 车机可能周期性重发同一份 0x4301：详情只在端点变化时打，避免刷日志。
        if (endpoint.hashCode() != lastLateStartSessionSignature) {
            lastLateStartSessionSignature = endpoint.hashCode()
            log(
                "iap2 rx=0x4301（晚到，Phase 1 之后）airPlayPort=${endpoint.airPlayPort} " +
                    "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                    "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}" +
                    "——交给主控热更新端点/重试配对",
            )
        }
        onEndpointUpdate?.invoke(endpoint)
    }

    /** 0x4301 原始体只记一次：车机间端点字段差异（有无 IPv4/端口/公钥）只能靠它定谳。 */
    private fun logStartSessionRaw(frame: Iap2Csm.Frame) {
        if (startSessionRawLogged) return
        startSessionRawLogged = true
        val body = frame.body
        val shown = if (body.size <= START_SESSION_RAW_LOG_BYTES) body else body.copyOf(START_SESSION_RAW_LOG_BYTES)
        val truncated = if (body.size > shown.size) "（前 ${shown.size}/${body.size} 字节）" else ""
        log("iap2 0x4301 原始体$truncated=" + shown.joinToString("") { "%02x".format(it) })
    }

    // ---- 帧收发 ----

    /**
     * MFi 挑战应答：按「对端可接受的长度」依次尝试，每次失败（0xAA04 / 静默超时 / 断链）换下一档。
     * 基础顺序与窗口由两端实测决定（2026-10-09/10，缺一不可）：
     *  - **32B 优先**：测试台 DiPlay（Carlinkit AuthV3 EC 身份）**只接受精确 32B**，
     *    长度不对会直接断开链路（实测 70~350ms 内断链）；其应答快（~0.2s），3s 窗口足够。
     *  - **20B 次之**：真车 Audi MIB3 对 20B 秒答（catplay/extractor 真车实录同款）；
     *    对 32B 的处置两种都出现过——10-09 静默不断链、10-11 拆链。
     *  - **16B 兜底**：部分旧配件。
     *
     * 顺序**以按车机持久化的记忆为准**（[MfiLengthMemory]，基线只作冷启动与兜底）：
     * 2026-10-19 奥迪 MMI 3315 实测 20B 静默 6 秒、32B 0.64 秒应答——只靠基线
     * 「真车都 20B 优先」每次连接都白等 6 秒。记忆命中后直接把上次成功的长度排第一。
     * 全部无应答时不抛异常直接返回——我们跳过签名校验，由后续 0x5702 是否获得应答
     * 判定对端是否放行（真车实测无应答后 0xaa05 仍可推进）。
     */
    private fun runMfiChallenge(
        deadline: Long,
        prefer20First: Boolean = false,
        receiverKey: String,
    ) {
        val baselineLengths =
            if (prefer20First) {
                listOf(MFI_ATTEMPTS[1].first, MFI_ATTEMPTS[0].first, MFI_ATTEMPTS[2].first)
            } else {
                MFI_ATTEMPTS.map { it.first }
            }
        val windowOf = MFI_ATTEMPTS.associate { it.first to it.second }
        val memory = MfiLengthMemory.load(context, receiverKey)
        // 只发一档的情形（对齐真 iPhone：它每轮只发**一个** 20B 挑战）：
        //  - 已知成功长度：不再用其它长度试探。错误长度会让车机拆链
        //    （BMW NBTevo 实测：32B/16B 期间对端直接关掉 iAP2 链路）；
        //  - 从不回应：直接跳过挑战（见 MfiLengthMemory.neverAnswered）。
        val order =
            if (memory.succeeded != null) {
                log("iap2 MFi 该车机已确定挑战长度 ${memory.succeeded}B——本轮只发这一档（不再用其它长度试探）")
                listOf(memory.succeeded!!)
            } else if (MfiLengthMemory.neverAnswered(memory, baselineLengths)) {
                log(
                    "iap2 MFi 该车机从未应答任何挑战（记忆：${MfiLengthMemory.describe(memory)}）" +
                        "——跳过 0xaa02，直接 0xaa05 自报",
                )
                return
            } else {
                MfiLengthMemory.orderAttempts(baselineLengths, memory)
            }
        if (!memory.isEmpty && memory.succeeded == null) {
            log(
                "iap2 MFi 按车机记忆（$receiverKey）：${MfiLengthMemory.describe(memory)}" +
                    " → 本轮顺序 ${order.joinToString("/")}B",
            )
        }
        for ((position, size) in order.withIndex()) {
            // 已知「静默过」的档位缩短窗口：它已经失败过一次，没必要再等满。
            val baseWindow = windowOf[size] ?: MFI_ATTEMPTS.last().second
            val windowMillis =
                if (size in memory.silent && size != memory.succeeded) {
                    minOf(baseWindow, MFI_SILENT_RETRY_WINDOW_MS)
                } else {
                    baseWindow
                }
            val challenge = ByteArray(size).also(random::nextBytes)
            send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
            log("iap2 tx=0xaa02 challenge=${size}B（第 ${position + 1} 次，窗口 ${windowMillis}ms）")
            val window = minOf(System.nanoTime() + windowMillis * 1_000_000L, deadline)
            var gotResponse = false
            var gotFailure = false
            while (true) {
                val remaining = (window - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                val frame = recvFrame(remaining)
                if (frame == null) {
                    if (!channel.isAlive) {
                        log("iap2 MFi 挑战期间链路断开（${size}B，对端不接受该长度）——记入该车机被拒档位")
                        MfiLengthMemory.recordRejected(context, receiverKey, size)
                        return
                    }
                    continue
                }
                when (frame.messageId) {
                    Iap2MessageIds.MFI_RESPONSE -> {
                        log("iap2 rx=0xaa03 signature bytes=${frame.body.size}（跳过校验）")
                        gotResponse = true
                    }

                    Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> {
                        log("iap2 rx=0xaa04 挑战 ${size}B 被拒")
                        gotFailure = true
                    }

                    else -> {
                        maybeAnswerIdentification(frame)
                        log(
                            "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                                "(${Iap2MessageIds.nameOf(frame.messageId)})",
                        )
                    }
                }
                if (gotResponse || gotFailure) break
            }
            if (gotResponse) {
                MfiLengthMemory.recordSuccess(context, receiverKey, size)
                return
            }
            if (gotFailure) {
                MfiLengthMemory.recordRejected(context, receiverKey, size)
            } else {
                MfiLengthMemory.recordSilent(context, receiverKey, size)
            }
            if (position < order.size - 1) {
                log("iap2 MFi ${size}B 无应答，改用 ${order[position + 1]}B 重试")
            } else {
                log("iap2 MFi 挑战全部无应答——跳过校验直接发 0xaa05（由 0x5702 判定车机是否放行）")
            }
        }
    }

    private fun send(
        messageId: Int,
        vararg body: ByteArray,
    ) {
        val joined =
            when (body.size) {
                0 -> ByteArray(0)
                1 -> body[0]
                else -> body.reduce { acc, bytes -> acc + bytes }
            }
        val frame = Iap2Csm.encode(messageId, joined)
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end))) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')}")
    }

    /** [send] 的发后不管变体：链路不因对端不 ACK 此消息而重传/死亡（见 0x4E0E）。 */
    private fun sendFireAndForget(
        messageId: Int,
        body: ByteArray,
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end), awaitAck = false)) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')}（发后不管）")
    }

    private fun expect(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException(timeoutMessage(messageId))

    /**
     * 「等不到消息」的两种成因必须分开报，否则日志会误导排查：
     *  - 链路已死（对端拆 socket / 本地取消）：[expectOrNull] 在 `!channel.isAlive`
     *    时返回 null，此时并不是等满窗口，而是链路被拆了；
     *  - 真超时：链路还在，只是对端一直不回。
     * 2026-10-25 的失败轮里，绝大多数「等待 0x1d01 超时」实际是前者（用户取消或
     * 车机主动 close），被当成协议超时排查了好几轮。
     */
    private fun timeoutMessage(messageId: Int): String {
        val id = "0x${messageId.toString(16).padStart(4, '0')}"
        return if (channel.isAlive) {
            "等待 $id 超时（链路仍在线，对端未应答）"
        } else {
            "等不到 $id：链路已被对端断开或本地关闭"
        }
    }

    /**
     * 等车机身份（0x1D01），并在等待期间容忍**链路重新协商**。
     *
     * 车机重发 SYN（链路层不接受首帧 SYN|ACK）时，它会重建同步边界；
     * 边界**之前**发出的 0x1D00 因此被它的会话层丢弃——即使传输层已经 ACK
     * （2026-10-25 实测：车机 ACK 了 seq=101 的 0x1D00，却始终不回 0x1D01，
     * 随后彻底静默）。所以每次发现新的 SYN|ACK 回显，就补发一遍识别请求，
     * 最多 [MAX_IDENTIFICATION_RESENDS] 次。
     */
    private fun expectIdentification(deadline: Long): Iap2Csm.Frame {
        var echoBaseline = channel.syncEchoCount
        var resends = 0
        while (true) {
            val slice = minOf(deadline, System.nanoTime() + RESYNC_POLL_NANOS)
            val frame = expectOrNull(Iap2MessageIds.IDENTIFICATION_INFORMATION, slice)
            if (frame != null) return frame
            if (!channel.isAlive) throw IOException(timeoutMessage(Iap2MessageIds.IDENTIFICATION_INFORMATION))
            if (System.nanoTime() >= deadline) {
                throw IOException(timeoutMessage(Iap2MessageIds.IDENTIFICATION_INFORMATION))
            }
            val echoes = channel.syncEchoCount
            if (echoes != echoBaseline && resends < MAX_IDENTIFICATION_RESENDS) {
                echoBaseline = echoes
                resends++
                log("iap2 车机重新同步（累计回显 $echoes 次）——补发 0x1d00（第 $resends 次）")
                send(Iap2MessageIds.START_IDENTIFICATION)
            }
        }
    }

    /**
     * 同 [expect]，但超时返回 null（用于可选消息）。非预期帧进暂存区供后续消费。
     *
     * 只从 [receivedFrames] 读新帧，**不重读自己刚暂存的帧**：旧实现两者共用一个
     * 队列，非预期帧被塞回后立刻又被本函数取出，形成活锁（见 pendingFrames 注释）。
     * 目标帧若已在暂存区（早到），先从这里取，保证不漏。
     */
    private fun expectOrNull(
        messageId: Int,
        deadlineNanos: Long,
    ): Iap2Csm.Frame? {
        pendingFrames.firstOrNull { it.messageId == messageId }?.let {
            pendingFrames.remove(it)
            return it
        }
        while (true) {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return null
            val frame = recvNewFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return null
                continue
            }
            if (frame.messageId == messageId) return frame
            if (maybeAnswerIdentification(frame)) {
                // 已应答的 0x1D00 不入暂存区：暂存后会在其他等待循环被二次消费，
                // 触发重复应答（第二条 0x1D01 会打乱车机的会话状态机）
                continue
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)})，非预期，暂存待后续消费",
            )
            pendingFrames += frame
            if (pendingFrames.size > maxPendingFrames) {
                pendingFrames.removeFirst()
                if (!pendingOverflowLogged) {
                    pendingOverflowLogged = true
                    log("iap2 非预期帧暂存区超过 $maxPendingFrames 条——丢弃最旧的（车机订阅类消息过多）")
                }
            }
        }
    }

    private fun awaitOptionalHid(deadline: Long) {
        // 声明清单里没有 StartHID 就不等（DiPlay/奥迪实测均不发，旧流程每轮白等 2s）。
        if (accessoryDeclaresSend(Iap2MessageIds.START_HID) == false) {
            log("iap2 车机未声明会发 0x6800 StartHID——跳过 2s 等待")
            return
        }
        val hidWindowNanos = minOf(System.nanoTime() + HID_WAIT_NANOS, deadline)
        while (true) {
            val remaining = (hidWindowNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) {
                log("iap2 未收到 0x6800（该接收端不发 StartHID，继续）")
                return
            }
            val frame = recvFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return // 链路已死：交给后续 expect 报出明确错误
                continue
            }
            when (frame.messageId) {
                Iap2MessageIds.START_HID -> {
                    log("iap2 rx=0x6800 start-hid bytes=${frame.body.size}")
                    send(
                        Iap2MessageIds.DEVICE_HID_REPORT,
                        Iap2Csm.u16(0, 0),
                        Iap2Csm.param(1, byteArrayOf(0)),
                    )
                    return
                }

                else -> {
                    maybeAnswerIdentification(frame)
                    log(
                        "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)})",
                    )
                }
            }
        }
    }

    /**
     * 通用取帧（MFi 挑战 / HID 等待 / 保活泵）：优先消费暂存的非预期帧
     * （保持到达顺序：暂存 > 新解析），再读新帧。
     */
    private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        pendingFrames.firstOrNull()?.let {
            pendingFrames.removeFirst()
            return it
        }
        return recvNewFrame(timeoutMillis)
    }

    /** 只取新帧（先已解析队列、再读 socket）；绝不返回暂存区的帧。 */
    private fun recvNewFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        receivedFrames.firstOrNull()?.let {
            receivedFrames.removeFirst()
            return it
        }
        if (timeoutMillis <= 0) return null
        val bytes = channel.recvControl(timeoutMillis) ?: return null
        val frames = parser.offer(bytes)
        if (frames.isEmpty()) return null
        frames.forEachIndexed { index, frame -> if (index > 0) receivedFrames += frame }
        return frames.first()
    }

    // ---- 解析 ----

    /** 从 0x5703 解析出的热点凭据（收到后应立即绑 WiFi + 广播 _carplay-ctrl）。 */
    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
    )

    private fun parseWifiConfiguration(frame: Iap2Csm.Frame): WifiConfiguration {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return WifiConfiguration(
            ssid = params[1]?.value?.asStringOrEmpty() ?: "",
            passphrase = params[2]?.value?.asStringOrEmpty() ?: "",
            securityType =
                params[3]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
            channel =
                params[4]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
        )
    }

    /**
     * 解析 0x4301。AP 信息有两种来源：
     *  - 已通过 0x5703 拿到（旧协议车机）→ 直接用；
     *  - 车机只在 0x4301 的无线组里给（新协议车机，如 BMW iD9）→ 从组内解析。
     *
     * 新协议无线组与 CarPlay Addendum 的 AccessoryWiFiConfigurationInformation 同构
     * （0/1=SSID/密码、2=信道、3=IP、4=安全类型），但个别实现从 1 起编号（本项目 0x5703
     * 解析即 1/2/3/4）——两种都兼容：前两个「非 IP」的 NUL 结尾字符串依次当 SSID/密码，
     * 单字节字段按取值范围归类。原始字节每次都打日志，真车实测后可直接校正。
     */
    private fun parseStartSession(
        frame: Iap2Csm.Frame,
        wifi: WifiConfiguration?,
    ): CarPlayEndpoint {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val wirelessGroup = params[1]?.value
        val wireless = wirelessGroup?.let { Iap2Csm.parseParams(it) }
        val addresses =
            wireless
                ?.filter { it.id == 3 }
                ?.mapNotNull { param -> param.value.asStringOrEmpty()?.takeIf { it.isNotEmpty() } }
                .orEmpty()
        log(
            "iap2 0x4301 顶层参数: " +
                params.entries.joinToString(" ") { (id, param) -> "#$id(${param.value.size}B)" },
        )
        if (wireless != null) {
            log(
                "iap2 0x4301 无线组参数: " +
                    wireless.joinToString(" ") { param ->
                        "#${param.id}=" + param.value.joinToString("") { "%02x".format(it) }
                    },
            )
        }
        val fromSession = wireless?.let { parseWirelessAp(it) }
        val ap = wifi ?: fromSession
        if (wifi == null && fromSession != null) {
            log(
                "iap2 0x4301 自带 AP：ssid=${fromSession.ssid} channel=${fromSession.channel} " +
                    "security=${fromSession.securityType} psk=${if (fromSession.passphrase.isEmpty()) "(空)" else "***"}",
            )
        }
        return CarPlayEndpoint(
            ssid = ap?.ssid ?: "",
            passphrase = ap?.passphrase ?: "",
            channel = ap?.channel ?: 0,
            securityType = ap?.securityType ?: 0,
            airPlayPort = params[2]?.value?.asU32() ?: 0,
            deviceIdentifier = params[3]?.value?.asStringOrEmpty(),
            publicKey = params[4]?.value?.asStringOrEmpty(),
            sourceVersion = params[5]?.value?.asStringOrEmpty(),
            ipAddresses = addresses,
        )
    }

    /**
     * 从 0x4301 的无线组里解析 AP 信息（新协议车机的唯一凭据来源）。
     *
     * 只取「不像 IP 地址」的 NUL 结尾字符串：第一个当 SSID、第二个当密码；
     * 单字节字段按取值范围归类（信道 1..196、安全类型 0..4），信道优先 id=2、
     * 其次 id=4/id=3 中不是安全类型的那个。解析不出来返回 null（调用方会报错并留日志）。
     */
    private fun parseWirelessAp(group: List<Iap2Csm.Param>): WifiConfiguration? {
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        val strings =
            group
                .sortedBy { it.id }
                .mapNotNull { param -> param.value.asStringOrEmpty() }
                .filter { it.isNotEmpty() && !ipv4.matches(it) && !it.contains(':') }
        val ssid = strings.getOrNull(0)?.takeIf { it.length <= 32 } ?: return null
        val passphrase = strings.getOrNull(1) ?: ""
        val singles = group.filter { it.value.size == 1 }.associate { it.id to (it.value[0].toInt() and 0xFF) }
        val security =
            singles[4]?.takeIf { it in 0..4 }
                ?: singles[3]?.takeIf { it in 0..4 }
                ?: 0
        val channel =
            singles[2]?.takeIf { it in 1..196 }
                ?: singles[4]?.takeIf { it in 1..196 && it != security }
                ?: singles[3]?.takeIf { it in 1..196 && it != security }
                ?: 0
        return WifiConfiguration(ssid = ssid, passphrase = passphrase, channel = channel, securityType = security)
    }

    /**
     * 从 0x1D01 的 ExternalAccessoryProtocol 组件（参数组 10/11/12）解析 EA 协议：
     * 子参数 0 = 协议号（u8）、1 = 协议名。对照 DiPlay 同款构造（u8(0,index)+string(1,name)+u8(2,0)）。
     */
    private fun parseEaProtocols(frame: Iap2Csm.Frame): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        for (groupId in intArrayOf(10, 11, 12)) {
            val value = params[groupId]?.value ?: continue
            val sub = Iap2Csm.parseParams(value).associateBy { it.id }
            val protocolId =
                sub[0]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: continue
            val name = sub[1]?.value?.asStringOrEmpty() ?: "?"
            out += protocolId to name
            log("iap2 0x1d01 EA 组件[$groupId] protocol=$protocolId name=$name")
        }
        return out
    }

    private fun logIdentification(frame: Iap2Csm.Frame) {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val name = params[0]?.value?.asStringOrEmpty()
        val model = params[1]?.value?.asStringOrEmpty()
        val manufacturer = params[2]?.value?.asStringOrEmpty()
        log("iap2 rx=0x1d01 车机身份 name=$name model=$model manufacturer=$manufacturer")
        // 参 6/7 = 车机声明「它会发送/它可接收」的消息 ID 清单。
        // 参 7（它可接收）直接告诉我们它是否认可 0x4300 —— 真车排查的关键证据。
        val sends = params[6]?.value?.asU16List()
        val receives = params[7]?.value?.asU16List()
        if (sends != null) {
            // 后续可选消息（0x6800/0x4301）按此清单判断是否会来，避免固定窗口空等
            accessorySendIds = sends.toSet()
            log("iap2 0x1d01 车机发送清单(${sends.size})=" + sends.joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 可选消息声明：StartHID(0x6800)=" +
                    (accessoryDeclaresSend(Iap2MessageIds.START_HID)?.let { if (it) "会发" else "不发" } ?: "未知") +
                    " CarPlayStartSession(0x4301)=" +
                    (accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)?.let { if (it) "会发" else "不发" } ?: "未知"),
            )
        }
        if (receives != null) {
            // 车机能否收 0x5702 决定要不要走旧协议——不接受就必须走 0x4300/0x4301
            // （BMW iD9 的可收清单里没有 0x5702，旧流程因此每轮死等 0x5703 超时）。
            accessoryReceiveIds = receives.toSet()
            receiveListDeclared = true
            log("iap2 0x1d01 车机可收清单(${receives.size})=" + receives.joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 协议代际：请求 WiFi 配置(0x5702)=" +
                    (
                        accessoryDeclaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)?.let {
                            if (it) "可收" else "不收"
                        } ?: "未知"
                    ) +
                    " 回 WiFi 配置(0x5703)=" +
                    (
                        accessoryDeclaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)?.let {
                            if (it) "会发" else "不发"
                        } ?: "未知"
                    ),
            )
        }
        log("iap2 0x1d01 原始体=" + frame.body.joinToString("") { "%02x".format(it) })
    }

    /**
     * 应答车机主动发起的设备识别（车机 → 手机 StartIdentification 0x1D00）。
     *
     * iAP2 识别是**双向**的：手机先发 0x1D00 要车机身份（[run] 的固定序列），
     * 车机同样可以反向要求设备身份，真 iPhone 必答。此前所有到站的 0x1D00 都被
     * 暂存/记日志——车机拿不到设备的身份与消息能力清单（0x1D01 参数 6/7 里的
     * CarPlay 消息家族），无从判定设备支持 CarPlay，严格校验设备侧的车机（BMW
     * EVO ID6 实测）会把整台设备判成「设备不支持 Apple CarPlay」并不再下发 0x4301。
     *
     * 回送内容对照真 iPhone：身份字段（name/model/manufacturer/serial/firmware）
     * + 收/发消息能力清单（含完整 CarPlay 家族）+ 语言。每会话最多应答一次；
     * 只有收到 0x1D00 才触发，奥迪/DiPlay 从不发该消息——对既有通路零影响。
     *
     * @return true 表示已应答（调用方不应再暂存该帧，避免二次消费重复应答）
     */
    private fun maybeAnswerIdentification(frame: Iap2Csm.Frame): Boolean {
        if (frame.messageId != Iap2MessageIds.START_IDENTIFICATION) return false
        if (deviceIdentificationAnswered) {
            log("iap2 rx=0x1d00 车机重复请求设备识别——本轮已应答，忽略")
            return true
        }
        deviceIdentificationAnswered = true
        log(
            "iap2 rx=0x1d00 车机请求设备识别——回送设备侧 IdentificationInformation " +
                "(name=$deviceName model=$DEVICE_MODEL_IDENTIFIER fw=$DEVICE_FIRMWARE_VERSION)",
        )
        send(Iap2MessageIds.IDENTIFICATION_INFORMATION, deviceIdentification())
        return true
    }

    /**
     * 设备侧 IdentificationInformation（0x1D01）体。
     *
     * 参数 6/7（收/发消息清单）是车机判定 CarPlay 能力的核心：必须包含完整
     * CarPlay 家族（0x4300/0x4301/0x4E0x/0x5702/0x5703）。清单逐字节写入日志
     * （send 的 tx 行 + 本函数返回值由调用方记录），真车实测后可按需修正。
     */
    private fun deviceIdentification(): ByteArray {
        val sentByDevice =
            intArrayOf(
                0x1D00, 0x1D02,
                0x4300,
                0x4E09, 0x4E0A, 0x4E0B, 0x4E0C, 0x4E0D, 0x4E0E,
                0x5702,
                0xAA00, 0xAA02, 0xAA04, 0xAA05,
                0x6801,
                0xEA00, 0xEA01,
                0x5001, 0x4155, 0x4158, 0x4171, 0x5201, 0xAE01,
                0xFFFA, 0xFFF0,
            )
        val acceptedByDevice =
            intArrayOf(
                0x1D00, 0x1D01,
                0x4301, 0x5703,
                0xAA01, 0xAA03, 0xAA06,
                0x6800, 0x6802, 0x6803,
                0x5000, 0x4157, 0x4154, 0x4170, 0x5200, 0xAE00,
                0xEA02, 0xEA03,
                0xFFFB,
            )
        val supportedLanguages = "zh".encodeToByteArray() + byteArrayOf(0) + "en".encodeToByteArray() + byteArrayOf(0)
        return Iap2Csm.string(0, deviceName) +
            Iap2Csm.string(1, DEVICE_MODEL_IDENTIFIER) +
            Iap2Csm.string(2, "Apple Inc.") +
            Iap2Csm.string(3, deviceSerialNumber()) +
            Iap2Csm.string(4, DEVICE_FIRMWARE_VERSION) +
            Iap2Csm.u16List(6, sentByDevice) +
            Iap2Csm.u16List(7, acceptedByDevice) +
            Iap2Csm.string(12, "zh") +
            Iap2Csm.param(13, supportedLanguages)
    }

    /** 设备序列号：由蓝牙 MAC 确定性派生（12 位大写字母数字），跨会话稳定。 */
    private fun deviceSerialNumber(): String =
        java.util.UUID
            .nameUUIDFromBytes(("serial:$bluetoothMac").toByteArray())
            .toString()
            .replace("-", "")
            .uppercase()
            .take(12)

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
        return remaining.coerceIn(0, 60_000L)
    }

    private fun u64Bytes(value: Long): ByteArray =
        ByteArray(8) { index ->
            (value ushr (8 * (7 - index))).toByte()
        }

    /** 设备 UUID：由蓝牙 MAC 确定性派生，跨会话稳定（车机按它标识设备）。 */
    private fun deviceUuid(): String =
        java.util.UUID
            .nameUUIDFromBytes(bluetoothMac.toByteArray())
            .toString()
            .uppercase()

    /**
     * NUL 结尾的 CSM 字符串。**必须去掉全部尾部 NUL，不能只去一个**：
     * Audi MMI 3315 实测把无线组里的地址字段填充到定长（IPv6 后跟 22 个 NUL、
     * IPv4 后跟 4 个），只去一个会得到 `"10.174.189.1\u0000\u0000\u0000\u0000"`。
     * 这种字符串 `contains('.')` 为真、`contains(':')` 为假，能通过所有「是不是
     * IPv4」的判断，但交给 `InetSocketAddress` 就成了
     * `Unable to resolve host "10.174.189.1"`（NUL 在日志里不可见，极难看出）——
     * 2026-10-18 实测：AirPlay 配对靠 mDNS 刷新端点侥幸成功，但视频数据端口与
     * keepAlive 仍用带 NUL 的地址，双双失败，会话 10 秒后被车机拆掉。
     */
    private fun ByteArray.asStringOrEmpty(): String? {
        if (isEmpty() || last() != 0.toByte()) return null
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return copyOf(end).decodeToString()
    }

    private fun ByteArray.asU32(): Int =
        if (size == 4) {
            ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)
        } else {
            0
        }

    /** 参 6/7 的消息清单：大端 u16 序列（识别帧里车机声明收/发能力用）。 */
    private fun ByteArray.asU16List(): List<Int>? {
        if (size < 2 || size % 2 != 0) return null
        return (0 until size step 2).map {
            ((this[it].toInt() and 0xFF) shl 8) or (this[it + 1].toInt() and 0xFF)
        }
    }
}
