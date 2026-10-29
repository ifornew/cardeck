package io.github.shaonialife.cardeck

import android.content.Context
import android.view.KeyEvent
import io.github.shaonialife.cardeck.airplay.AirPlayClient
import io.github.shaonialife.cardeck.airplay.PairVerifyClient
import io.github.shaonialife.cardeck.audio.AudioProfile
import io.github.shaonialife.cardeck.audio.AudioProfileMemory
import io.github.shaonialife.cardeck.audio.AudioSender
import io.github.shaonialife.cardeck.audio.AudioTuning
import io.github.shaonialife.cardeck.bt.BtProfiles
import io.github.shaonialife.cardeck.bt.BtSessionGate
import io.github.shaonialife.cardeck.bt.Iap2BluetoothClient
import io.github.shaonialife.cardeck.bt.Iap2BluetoothServer
import io.github.shaonialife.cardeck.bt.ResidentBackoff
import io.github.shaonialife.cardeck.bt.TargetDevicePrefs
import io.github.shaonialife.cardeck.capture.HiddenDisplaySource
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.input.ConsumerKeys
import io.github.shaonialife.cardeck.input.KnobKeys
import io.github.shaonialife.cardeck.input.KnobNav
import io.github.shaonialife.cardeck.input.TouchReports
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.launcher.settings.ThemePrefs
import io.github.shaonialife.cardeck.net.AirPlayFeatures
import io.github.shaonialife.cardeck.net.CarPlayCtrlAdvertiser
import io.github.shaonialife.cardeck.net.CarPlayServiceBrowser
import io.github.shaonialife.cardeck.net.EndpointHosts
import io.github.shaonialife.cardeck.net.WifiCarPlayConnector
import io.github.shaonialife.cardeck.net.WifiSavedNetworks
import io.github.shaonialife.cardeck.service.CarPlayService
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.ui.ScreenDimmer
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import io.github.shaonialife.cardeck.video.VideoSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程级会话中枢：**日志缓冲**与**会话管线所有权**的唯一持有者。
 *
 * 背景（2026-10-11 用户实测发现三个连带问题）：投屏管线运行在 MainActivity 派生线程里，
 * 划掉界面（Activity 销毁）后进程被前台服务保活、管线继续跑；但重进应用创建的是**新
 * Activity 实例**——
 *  1. 老线程的日志写进老实例（已销毁）的 UI → 新界面"日志断联"（文件日志实际未断）
 *  2. 「断开连接」操作新实例的新对象 → 老管线无人持有，断不掉
 *  3. 重开连接会建第二套对象 → 双会话风险
 *
 * 本对象把这些状态提升为进程级单例：Activity 只是"挂屏"（订阅日志、转发按钮），
 * 销毁/重建对其无影响。会话逻辑线程通过 [appContext] 运行，不依赖任何 Activity。
 */
object SessionController {
    // ---- 日志缓冲（进程级；Activity 订阅渲染）----

    private val logLock = Any()
    private val logBuilder = StringBuilder()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 当前挂屏 Activity 的渲染回调（收到**本条新增日志行**，含时间戳前缀）；Activity onDestroy 时清空。 */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** 「监听日志」开关（由 Activity 从偏好读取后同步；关闭时不采集不渲染）。 */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** WiFi 等待用户操作的窗口（打开 WLAN 开关 / 手动加入热点的轮询上限）。 */
    private const val WLAN_WAIT_TIMEOUT_MS = 120_000L

    /** 加入确认弹窗的应答等待上限（无人应答按中止处理）。 */
    private const val JOIN_DIALOG_TIMEOUT_S = 90L

    /**
     * 等车机 ctrl 邀请的上限（事件驱动，收到即走）。
     * catplay 以邀请为连接触发点；实测邀请在 ctrl 重注册后 ~1.0-1.3s 到达。
     * 车机不发邀请时（旧机型）由「已发现 _airplay._tcp」提前放行，最坏也只等这么久。
     */
    private const val CTRL_INVITE_WAIT_MS = 4_000L

    /** 端点不完整时等 mDNS 缓存命中：绑网时已开始发现，通常几百毫秒内命中。 */
    private const val ENDPOINT_RESOLVE_WAIT_MS = 3_000L

    /**
     * 明文 SETUP 被拒后「加密升级」补配对的上限（catplay handle_setup_result 路径）。
     * 只在实际被拒时才走到这里，正常车机零成本；不支持配对的接收端会在
     * pair-setup 收到 404/405/455 后立刻放弃，不会等满这个窗口。
     */
    private const val PAIRING_UPGRADE_WINDOW_MS = 30_000L

    /**
     * 明文会话初始 SETUP 的重试窗口。pre-HomeKit 车机的 AirPlay 端口常在
     * 「用户点车机上的手机图标」后才开放；端口晚开 1~2 秒不该让整轮失败。
     */
    private const val INITIAL_SETUP_WINDOW_MS = 30_000L

    /**
     * 端点已到但手机还没切到车机热点时的等待上限。
     * 覆盖「自动加入（几秒）」与「用户手动加入（常见 10-30s）」两种路径；
     * 超时后明确提示并复位 sessionStarting，不留「永久正在连接」。
     */
    private const val CAR_WIFI_BIND_WAIT_MS = 45_000L

    /**
     * `requestNetwork(WIFI)` 等 onAvailable 的上限（秒）。
     * 用户正在系统设置里手动加入热点时，回调要等关联+DHCP 完成才来，实测可达 10s+。
     * 旧值 5s 会在成功前 116ms 误判失败并放弃整轮会话（见 bindCarWifiAndRegister）。
     */
    private const val WIFI_BIND_WAIT_S = 15L

    /**
     * 等用户确认「连接车机热点」系统弹窗的上限（应用专属网络）。
     * 系统确认框可能停留很久；用户取消连接时 [connectCancelled] 会即时退出。
     */
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 120_000L

    /** 绑定被系统回收后自动重加入的上限（在配对重试窗口内完成即可）。 */
    private const val WIFI_RECOVER_TIMEOUT_MS = 20_000L

    /** 自动重加入的最小间隔：避免配对重试每一轮都重新 requestNetwork 扰动车机热点。 */
    private const val WIFI_RECOVER_COOLDOWN_MS = 6_000L

    /** Activity onCreate 时调用：登记 applicationContext 并创建会话对象（幂等）。 */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        ensureSessionObjects(context.applicationContext)
        // 横屏兼容写入失败的原因（user build 限制 / Shizuku 故障）只有这里能落盘：
        // 车机侧设置面板的 toast 只给用户看，排查需要日志。
        AppOrientationCompat.diagSink = ::appendLog
        // 主线程调用 shell 的告警（历史上两次真机 ANR 都源于此；HKS 下 logcat 不可读）
        Shell.violationSink = ::appendLog
    }

    /** 追加一行日志：logcat + 文件 + 进程级缓冲 + 挂屏渲染（增量行直送）。任意线程可调。 */
    fun appendLog(message: String) {
        // NUL 会写进日志文件：文本编辑器/文件管理器打不开（Read 工具也会判为二进制），
        // 用户反馈日志时等于白导。0x4301 的定长地址字段曾原样打印出多个 NUL
        // （2026-10-18 实测 216 个），这里替换成可见占位符——顺带让「字符串里有脏字节」
        // 在日志上直接可见，而不是隐形成 InetSocketAddress 的解析失败。
        val clean = if (message.indexOf('\u0000') >= 0) message.replace("\u0000", "␀") else message
        android.util.Log.i("CarPlaySender", clean)
        appContext?.let {
            DebugFileLog
                .append(it, clean)
        }
        if (!logEnabled) return
        val line =
            synchronized(logLock) {
                val stamped = '[' + timestamp.format(Date()) + "] " + clean + '\n'
                logBuilder.append(stamped)
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                stamped
            }
        // 增量行直送 UI：此前每行 toString 整个缓冲再 setText 全文，
        // 高频日志（触摸/帧）下是 UI 线程与 GC 的双重放大器
        logListener?.invoke(line)
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    /**
     * 派发系统媒体键：**优先经 Shizuku shell 注入**（与方控/旋钮同一通道）。
     *
     * `AudioManager.dispatchMediaKeyEvent` 需要 `MEDIA_CONTENT_CONTROL`——第三方 App
     * 只有是 MediaBrowser/通知监听器才拿得到。EMUI 上它不抛异常、只静默丢弃，
     * 表现就是「日志里收到了车机媒体键，手机却一点反应没有」（2026-10-29 实车：
     * 方向盘上一曲/下一曲无效，日志 `usage=0xb5 → keyCode=87` 之后无任何效果）。
     * shell 注入走真实 InputDispatcher，全局路由到活跃媒体会话，不受该权限限制；
     * 注入不可用（Shizuku 未就绪）时退回 AudioManager，尽量保留老路径。
     *
     * @return true = 已由 Shizuku 注入；false = 退回 AudioManager（可能被 ROM 忽略）
     */
    fun dispatchMediaKey(keyCode: Int): Boolean {
        if (CarInputClient.service?.key(0, keyCode) == true) return true
        val ctx = appContext ?: return false
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return false
    }

    /**
     * 清空进程级日志缓冲（UI 刷新是调用方的事：显式 `renderConsole(logText())`）。
     * 历史上这里靠 `logListener?.invoke("")` 通知 UI——那是「每行全量 setText」时代的
     * 语义；改为增量 appendConsole 后空串等于追加空行，UI 原地不动（「清除按钮点了
     * 没反应」根因，2026-10-16）。不要恢复该调用：空串与真实日志行无法区分。
     */
    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
    }

    // ---- 会话对象（进程级所有权；Activity 重建不影响）----

    private var wifiConnector: WifiCarPlayConnector? = null

    /**
     * 当前已绑定到哪个车机热点（bindCarWifiAndRegister 成功后写入）。
     * 端点交接时若已绑定到同一 SSID，跳过二次 bindCurrentWifi——它内部先
     * release() 解绑，会打断刚注册好的 ctrl mDNS 广播并白等系统重连。
     */
    @Volatile
    private var boundCarSsid: String? = null

    /** 本次进程内已就「是否清除系统保存热点」询问过的 SSID（避免重试时重复弹窗）。 */
    private val forgetPromptHandled =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** WiFi 绑定互斥：auto-join 与端点交接两条路径可能同时绑定同一热点。 */
    private val wifiBindLock = Any()

    /** DCL 双检锁的可发布字段（无 volatile 的双检锁是坏发布——虽然当前仅主线程调用，别留隐患）。 */
    @Volatile
    private var bluetoothServer: Iap2BluetoothServer? = null
    private var bluetoothClient: Iap2BluetoothClient? = null
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /**
     * 常驻 `_airplay._tcp` 发现器（对照 catplay 的常驻 Bonjour cache）。
     * 加入热点即开始后台发现，交接端点时地址多半已就绪，不再现查现等。
     */
    private var endpointBrowser: CarPlayServiceBrowser? = null

    /**
     * 用户当前是否「要连着」（启动连接置 true，取消/断开置 false）。
     * 异常断链自动重挂只看它：否则用户按下断开后，推流线程晚到的 onEnded 会把链路又拉起来。
     */
    @Volatile
    private var connectionWanted = false

    /** 异常断链「完整收尾 → 静默 → 重新发布」的令牌：取消/新一轮连接时自增作废。 */
    private val rearmLock = Any()
    private var rearmToken = 0
    private var rearmPending = false
    private val rearmHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---- Resident 常驻模式（对照 CarDroid J5/O5）----

    /** 阶梯退避器：异常断链后按 0s→5s→30s→2min→10min→30min 递增静默。 */
    private val residentBackoff = ResidentBackoff()

    /** 用户是否开启了「常驻连接」（持久化偏好；开启后 App 启动即自动进入等待车机回连）。 */
    @Volatile
    var residentEnabled: Boolean = false
        private set

    /** 常驻模式下的周期健康巡检（BT 监听/ctrl 广播是否还活着）。 */
    private val patrolHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var patrolRunning = false

    /** 巡检间隔：与 CarDroid resident_standby_report 同量级。 */
    private const val PATROL_INTERVAL_MS = 15_000L

    /** 异常断链后静默多久再重新发布（模拟用户「取消 → 启动」之间的空档）。 */
    private const val ABNORMAL_REARM_DELAY_MS = 3_000L

    @Volatile
    var videoSender: VideoSender? = null

    // ---- 「显示」运行时生效值（会话建立时快照；英雄卡展示运行时值而非偏好值）----

    /** 本会话流分辨率缩放百分比（按车机分辨率的百分比生成；100=原尺寸）。 */
    @Volatile
    var activeScalePercent: Int = 100

    /** 本会话目标帧率（合成节奏上限；「显示」设置运行中切换可实时生效）。 */
    @Volatile
    var activeTargetFps: Int = 30

    /**
     * 最近一次握手时车机通告的最高刷新率（/info displays[].maxFPS；0=尚未握手/未知）。
     * 「显示」设置据它禁用高于车机能力的帧率档位——这类档位即使选了也会被夹紧，
     * 只会白增编码开销与无效帧。
     */
    @Volatile
    var carMaxFps: Int = 0

    @Volatile
    var audioSender: AudioSender? = null

    @Volatile
    var currentClient: AirPlayClient? = null

    @Volatile
    var currentHiddenSource: HiddenDisplaySource? = null

    /** 当前会话的共享密钥与接收端地址：投影授权晚于连接时，动态补启音频用。 */
    @Volatile
    var currentSharedSecret: ByteArray? = null

    @Volatile
    var currentAudioHost: String? = null

    /** 本次会话是否在车机侧播放声音（默认开启，可在连接中手动关闭）。 */
    @Volatile
    var audioEnabledForSession: Boolean = false

    /**
     * 「开启声音（兼容档）」：候选里把非 media 档（compatibility 等）排前面。
     * 2026-10-19 Honda HFT 实测——media 档只声明 44.1k，该车机音频通路只吃得下 48k，
     * 走默认档会「上线几秒后整车机拆链」。
     */
    @Volatile
    var audioPreferCompat: Boolean = false

    /**
     * 免授权采集（Shizuku shell REMOTE_SUBMIX）可用性：null = 未探测，true = 可用，
     * false = 本机 ROM 拦截。实测华为 EMUI12：AudioRecord 能初始化、startRecording 成功，
     * 但 read 立即返回 0（HKS 清零 uid 2000 非应用进程的录音），因此只能靠「实际读到的
     * 字节数」判定。探测由界面后台发起，会话内只读缓存，不阻塞。
     */
    @Volatile
    private var shellCaptureOk: Boolean? = null

    /** 探测互斥：界面轮询与连接流程可能同时触发。 */
    private val shellProbeLock = Any()

    /** 免授权采集探测结果（null = 尚未探测）。 */
    fun shellCaptureState(): Boolean? = shellCaptureOk

    /** 探测 shell 免授权采集是否真能出数据（约 600ms 上限，必须后台线程调用）。 */
    fun shellCaptureAvailable(): Boolean {
        shellCaptureOk?.let { return it }
        synchronized(shellProbeLock) {
            shellCaptureOk?.let { return it }
            val ok =
                runCatching { probeShellCapture() }.getOrElse {
                    appendLog("AUDIO: 免授权采集探测异常 - ${it.message}")
                    false
                }
            shellCaptureOk = ok
            appendLog(
                if (ok) {
                    "AUDIO: 免授权采集探测 → 可用（shell REMOTE_SUBMIX 有数据）"
                } else {
                    "AUDIO: 免授权采集探测 → 不可用（本机 ROM 拦截 shell 录音，读不到任何 PCM），改用投屏授权"
                },
            )
            return ok
        }
    }

    private fun probeShellCapture(): Boolean {
        val service = CarInputClient.service ?: return false
        val pipe =
            runCatching {
                service.startAudioCapture(
                    PROBE_SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_STEREO,
                    AudioSender.PACKET_BYTES * 4,
                )
            }.getOrNull() ?: return false
        // 读线程只等到第一块数据（正常机器 ~20ms 就有），本机读不到则一直阻塞
        val bytes = AtomicInteger(0)
        val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe)
        val reader =
            Thread(
                {
                    val buffer = ByteArray(4096)
                    try {
                        while (bytes.get() == 0) {
                            val n = stream.read(buffer)
                            if (n <= 0) break
                            bytes.addAndGet(n)
                        }
                    } catch (_: Throwable) {
                    }
                },
                "shell-audio-probe",
            )
        reader.isDaemon = true
        reader.start()
        reader.join(PROBE_WAIT_MS)
        // 关写端让阻塞读退出，再等读线程收尾
        runCatching { service.stopAudioCapture() }
        reader.join(300L)
        runCatching { stream.close() }
        return bytes.get() > 0
    }

    /** 本轮真正用上的音频档位（拆链归因与记忆回写用；null = 本次没有音频）。 */
    @Volatile
    private var activeAudioProfile: AudioProfile? = null

    /** 音频档位记忆的键（车机身份文本，见 [AirPlayClient.receiverIdentity]）。 */
    @Volatile
    private var activeAudioReceiverKey: String = ""

    /** 音频通道真正开始推流的时刻（uptimeMillis）；0 = 未开始。 */
    @Volatile
    private var activeAudioStartedAt: Long = 0L

    /**
     * 音频「存活窗口」（ms）：超过它才认为该档位可用。
     * 依据 2026-10-19 实测：拆链发生在音频起流后 6.5s；取 20s 留足车机音频通路初始化
     * 与首个欠载周期的余量。
     */
    private const val AUDIO_PROFILE_PROBE_MS = 20_000L

    /** 免授权采集探测：采样率（与 SETUP 主档一致）与等待首块数据的上限。 */
    private const val PROBE_SAMPLE_RATE = 48_000
    private const val PROBE_WAIT_MS = 600L

    /** 会话建立互斥（端点交接 → 配对 → 推流全程只允许一条）。 */
    val sessionStarting = AtomicBoolean(false)

    /**
     * 交接流程已在跑、但 AirPlayClient 尚未创建时收到的晚到端点（0x4301）。
     * 配对线程起步时取用一次后清空——此刻没有 client 可更新，丢了就白等到下一轮。
     */
    @Volatile
    private var pendingEndpointUpdate: Iap2SenderSession.CarPlayEndpoint? = null

    /** 用户取消连接：配对线程在关键检查点看到后中止（mDNS/配对等待无法中途打断，粒度秒级）。 */
    @Volatile
    var connectCancelled: Boolean = false

    /**
     * 当前连接阶段的一句话提示（英雄卡副标题用，详细过程看日志）。
     * 由各阶段显式设置；null 时 UI 显示默认文案。
     */
    @Volatile
    var phaseHint: String? = null

    /** 取消「连接中」：停链路 + 置取消标志，卡在错误网络/等待回连时用户可主动脱身。 */
    fun cancelConnecting(context: Context) {
        connectCancelled = true
        appendLog("SESSION: 用户取消连接——正在停止蓝牙/WiFi 链路")
        disconnect(context)
        // 强制释放互斥：配对流程线程可能阻塞在不可中断的连接/mDNS 等待上（如手机
        // 不在热点网段时连热点 IP），finally 遥遥无期——用户显式取消即强制解锁，
        // 否则英雄卡永久「正在连接」（旧流程产出已被 retireSession 清理，无冲突）
        sessionStarting.set(false)
        phaseHint = "已取消连接"
    }

    /**
     * 最近一次 0x5703 的热点密码（仅内存，不落盘）。
     * 「车机热点 → 去连接」按钮复制到剪贴板用（手动加入兜底场景）。
     */
    @Volatile
    var lastWifiPsk: String? = null

    /** 最近一次 0x5703 的完整热点配置（网络绑定被系统回收时自动重新加入用，仅内存）。 */
    @Volatile
    private var lastWifiConfig: Iap2SenderSession.WifiConfiguration? = null

    /** 上一次自动重新加入车机热点的时刻（节流，避免每轮配对重试都重开请求扰动车机）。 */
    @Volatile
    private var lastWifiRecoveryAtMs = 0L

    /** 挂屏 UI 钩子：SSID 变化时通知当前 Activity 回填输入框（可空）。 */
    @Volatile
    var ssidSink: ((String) -> Unit)? = null

    /** 挂屏 UI 钩子：重要连接提示（WiFi 未开启/热点加入限制等）弹给用户看。 */
    @Volatile
    var uiHintSink: ((String) -> Unit)? = null

    /** 弹窗规格（加入热点确认等两按钮询问）。 */
    data class DialogSpec(
        val title: String,
        val message: String,
        val positive: String,
        val negative: String,
    )

    /**
     * 挂屏 UI 钩子：两按钮确认弹窗。
     * onChoice：true=positive；false=negative；null=无人应答/被关闭（调用方兜底）。
     */
    @Volatile
    var dialogSink: ((DialogSpec, onChoice: (Boolean?) -> Unit) -> Unit)? = null

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            wifiConnector =
                WifiCarPlayConnector(context) { line -> appendLog(line) }.also { connector ->
                    // 应用专属网络被系统断开（车机熄火/离开范围）：明确告知用户，
                    // 而不是让英雄卡在「已连接」与失败之间静默摇摆
                    connector.onNetworkLost = { ssid ->
                        boundCarSsid = null
                        phaseHint = "车机热点已断开（$ssid）"
                        uiHintSink?.invoke("车机热点 \"$ssid\" 已断开——请重新启动连接")
                        appendLog(">>> 车机热点 \"$ssid\" 已断开——本次会话需重新连接")
                    }
                }
            val wifiCredentialHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            // 目标车机动态读取：选择后下一轮连接尝试/下一次连入即生效，无需重启链路
            val targetProvider: () -> TargetDevicePrefs.Target? = {
                appContext?.let { TargetDevicePrefs.get(it) }
            }
            bluetoothServer =
                Iap2BluetoothServer(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "车机回连") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "车机回连") },
                    targetProvider = targetProvider,
                )
            bluetoothClient =
                Iap2BluetoothClient(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "主动连车机") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "主动连车机") },
                    targetProvider = targetProvider,
                    // 车机回连过就不再主动拨 …caff：BMW 该方向必被 DM，而兜底会
                    // 反复断开 HFP/A2DP（车机回连的触发前提），反而让配对/重连窗口失效。
                    inboundSeen = { bluetoothServer?.inboundAccepted == true },
                )
            ctrlAdvertiser = CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }
            endpointBrowser =
                CarPlayServiceBrowser(
                    context,
                    log = { line -> appendLog(line) },
                    accept = { info ->
                        // 手机双归属（连车机热点 + 自己开热点共享给电脑）时 mDNS 会跨网
                        // 发现电脑的 AirPlay 接收器（macOS 自带）——2026-10-11 实测把
                        // MacBook 当成车机连了 120s。非本网段一律不算车机。
                        // 地址判定统一走 EndpointHosts：IPv4 按 /24 前缀，链路本地 IPv6
                        // 天然只在本链路，全局 IPv6 按 /64 比对。旧实现只认 IPv4，会把
                        // 只广播 fe80:: 的奥迪 4xxx 直接丢掉（2026-10-19）。
                        val ip = info.host?.hostAddress ?: return@CarPlayServiceBrowser false
                        EndpointHosts.isSameNetwork(
                            ip,
                            wifiConnector?.localIpv4Address(),
                            wifiConnector?.localIpv6Addresses().orEmpty(),
                        )
                    },
                )
        }
    }

    /** 状态行文本（Activity 渲染 statusText 用）。 */
    fun statusLine(): String =
        "wifi=${wifiConnector?.currentNetwork != null} " +
            "bt=${bluetoothServer?.isRunning == true} ctrl=${(ctrlAdvertiser?.port ?: -1) > 0}"

    /** 会话是否活跃（推出流进程或 BT 任一在跑）。 */
    fun isSessionRunning(): Boolean =
        videoSender?.isRunning == true ||
            bluetoothServer?.isRunning == true ||
            bluetoothClient?.isRunning == true

    /**
     * 「启动连接」主体（权限检查由 Activity 完成后调用）：前台保活服务 + 双向蓝牙。
     * 幂等：已有会话时 BT 组件的 start() 自行短路，不会双开。
     */
    fun startConnection(context: Context) {
        connectionWanted = true
        cancelPendingRearm()
        connectCancelled = false
        // 声音选择必须持久化并在此恢复：常驻模式/ACL 自动唤醒**不经过**「启动连接」
        // 弹窗，若只依赖弹窗赋值，自动连接永远是「仅推视频」（2026-10-28 实测）。
        audioEnabledForSession = loadAudioPref(context.applicationContext)
        phaseHint = "蓝牙就绪，等待车机回连…"
        when (
            ShizukuGate
                .state(context)
        ) {
            ShizukuGate.State.READY ->
                appendLog("SHIZUKU: 就绪（触摸注入可用）")

            ShizukuGate.State.UNAUTHORIZED -> {
                appendLog("SHIZUKU: 未授权本应用——已发起授权请求，请在弹窗点允许后重连")
                ShizukuGate
                    .requestPermission()
            }

            ShizukuGate.State.NOT_RUNNING ->
                appendLog("SHIZUKU: 服务未运行——打开 Shizuku App 点「启动」")

            ShizukuGate.State.NOT_INSTALLED ->
                appendLog("SHIZUKU: 未安装（隐形屏模式需要）")
        }
        if (!CarPlayService.start(context)) {
            // 后台被策略拦截：常驻模式经 ACL 唤醒时最常命中。必须显式告知，
            // 否则用户看到的是「怎么点都没反应、日志也没有内容」。
            phaseHint = "后台启动受限——请打开 App 后重试"
            appendLog(
                "RESIDENT: 前台保活服务被系统拦截——Android 12+ 不允许后台无界面拉起服务；" +
                    "请打开 App，常驻模式会自动恢复",
            )
        }
        // 能力发布先于连接（catplay 的做法：CarPlayCtrlServer 在会话一开始就常驻广播
        // `_carplay-ctrl._tcp`，之后每 1s force_announce，收到车机邀请才建 AirPlay）。
        // 我们此前要等 0x5703 才注册，导致「车机发现手机」这一步总落在热点绑定之后，
        // 每轮多花 1~3 秒；现在启动连接即发布，换网时 ensureForNetwork 会自动重注册。
        // 主路径（真车）：监听手机侧 iPhone UUID，等车机回连 …cafe（BMW 82772 实测形态）
        // 兜底路径：主动连车机（SDP …caff 配件服务，奥迪 MIB3 实测）——宽限超时才启用，
        // 故必须先开监听，保证车机回连时服务端已在等待。
        armLinks()
        // 常驻模式：连接启动即开始周期巡检（进程重建后自动启动路径也要覆盖）
        if (residentEnabled) startPatrol()
        // 诊断（奥迪实测：手机与车机「蓝牙已连接」时车机不开放 CarPlay）：
        // 连之前把目标设备的蓝牙配置文件状态打出来，失败时一眼看出是不是这个原因。
        Bg.run("bt-profile-diag") {
            val target = appContext?.let { TargetDevicePrefs.get(it) } ?: return@run
            val connected = BtProfiles.connectedNames(context.applicationContext, target.address)
            appendLog(
                "BT-PROFILE: 连接前「${target.name}」的蓝牙配置文件状态=" +
                    (connected.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "未连接"),
            )
        }
    }

    /** 「断开连接」（可在任意 Activity 实例上调用——操作的是真管线对象）。 */
    fun disconnect(context: Context) {
        // 调用方定案：断开会停掉整条链路且不可自动恢复，历史上出现过「无用户操作
        // 却走到这里」的日志（连接Wanted 被置 false 后巡检再也重挂不了），
        // 不留调用栈就只能靠猜。
        val caller =
            Throwable()
                .stackTrace
                .drop(1)
                .firstOrNull()
                ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        appendLog("SESSION: 断开调用来源→$caller")
        connectionWanted = false
        cancelPendingRearm()
        val hadProjection = CarPlayService.mediaProjection != null
        pendingEndpointUpdate = null
        retireSession()
        // BT 控制会话只在这里（用户终态）退役，socket close 让保活读循环立刻退出：
        // 否则它阻塞到对端超时（实测 ~24s）才结束，用户取消后英雄卡持续转圈。
        // 配对中绝不能断——DiPlay 在隧道 iAP2 就绪前看到 RFCOMM 断开即判
        // bring-up failed 并拆整轮无线栈（含推流中的 AirPlay 会话，2026-10-16 实测）。
        detachActiveBtSessions()
        phaseHint = null
        bluetoothServer?.stop()
        bluetoothClient?.stop()
        ctrlAdvertiser?.stop()
        endpointBrowser?.stop()
        wifiConnector?.release()
        boundCarSsid = null
        if (hadProjection) {
            // Android 14+：MediaProjection 与保活服务（mediaProjection FGS 类型）绑定，
            // 停服务会连带失效授权。为了让「声音授权保活、下次连接不再授权」，
            // 断开只收会话、保留服务与投影（2026-10-28 用户要求）；唤醒锁随会话释放。
            CarPlayService.releaseSessionLocks(context)
            appendLog("已手动断开（保活服务保留：车机侧声音授权继续有效，下次连接无需重新授权）")
        } else {
            CarPlayService
                .stop(context)
            appendLog("已手动断开并停止保活服务（常亮已恢复）")
        }
    }

    // ---- Resident 常驻模式公开 API ----

    /**
     * 开启常驻连接（对照 CarDroid resident 模式）：
     * App 启动即自动进入「等待车机回连」状态，无需用户点「启动连接」。
     * 开启状态持久化到偏好；异常断链按阶梯退避自动重挂。
     */
    fun startResident(context: Context) {
        if (residentEnabled) {
            // 已从偏好恢复（如 ACL 接收器触发）：幂等补一次启动与巡检
            startConnection(context)
            return
        }
        residentEnabled = true
        saveResidentPref(context, true)
        appendLog(
            "RESIDENT: 常驻模式已开启（首次配对新车机时，需在系统蓝牙设置里打开「对附近设备可见」；" +
                "已配对车机可直接回连，无需此步）",
        )
        // 立即进入连接等待（幂等，已有会话时不会双开；内部会自动启动巡检）
        startConnection(context)
    }

    /** 关闭常驻模式：停止巡检，断开当前连接。 */
    fun stopResident(context: Context) {
        if (!residentEnabled) return
        residentEnabled = false
        saveResidentPref(context, false)
        stopPatrol()
        disconnect(context)
        appendLog("RESIDENT: 常驻模式已关闭")
    }

    /**
     * 判断当前是否应自动启动连接（App 启动时由 MainActivity 调用）。
     * 返回 true 表示用户之前开启了常驻模式。
     */
    fun shouldAutoStart(context: Context): Boolean {
        if (!residentEnabled) {
            // 从偏好恢复（进程重建场景）
            residentEnabled = loadResidentPref(context)
        }
        return residentEnabled
    }

    /** 周期巡检：确认 BT 监听与 ctrl 广播仍存活，发现异常自动重挂。 */
    private fun startPatrol() {
        if (patrolRunning) return
        patrolRunning = true
        patrolHandler.postDelayed(
            object : Runnable {
                override fun run() {
                    if (!residentEnabled || !patrolRunning) return
                    patrolTick()
                    patrolHandler.postDelayed(this, PATROL_INTERVAL_MS)
                }
            },
            PATROL_INTERVAL_MS,
        )
    }

    private fun stopPatrol() {
        patrolRunning = false
        patrolHandler.removeCallbacksAndMessages(null)
    }

    /** 单次巡检：会话不活跃但 resident 开着 → 检查链路是否需要重挂。 */
    private fun patrolTick() {
        // 推流中：一切正常
        if (videoSender?.isRunning == true) return
        // BT 监听停了但 resident 开着 → 静默重挂
        val btAlive = bluetoothServer?.isRunning == true || bluetoothClient?.isRunning == true
        val ctrlAlive = (ctrlAdvertiser?.port ?: -1) > 0
        if (!btAlive || !ctrlAlive) {
            appendLog(
                "RESIDENT: 巡检发现链路异常（bt=$btAlive ctrl=$ctrlAlive " +
                    "wanted=$connectionWanted cancelled=$connectCancelled pending=$rearmPending）——静默重挂",
            )
            scheduleAbnormalRearm("巡检发现链路异常")
        }
    }

    private fun saveResidentPref(
        context: Context,
        enabled: Boolean,
    ) {
        context.applicationContext
            .getSharedPreferences("resident_mode", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabled)
            .apply()
    }

    private fun loadResidentPref(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences("resident_mode", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)

    /**
     * 「启动连接」弹窗的声音选择，持久化后供常驻/自动唤醒复用。
     * 默认 **true**：新用户期望「连上就有声音」，否则会误判为音频推送故障。
     */
    fun setAudioEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        audioEnabledForSession = enabled
        context.applicationContext
            .getSharedPreferences("resident_mode", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("audio", enabled)
            .apply()
    }

    private fun loadAudioPref(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences("resident_mode", Context.MODE_PRIVATE)
            .getBoolean("audio", true)

    /**
     * 发布能力 + 开监听（启动连接与「异常断链后重新发布」共用）。
     *
     * 能力发布先于连接（catplay 的做法：`_carplay-ctrl._tcp` 常驻广播，收到车机邀请
     * 才建 AirPlay）；真车回连是 ⌜车机主动连手机⌟，所以监听必须先于任何等待。
     */
    private fun armLinks() {
        ctrlAdvertiser?.resetInviteCount()
        // 后台发布：start() 首次要向 Shizuku 解析真实蓝牙 MAC（shell），不能在主线程做
        Bg.run("ctrl-publish") { ctrlAdvertiser?.start() }
        bluetoothServer?.start()
        bluetoothClient?.start()
    }

    /**
     * 异常断链（不是用户断开）后的**完整收尾 + 静默 + 重新发布**。
     *
     * 为什么需要：WiFi 被系统静默回收时车机收不到任何 teardown 通知，它侧 CarPlay
     * 会话仍是「已连接」；随后车机回连蓝牙只做链路层重试，拒绝重做识别——0x1D01
     * 永远不来，链路静默到对端拆 socket 或用户手动取消（2026-10-25 实车 5 轮全此）。
     * 用户手动「取消 → 启动」能立刻恢复（干净收尾后 10–50s 内即识别成功），说明
     * 需要的正是这次完整收尾。这里把它自动化：停 ctrl 广播、释放 WiFi、关 AirPlay、
     * 停 BT 监听，静默 [ABNORMAL_REARM_DELAY_MS] 后再重新发布。
     */
    private fun scheduleAbnormalRearm(reason: String) {
        if (connectCancelled || !connectionWanted) return
        val token =
            synchronized(rearmLock) {
                if (rearmPending) return
                rearmPending = true
                rearmToken
            }
        val delayMs = residentBackoff.nextDelayMs()
        val step = residentBackoff.currentStep
        if (residentBackoff.isExhausted) {
            // 必须清掉 pending：否则后续巡检的调用会在 synchronized 里静默返回，
            // 表现为「每 15s 打一行正在重挂、实际什么都没做」（常驻模式永久卡死）。
            synchronized(rearmLock) { rearmPending = false }
            appendLog(
                "LINK: 异常断链（$reason）——已达最大重试次数，等待用户手动操作",
            )
            phaseHint = "连接多次失败——请手动重试"
            return
        }
        val delayText =
            when {
                delayMs == 0L -> "立即"
                delayMs < 60_000L -> "${delayMs / 1000}s"
                else -> "${delayMs / 60_000}min"
            }
        appendLog(
            "LINK: 异常断链（$reason）——完整收尾（停 ctrl 广播/释放 WiFi/BT 监听），" +
                "退避阶梯[$step] ${delayText}后重新发布",
        )
        phaseHint = "网络异常断开——正在重置链路…"
        Bg.run("link-teardown") {
            // retireSession 会把 AirPlay 控制通道正常关掉（车机据此判定会话结束）
            retireSession()
            detachActiveBtSessions()
            bluetoothServer?.stop()
            bluetoothClient?.stop()
            ctrlAdvertiser?.stop()
            endpointBrowser?.stop()
            wifiConnector?.release()
            boundCarSsid = null
        }
        rearmHandler.postDelayed({ rearmNow(token) }, delayMs)
    }

    private fun rearmNow(token: Int) {
        synchronized(rearmLock) {
            if (token != rearmToken) return // 用户已断开 / 已开始新一轮连接
            rearmPending = false
        }
        if (connectCancelled || !connectionWanted) return
        appendLog("LINK: 重新发布能力（ctrl 广播 + BT 监听），等待车机回连")
        phaseHint = "已重置，等待车机回连…"
        armLinks()
    }

    /** 作废待执行的「异常断链重挂」（用户断开、取消或重新启动连接时）。 */
    private fun cancelPendingRearm() {
        synchronized(rearmLock) {
            rearmToken++
            rearmPending = false
        }
        rearmHandler.removeCallbacksAndMessages(null)
    }

    /** 会话结束/新周期开始前彻底退役上一代会话（旧管线泄漏会累积编码器/虚拟显示器，最终 dequeueBuffer -19）。 */
    fun retireSession() {
        videoSender?.stop()
        videoSender = null
        // 暗幕/常亮随会话收掉（本方法在每轮配对起步也会执行，新一轮推流起来后会重新置位）
        ScreenDimmer.setSessionActive(false)
        audioSender?.stop()
        audioSender = null
        currentSharedSecret = null
        currentAudioHost = null
        currentHiddenSource?.release()
        currentHiddenSource = null
        TouchRouter
            .reset()
        currentClient?.cancelled = true
        // 会话已建立时先发 RTSP TEARDOWN 再关控制通道：真 iPhone 结束 CarPlay 会先
        // 通知车机会话结束；硬关 socket 会让车机侧 CarPlay 服务停留在半初始化状态
        // （「手动取消连接」后车机死机重启的主要嫌疑）。未建立会话时该方法只关 socket。
        currentClient?.teardownAndClose("会话退役")
        currentClient = null
        // 车机昼夜信号随会话失效：断开后「自动」档退回跟随手机系统
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ThemePrefs.setCarNightSignal(ctx, null)
            }
        }
        // 注意：这里绝不能 detach BT 控制会话。本方法在每轮配对起步时也会执行，
        // 而 DiPlay 在 WiFi 隧道就绪前看到 RFCOMM 断开即判 bring-up failed、
        // closeWirelessStack 拆掉整轮无线栈（Bonjour/热点/AirPlay 服务），把已建立
        // 甚至正在推流的 AirPlay 会话一起拆掉——「连上约 3 秒即断」循环的根因
        // （2026-10-16 实测）。BT 退役只在用户终态做，见 disconnect()。
        // 解除会话静默：出站探测恢复（下次会话建立时会再次置位）
        BtSessionGate.sessionActive = false
        // 「强制应用横屏」覆盖随会话结束统一复原（应用回到手机侧正常竖屏形态）。
        // 异步：每应用两次 am compat shell 往返，6 个应用实测 ~1.6s；此前同步跑在
        // 配对前，等于每次连接白等 1.6s（2026-10-18 实测）。执行器串行保证
        // 「先复原上一会话、后写入本会话」的顺序。
        appContext?.let { ctx -> AppOrientationCompat.resetAllAsync(ctx, ::appendLog) }
    }

    /** 会话已建立且投影就绪、但音频尚未启动时，动态补启（投影授权晚于连接的场景）。 */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        val client =
            currentClient ?: run {
                appendLog("MODE: 投屏已就绪（当前无会话——下次连接时自动启用音频）")
                return
            }
        val secret = currentSharedSecret
        if (secret == null) {
            appendLog("MODE: 明文会话（未配对）无共享密钥——不补启音频")
            return
        }
        if (audioSender?.isRunning == true) return
        // 走到这里说明用户刚为「声音」补授了投屏权限：本次会话必须放行音频，
        // 否则 startAudioChannel 会因 audioEnabledForSession=false 直接拒绝，
        // 表现为「补启音频通道」日志之后紧跟着「本次连接未开启声音」（2026-10-28 实测）。
        setAudioEnabled(context, true)
        appendLog("MODE: 会话进行中，动态补启音频通道…")
        startAudioChannel(context, client, secret, currentAudioHost)
    }

    /**
     * 0x5703 到达（拿到车机热点凭据）立即执行——真车 0x4301 的前置条件：
     * 车机在 WiFi 网络上发现手机的 `_carplay-ctrl._tcp`（TXT id=真实蓝牙 MAC）后
     * 主动发 `GET /ctrl-int/1/connect` 邀请，邀请被接受才发 0x4301。
     * 因此这里必须：绑车机 WiFi → 广播 ctrl；晚于 0x4300 则双方互等死锁（Audi 实测）。
     */
    fun onWifiCredentials(wifi: Iap2SenderSession.WifiConfiguration) {
        // 会话推流中收到的 0x5703 是冗余凭据（DiPlay 控制器周期重连会重发一轮
        // Phase 1，2026-10-15 实测「连上后自动断开」循环的推手之一）：此时再走绑定
        // 流程会 release+重绑进程网络（扰动正在推流的 socket）、force 重注册 ctrl
        // mDNS（DiPlay 视为手机重新上线、再拉一轮 RFCOMM）。只刷新密码缓存。
        if (videoSender?.isRunning == true) {
            lastWifiPsk = wifi.passphrase
            lastWifiConfig = wifi
            appendLog("0x5703 冗余凭据（会话推流中）——保持现有 WiFi 绑定与 ctrl 广播不变")
            return
        }
        ssidSink?.invoke(wifi.ssid)
        lastWifiPsk = wifi.passphrase
        lastWifiConfig = wifi
        phaseHint = "拿到车机热点凭据，绑定 WiFi…"
        appendLog(">>> 拿到车机热点凭据（0x5703）——立即绑定 WiFi + 广播 _carplay-ctrl")
        // ctrl 广播已在 startConnection 时发布（catplay：连接前保持发布能力在线）。
        // 这里登记 SSID 并让注册对齐一次（手机若已手动连在车机热点上、Shizuku 不可用
        // 时这是唯一一次重注册机会）；加入热点后 bindCarWifiAndRegister 还会 force
        // 重注册，把组播落到车机所在网络（真车实测必需）。
        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        appendLog("CTRL: 广播已在启动连接时发布，此处对齐到热点 ${wifi.ssid}")
        // 绑定与切换统一走自动加入流程：bindCurrentWifi 是「任意 WiFi 即成功」的盲绑，
        // 手机连着公司 WiFi 时会误判已在车机热点（ctrl 广播落在错误网络 → 双方互等死锁），
        // 必须先核对真实 SSID 再决定绑定或切换
        autoJoinCarWifi(wifi)
    }

    /**
     * 校对真实 WiFi 并加入车机热点（Shizuku shell，Android 12+ `cmd wifi`）：
     *  1. `cmd wifi status` 查当前 SSID——已是车机热点直接绑定（快路径）；
     *  2. 否则 `cmd wifi connect-network` 保存并切换（免系统弹窗、免输密码；
     *     网络被系统保存，下次进入热点范围自动重连，契合上车即连）；
     *  3. 轮询 SSID 确认切换完成后再绑定，各环节检查 [connectCancelled]——
     *     用户取消后不得继续跑（否则 phaseHint 被覆盖、ctrl 广播被重新拉起，表现为「取消不了」）。
     * Shizuku 未就绪/连接失败时回退「系统 WLAN 手动加入」指引（密码可经「去连接」复制）。
     */
    private fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        Bg.run("wifi-auto-join") {
            val shellReady =
                appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
            // 1) WLAN 开关。shell 不可用时跳过检查——requestNetwork 会以 Unavailable 反馈。
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled()) return@run
            if (connectCancelled) return@run
            // 2) 首选应用专属网络（不影响 4G/5G、不会被系统切回、不需要 Shizuku）
            when (val result = joinCarWifiSpecifier(wifi, shellReady)) {
                is WifiCarPlayConnector.JoinResult.Joined -> Unit

                WifiCarPlayConnector.JoinResult.Unsupported -> {
                    appendLog("WIFI: 热点安全类型 ${wifi.securityType} 无法用应用专属网络表达——退回系统级加入")
                    legacyJoinCarWifi(wifi, shellReady)
                }

                WifiCarPlayConnector.JoinResult.Unavailable -> promptSpecifierFailed(wifi, shellReady)
            }
        }
    }

    /**
     * 用应用专属网络（`WifiNetworkSpecifier`）加入车机热点，成功后进程网络已绑定。
     *
     * 先清掉系统里保存的同名热点：旧版本用 `cmd wifi connect-network` 存过它，
     * 只要还在系统配置里，系统就会自动全局连接——无互联网热点于是被当成默认网络，
     * 部分 ROM 连带断掉移动数据，正是本次要解决的场景。
     */
    private fun joinCarWifiSpecifier(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        // 已保存到系统配置里才询问是否清除：不擅自删用户网络（有的场景就是要用
        // 车机流量/互联网，或用户希望系统自动连）。选择保留不影响本轮连接。
        if (shellReady) {
            promptForgetSavedNetworkIfAny(wifi.ssid)
            if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        }
        phaseHint = "正在加入车机热点 ${wifi.ssid}…（不影响移动数据）"
        appendLog(
            ">>> 以应用专属网络加入 \"${wifi.ssid}\"（不保存为系统 WiFi，其它应用继续走 4G/5G）",
        )
        val result =
            synchronized(wifiBindLock) {
                // 「网络已在位」不能只看引用非空：华为 EMUI 在无互联网的应用专属网络上
                // ~70s 后会静默把 WLAN 切回仅扫描（2026-10-25 12:49:02 实测
                // CMD_WIFI_TOGGLED → ScanOnlyModeState），onLost 不回调、boundNetwork
                // 残留——后续每轮只打印「网络已在位」跳过加入，socket 一路 ENONET。
                // 先请系统确认该网络仍在，失效则就地释放，逼出一次真正的重新加入。
                if (!connector.ensureNetworkAlive()) boundCarSsid = null
                val existing = connector.currentNetwork
                if (boundCarSsid == wifi.ssid && existing != null) {
                    appendLog("WIFI: 网络已在位——跳过重复加入")
                    WifiCarPlayConnector.JoinResult.Joined(existing)
                } else {
                    connector.joinCarPlayNetwork(
                        ssid = wifi.ssid,
                        passphrase = wifi.passphrase,
                        securityType = wifi.securityType,
                        timeoutMillis = SPECIFIER_JOIN_TIMEOUT_MS,
                        isCancelled = { connectCancelled },
                    )
                }
            }
        if (result is WifiCarPlayConnector.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
            appendLog(">>> WiFi 已加入车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请")
        } else if (!connectCancelled) {
            appendLog("WIFI: 加入车机热点未成功（${result.javaClass.simpleName}）")
        }
        return result
    }

    /**
     * 会话期间网络绑定失效时的自动恢复（配对重试循环每轮失败时调用）。
     *
     * 2026-10-29 实车根因（日志 18:37:07 → 18:38:12）：会话被车机拆掉后「完整收尾」
     * 释放了 WiFi，重新加入的绑定又在 ~24s 后被 EMUI 静默回收（onLost 不回调），
     * 此时 `_airplay._tcp` 仍解析到车机地址——原实现只会对着死绑定空转直到 120s
     * 配对窗口耗尽（ECONNREFUSED → ENONET），用户只能「取消连接 → 开始连接」手动
     * 重绑后才连得上。这里就地重新加入热点，让配对重试继续跑，用户无需手动干预。
     *
     * 网络健康时只做一次 `getNetworkCapabilities`（零成本），不会扰动现有绑定；
     * 推流中也不动手（重绑会打断正在跑的 socket）。
     *
     * @return true = 网络可用（本来就健康，或已重新加入成功）
     */
    private fun recoverCarWifiNetwork(): Boolean {
        val connector = wifiConnector ?: return false
        if (connectCancelled) return false
        if (videoSender?.isRunning == true) return true
        if (connector.ensureNetworkAlive()) return true
        val wifi = lastWifiConfig ?: return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastWifiRecoveryAtMs < WIFI_RECOVER_COOLDOWN_MS) return false
        lastWifiRecoveryAtMs = now
        boundCarSsid = null
        phaseHint = "车机热点绑定被系统回收——自动重新加入…"
        appendLog("WIFI: 绑定网络已被系统回收——自动重新加入 \"${wifi.ssid}\"（无需手动取消/重连）")
        val result =
            synchronized(wifiBindLock) {
                connector.joinCarPlayNetwork(
                    ssid = wifi.ssid,
                    passphrase = wifi.passphrase,
                    securityType = wifi.securityType,
                    timeoutMillis = WIFI_RECOVER_TIMEOUT_MS,
                    isCancelled = { connectCancelled },
                )
            }
        return if (result is WifiCarPlayConnector.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            phaseHint = "已重新加入车机热点，继续重试配对…"
            ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
            appendLog("WIFI: ✓ 已自动重新加入车机热点 \"${wifi.ssid}\"，继续重试配对")
            true
        } else {
            appendLog("WIFI: 自动重新加入车机热点失败（${result.javaClass.simpleName}）")
            false
        }
    }

    /**
     * 系统里已保存同名热点时，询问用户是否清除（**不擅自删除**）。
     *
     * 为什么要问而不是直接删：保留也有正当用途——用户可能就想让系统自动连车机、
     * 或车机热点确实带互联网（部分车型提供流量）。删除只影响「系统会不会全局
     * 自动连它」，与本轮连接无关；选择「保留」时继续正常连接。
     *
     * 每个 SSID 每个进程只问一次：配对失败重试、端点交接重入都会再次走到这里，
     * 重复弹窗会挡住连接（2026-10-18 实测）。用户的选择在本次进程内记忆。
     *
     * 阻塞直到用户选择（[JOIN_DIALOG_TIMEOUT_S] 超时按「保留」处理——不做破坏性动作）。
     */
    private fun promptForgetSavedNetworkIfAny(ssid: String) {
        if (!forgetPromptHandled.add(ssid)) return
        val ids = savedNetworkIds(ssid)
        if (ids.isEmpty()) return
        appendLog(
            "WIFI: 系统里已保存热点 \"$ssid\"（netId=${ids.joinToString(",")}）" +
                "——询问用户是否清除（保留也能正常连接）",
        )
        val forget =
            askWifiJoinChoice(
                DialogSpec(
                    title = "已保存该车机热点",
                    message =
                        "系统里保存过 \"$ssid\"。保留会让手机在进入范围时自动连接它，" +
                            "若该热点没有互联网，可能导致手机无法使用移动数据。\n\n" +
                            "「清除」只是不再自动连接，不影响本次使用；" +
                            "如果车机热点本身提供网络，选「保留」。",
                    positive = "清除保存",
                    negative = "保留",
                ),
            )
        if (connectCancelled) return
        when (forget) {
            true -> {
                ids.forEach { id ->
                    val result = Shell.exec("cmd wifi forget-network $id", 5_000L)
                    appendLog(
                        "WIFI: 已按用户选择清除系统级保存的热点 \"$ssid\"（netId=$id ok=${result.ok}）",
                    )
                }
            }

            false -> appendLog("WIFI: 用户选择保留系统保存的 \"$ssid\"——继续以应用专属网络连接")

            // 无人应答（UI 不在前台）：按「保留」处理，绝不在用户未确认时删网络
            null -> appendLog("WIFI: 无人应答是否清除保存的网络——按保留处理（不改用户配置）")
        }
    }

    /** 查系统已保存的、SSID 与目标完全一致的网络 id（`cmd wifi list-networks`）。 */
    private fun savedNetworkIds(ssid: String): List<Int> {
        val listing = Shell.exec("cmd wifi list-networks", 5_000L)
        if (!listing.ok) {
            appendLog("WIFI: 无法读取系统已保存网络（exit=${listing.exitCode}）——跳过检查")
            return emptyList()
        }
        return WifiSavedNetworks.idsOf(listing.output, ssid)
    }

    /** WiFi 未开启时的引导与等待；true = 已开启可继续。 */
    private fun waitForWifiEnabled(): Boolean {
        val open =
            askWifiJoinChoice(
                DialogSpec(
                    title = "WiFi 未开启",
                    message = "连接车机热点需要打开手机 WLAN。是否打开 WLAN 设置？",
                    positive = "打开 WLAN 设置",
                    negative = "取消",
                ),
            )
        if (connectCancelled) return false
        when (open) {
            true -> {
                openWlanSettings()
                uiHintSink?.invoke("请在 WLAN 设置中打开 WiFi 开关")
            }

            false -> {
                phaseHint = "已取消——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 用户取消——自动加入中止（WiFi 未开启）")
                return false
            }

            null -> {
                phaseHint = "WiFi 未开启——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 弹窗无人应答——自动加入中止（WiFi 未开启）")
                return false
            }
        }
        var waited = 0
        while (shellWifiDisabled() && waited < WLAN_WAIT_TIMEOUT_MS && !connectCancelled) {
            if (waited == 0) {
                phaseHint = "等待打开 WLAN（最长 120 秒，取消连接可中止）"
                appendLog("WIFI: 等待用户打开 WLAN（最长 120 秒，取消连接可中止）")
            }
            Thread.sleep(2_000)
            waited += 2_000
        }
        if (connectCancelled) {
            phaseHint = "已取消连接"
            return false
        }
        if (shellWifiDisabled()) {
            phaseHint = "WiFi 未开启——无法连接车机热点"
            appendLog("WIFI: 等待超时 WiFi 仍未开启——自动加入中止，请打开 WLAN 后重新连接")
            return false
        }
        return true
    }

    /** 应用专属网络被系统拒绝/超时：让用户重试或转手动连接。 */
    private fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "未能加入车机热点",
                    message =
                        "系统没有允许加入 \"${wifi.ssid}\"（可能点了拒绝，或热点不在范围内）。\n\n" +
                            "「重试」会再次弹出系统确认框。也可以到 WLAN 设置手动连接——" +
                            "手动连接会把热点保存为系统网络，可能影响移动数据。",
                    positive = "重试",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        when (choice) {
            true -> {
                when (val retry = joinCarWifiSpecifier(wifi, shellReady)) {
                    is WifiCarPlayConnector.JoinResult.Joined -> Unit
                    WifiCarPlayConnector.JoinResult.Unsupported -> legacyJoinCarWifi(wifi, shellReady)
                    WifiCarPlayConnector.JoinResult.Unavailable -> {
                        phaseHint = "未能加入车机热点——请到 WLAN 设置手动连接"
                        appendLog("WIFI: 重试仍未加入 \"${wifi.ssid}\"——请到系统 WLAN 手动连接")
                    }
                }
            }

            false -> manualJoinCarWifi(wifi)

            null -> {
                phaseHint = "未能加入车机热点——请重新连接"
                appendLog("WIFI: 弹窗无人应答——自动加入中止")
            }
        }
    }

    /** 手动兜底：密码进剪贴板 + 跳 WLAN 设置，轮询用户是否连上。 */
    private fun manualJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        copyHotspotPsk(wifi.passphrase)
        openWlanSettings()
        phaseHint = "请在 WLAN 设置中连接 ${wifi.ssid}…"
        appendLog(
            "WIFI: 用户选择手动连接——等待 WLAN 连上 \"${wifi.ssid}\"（最长 120 秒）；" +
                "注意：手动连接会把该热点保存为系统网络，若它没有互联网可能影响移动数据",
        )
        val manualDeadline = android.os.SystemClock.uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < manualDeadline) {
            if (connectCancelled) return
            Thread.sleep(5_000)
            if (shellWifiStatus().second == wifi.ssid) {
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
        }
        phaseHint = "尚未连接 ${wifi.ssid}——自动加入已中止"
        appendLog("WIFI: 手动连接等待超时——自动加入中止")
    }

    /**
     * 旧路径（WEP 等 specifier 无法表达的安全类型）：`cmd wifi connect-network`
     * 系统级保存并切换。注意这会保存热点为系统网络——无互联网时可能影响移动数据，
     * 只作为兼容兜底。
     */
    private fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (!shellReady) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                ">>> 手机不在车机热点 \"${wifi.ssid}\" 且 Shizuku 未就绪（无法自动加入）——" +
                    "请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog("WIFI: 手机已在车机热点 \"$current\"——直接绑定")
            bindCarWifiAndRegister(wifi.ssid)
            return
        }
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "连接车机热点",
                    message =
                        "将自动加入 \"${wifi.ssid}\" 热点。该热点无互联网，系统可能自动切回原 WiFi。" +
                            "\n\n出现断连时，请到 WLAN 设置中主动连接 \"${wifi.ssid}\"（手动连接不会被切回）。",
                    positive = "继续自动加入",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        if (choice == false) {
            manualJoinCarWifi(wifi)
            return
        }
        if (choice == null) {
            phaseHint = "未能加入车机热点——请重新连接"
            appendLog("WIFI: 弹窗无人应答——自动加入中止")
            return
        }
        phaseHint = "正在自动加入车机热点 ${wifi.ssid}…"
        appendLog(
            ">>> 手机当前 WiFi: ${current ?: "未连接"}（非车机热点）——" +
                "经 Shizuku 自动切换到 \"${wifi.ssid}\"",
        )
        if (connectCancelled) return
        val security =
            if (wifi.passphrase.isEmpty()) "open" else "wpa2 ${shellQuote(wifi.passphrase)}"
        val result =
            Shell
                .exec("cmd wifi connect-network ${shellQuote(wifi.ssid)} $security", 10_000L)
        if (!result.ok) {
            phaseHint = "自动加入热点失败——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                "WIFI: connect-network 失败（${result.output.take(80).ifEmpty { "exit=${result.exitCode}" }}）" +
                    "——请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        phaseHint = "正在加入车机热点 ${wifi.ssid}…若被切回原 WiFi，请在 WLAN 中手动连接"
        appendLog("WIFI: connect-network 已发起（自动保存网络，等待切换…）")
        // 系统切换是异步的（断开当前网/扫描/关联/DHCP，2-10 秒）：轮询 SSID 确认。
        repeat(20) {
            if (connectCancelled) {
                appendLog("WIFI: 自动加入已取消（用户断开）")
                return
            }
            val ssid = shellWifiStatus().second
            if (ssid == null) {
                // status 命令不可用（非 AOSP 系统）：退化为直接尝试绑定（无 SSID 校验）
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            if (ssid == wifi.ssid) {
                appendLog("WIFI: 已切到车机热点 ${wifi.ssid}")
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            Thread.sleep(800)
        }
        phaseHint = "未能在热点驻留——请到 WLAN 设置手动连接 ${wifi.ssid}"
        uiHintSink?.invoke("未能在热点驻留——请到 WLAN 设置中主动连接 ${wifi.ssid}")
        appendLog(
            ">>> WiFi 自动加入超时（可能被系统切回原 WiFi）——" +
                "请到 系统设置>WLAN 手动连接 \"${wifi.ssid}\"（手动连接不会被自动切回）",
        )
    }

    /**
     * 绑定当前 WiFi（应为车机热点）并重注册 ctrl 广播；绑定超时给出指引。
     *
     * 绑定动作用 [wifiBindLock] 串行化：auto-join 线程与端点交接线程都可能触发，
     * 并发调用 bindCurrentWifi 会互相 release（解绑瞬间 mDNS 广播直接失效）。
     *
     * 等待窗口 [WIFI_BIND_WAIT_S] 必须够长：`requestNetwork(WIFI)` 的 onAvailable
     * 在「手机还没连上任何 WiFi、用户正在系统设置里手动加入热点」时，要等到真正
     * 关联+DCHP 完成才回调。旧值 5s 在奥迪实测中是致命竞态——20:02:09.352 判定
     * 「不在热点」放弃整轮会话，20:02:09.467（**116ms 后**）onAvailable 就到了并
     * 绑定成功；用户被迫多等一轮 BT 重连（+13s）。宁可可中断地多等，也不要假失败。
     *
     * @return 进程网络是否绑定成功
     */
    private fun bindCarWifiAndRegister(ssid: String): Boolean {
        var bound = false
        synchronized(wifiBindLock) {
            // 另一个路径已经绑定同一热点：别再走一遍 release+requestNetwork。
            // 必须核验系统是否仍承认该网络——EMUI 静默回收应用专属网络后引用会残留。
            if (boundCarSsid == ssid && wifiConnector?.isCurrentNetworkAlive() == true) {
                bound = true
            } else {
                boundCarSsid = null
                val latch = java.util.concurrent.CountDownLatch(1)
                wifiConnector?.bindCurrentWifi { ok ->
                    bound = ok
                    latch.countDown()
                }
                // 分片等待：期间用户取消要能立刻退出，不必等满窗口
                val deadline = android.os.SystemClock.uptimeMillis() + WIFI_BIND_WAIT_S * 1_000
                while (!connectCancelled &&
                    bound.not() &&
                    android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                }
                if (bound) boundCarSsid = ssid
            }
        }
        if (bound && !connectCancelled) {
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            appendLog(">>> WiFi 已绑定车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请（随后才回 0x4301）")
        } else if (!connectCancelled) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 $ssid"
            appendLog(
                ">>> 等待 ${WIFI_BIND_WAIT_S}s 仍未绑定到 \"$ssid\"——" +
                    "请在 系统设置>WLAN 手动加入该热点后重新连接",
            )
        }
        return bound
    }

    /**
     * 弹窗询问用户（阻塞调用线程直到选择/关闭/90 秒超时）：
     * true=positive；false=negative；null=无人应答（调用方决定兜底）。
     */
    private fun askWifiJoinChoice(spec: DialogSpec): Boolean? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice: Boolean? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val sink = dialogSink
            if (sink == null) {
                // 无 UI 宿主（Activity 销毁窗口）：宁可中止也不默认替用户决定加入
                latch.countDown()
                return@post
            }
            sink(spec) { result ->
                choice = result
                latch.countDown()
            }
        }
        latch.await(JOIN_DIALOG_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        return choice
    }

    private fun openWlanSettings() {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    ctx.startActivity(
                        android.content
                            .Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    /** 热点密码复制到剪贴板（手动到 WLAN 页连接时粘贴），并提示用户。 */
    private fun copyHotspotPsk(passphrase: String) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm =
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("carplay-wifi", passphrase),
                    )
                    uiHintSink?.invoke("热点密码已复制（${passphrase.length} 位）——粘贴到密码框即可")
                }
            }
        }
    }

    /**
     * WiFi 开关与当前连接的 SSID（一次 `cmd wifi status` 查询）。
     * 输出形如：`Wifi is enabled` + `Wifi is connected to "Redmi K40"`（未连接无此行）。
     */
    private fun shellWifiStatus(): Pair<Boolean, String?> {
        val result =
            Shell
                .exec("cmd wifi status", 5_000L)
        if (!result.ok) return true to null
        val enabled = !result.output.contains("Wifi is disabled")
        val ssid = Regex("connected to \"(.*)\"").find(result.output)?.groupValues?.get(1)
        return enabled to ssid
    }

    private fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** 单引号包裹的 POSIX shell 字面量（SSID/密码可能含空格与特殊字符）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 复现「BT 控制已结束」状态：断开当前任一路径的 iAP2 控制会话（DiPlay 只在此状态应答 RTSP）。 */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    /**
     * 等车机 ctrl 邀请（`GET /ctrl-int/1/connect`）——catplay 的连接触发点。
     *
     * 邀请意味着「车机已在 WiFi 上发现我们，且已把 AirPlay 端口打开」，此时连
     * AirPlay 才不会被 Connection refused。旧实现用固定 sleep 1.6s 赌邀请已到：
     * 车机快时白等，慢时又不够。这里事件驱动，并且若 mDNS 已发现 `_airplay._tcp`
     * （端口确定可连）也立即放行，避免旧机型不发邀请时白等满窗口。
     *
     * @return true = 收到邀请 / 已发现端点；false = 等待窗口耗尽
     */
    private fun awaitCtrlInvite(
        ssid: String,
        timeoutMillis: Long,
    ): Boolean {
        val advertiser = ctrlAdvertiser
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        val startedAt = android.os.SystemClock.uptimeMillis()
        var lastLogAt = startedAt
        var reannounced = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled) return false
            val invites = advertiser?.inviteCount ?: 0
            if (invites > 0) {
                appendLog(
                    "CTRL: ✓ 收到车机邀请（${android.os.SystemClock.uptimeMillis() - startedAt}ms，第 $invites 次）" +
                        "——立即连接 AirPlay",
                )
                return true
            }
            if (endpointBrowser?.awaitEndpoint(0) != null) {
                appendLog(
                    "CTRL: 已发现 _airplay._tcp（未收到邀请，旧机型行为）——直接连接 AirPlay",
                )
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            // catplay 实测：iPhone 的 Bonjour 公告常被车机丢弃——它在每次邀请前都
            // force_announce。这里在等待中途补发一次，覆盖「绑网瞬间公告丢失」的情况。
            if (!reannounced && now - startedAt >= 2_000) {
                reannounced = true
                appendLog("CTRL: 2s 未收到邀请——强制重新公告一次（catplay 同款 force_announce）")
                ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            }
            if (now - lastLogAt > 1_200) {
                appendLog("CTRL: 等待车机 ctrl 邀请…（已等 ${now - startedAt}ms）")
                lastLogAt = now
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        appendLog("CTRL: ${timeoutMillis}ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）")
        return false
    }

    // ---- 以下方法体自 MainActivity 原样迁入（会话逻辑不依赖 Activity）----

    /**
     * 本次会话是否真的会推主音频：与 [startAudioChannel] 的前置门槛保持一致。
     *
     * 用于 modesChanged 的资源声明——不推音频就不占主音频资源。2026-10-22 BMW G32
     * 实测：占着主音频却没有音频流时，车机会等一条永远不会来的流，两次会话分别在
     * 81.0s / 80.7s 被车机整条关掉（mDNS goodbye → 事件通道 RST → 控制通道）。
     */
    private fun audioWillStreamNow(
        client: AirPlayClient,
        sessionKey: ByteArray?,
    ): Boolean {
        if (!audioEnabledForSession) return false
        if (sessionKey == null) return false
        if (CarPlayService.mediaProjection == null && shellCaptureOk != true) return false
        // 记忆只影响排序、不影响「试不试」，所以这里只看到底有没有候选档位
        return client.audioCandidates(preferCompat = audioPreferCompat).isNotEmpty()
    }

    /**
     * 音频通道（手机 → 车机，RTP over UDP）：SETUP type=100 协商 → 播放捕获 → ChaCha 封包推流。
     *
     * 采集来源：优先 Shizuku UserService（shell uid 直采 REMOTE_SUBMIX，免投屏授权），
     * 不可用时回退 MediaProjection 播放捕获；且需用户在「启动连接」弹窗中选择「开启声音」
     * ——音视频同时推流显著增加负载（实测会卡顿/断连）。
     *
     * @return 音频通道是否真正启动（调用方据此决定是否补发 RECORD 激活新加入的流）
     */
    fun startAudioChannel(
        context: Context,
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        host: String?,
    ): Boolean {
        if (!audioEnabledForSession) {
            appendLog("AUDIO: 本次连接未开启车机侧声音（已手动关闭）——仅推视频")
            return false
        }
        if (sharedSecret == null) {
            // 明文会话（et=NONE）没有共享密钥可派生 DataStream 输出密钥：
            // 推出去的 RTP 车机无法解密。宁可不推，也不要制造「有包无声」的假象。
            appendLog("AUDIO: 明文会话（未配对）无共享密钥——跳过音频通道")
            return false
        }
        if (host == null) {
            appendLog("AUDIO: 无接收端可用地址，跳过音频通道")
            return false
        }
        // 采集来源二选一：Shizuku shell 采集（免投屏授权，优先）／MediaProjection 播放捕获（回退）。
        // shell 路径须以「探测真的读得到数据」为准：部分 ROM（华为 EMUI/HKS）接口全成功但不出数据。
        val projection = CarPlayService.mediaProjection
        val shellService = CarInputClient.service
        val shellReady = shellService != null && shellCaptureOk == true
        if (projection == null && !shellReady) {
            appendLog(
                if (shellService != null && shellCaptureOk == false) {
                    "AUDIO: 本机 ROM 拦截 shell 录音（免授权采集不可用）——需投屏授权后即时补启（无需重连）"
                } else {
                    "AUDIO: 既无 Shizuku 采集服务也无投屏授权——音频通道未启动"
                },
            )
            return false
        }
        return try {
            // 车机身份 → 只用于「本车机哪些音频档位踩过雷」的持久化记忆（决定排序）。
            val identity = client.receiverIdentity()
            // 音频封装参数已定案（见 AudioTuning），不再提供切换
            val tuning = AudioTuning.RAW_SPF
            // 记忆只用于排序：用户显式开了声音，本会话就真试一次。此前「全档位拆链后
            // 永久静音」会让用户再也推不出声音（2026-10-22 反馈），也违背了弹窗的显式选择。
            val entry = AudioProfileMemory.load(context.applicationContext, identity)
            val candidates = client.audioCandidates(preferCompat = audioPreferCompat)
            val ordered = AudioProfileMemory.order(candidates, entry)
            if (ordered.isEmpty()) {
                appendLog("AUDIO: 没有可用音频档位（候选=${candidates.map { it.label }}）——仅推视频")
                return false
            }
            appendLog(
                "AUDIO: 音频档位候选=${ordered.map { it.label }}" +
                    "（本车机记忆：${AudioProfileMemory.describe(entry)}；" +
                    "封装=${tuning.detail}）",
            )
            // SETUP 失败不会扰动会话（车机只是不接这条流的请求），所以本会话内就把候选
            // 试完，不必等下一次连接。这一点对当前场景很关键：102/AAC-LC 与 100/PCM 是
            // 两条完全不同的通路，前者不被接受时应当立刻退到后者，而不是让用户白连一次。
            // 音频起流前先确保资源归属：连接时若没开声音，modesChanged 只声明了主屏，
            // 这里补发一次（否则「未声明就推音频」会被车机在 ~35s 后整链拆掉）。
            client.claimMainAudioResource()
            var chosen: AudioProfile? = null
            var streamInfo: AirPlayClient.AudioStreamInfo? = null
            for (candidate in ordered) {
                val result = client.runAudioStream(sharedSecret, candidate, tuning)
                if (result != null) {
                    chosen = candidate
                    streamInfo = result
                    break
                }
                // SETUP 被拒不等于「车机不兼容」：可能只是资源仲裁/网络抖动，
                // 不能据此把整台车机的音频判死（置 disabled 只认「拆链」证据）。
                AudioProfileMemory.recordRejected(
                    context.applicationContext,
                    identity,
                    candidate.key,
                    ordered.map { it.key }.toSet(),
                    markExhausted = false,
                )
                appendLog("AUDIO: 档位 ${candidate.label} 协商失败——本会话继续试下一档")
            }
            val profile = chosen
            val audio = streamInfo
            if (profile == null || audio == null) {
                appendLog("AUDIO: 全部候选档位协商失败（共 ${ordered.size} 档）——本次仅推视频")
                return false
            }
            activeAudioProfile = profile
            activeAudioReceiverKey = identity
            activeAudioStartedAt = android.os.SystemClock.uptimeMillis()
            val sender =
                AudioSender(
                    host = host,
                    dataPort = audio.dataPort,
                    streamKey = audio.streamKey,
                    log = { line -> appendLog(line) },
                    context = context.applicationContext,
                    sampleRate = audio.sampleRate,
                    codec = profile.codec,
                    streamType = profile.streamType,
                    adtsFrames = tuning.adts,
                    // 同步包目标端口：车机在 SETUP 应答里下发。缺了它就没有 RTP↔NTP 锚点，
                    // 接收端只能「按到达时刻猜」，严格的实现干脆不排播（2026-10-20 实测）。
                    controlPort = audio.controlPort,
                    // shell 采集：停流时请 UserService 收掉 REMOTE_SUBMIX 录音器
                    onCaptureStop = { runCatching { CarInputClient.service?.stopAudioCapture() } },
                )
            audioSender = sender
            val shellPipe =
                if (shellReady) {
                    runCatching {
                        CarInputClient.service?.startAudioCapture(
                            audio.sampleRate,
                            android.media.AudioFormat.CHANNEL_IN_STEREO,
                            AudioSender.PACKET_BYTES * 8,
                        )
                    }.getOrElse {
                        appendLog("AUDIO: shell 采集启动异常 - ${it.message}")
                        null
                    }
                } else {
                    null
                }
            if (shellPipe != null) {
                appendLog("AUDIO: 本次采集来源 = Shizuku shell（免投屏授权）")
            } else if (projection != null) {
                appendLog("AUDIO: 本次采集来源 = 投屏授权（MediaProjection）")
            }
            val started =
                when {
                    shellPipe != null -> sender.startShellCapture(shellPipe) // 免授权
                    projection != null -> sender.start(projection) // 回退投屏授权
                    else -> false
                }
            if (!started) {
                audioSender = null
                activeAudioProfile = null
                activeAudioStartedAt = 0L
                false
            } else {
                true
            }
        } catch (error: Exception) {
            appendLog("AUDIO: 通道启动异常 - ${error.message}")
            audioSender = null
            activeAudioProfile = null
            activeAudioStartedAt = 0L
            false
        }
    }

    /**
     * 会话结束时的**音频拆链归因**（2026-10-19 Honda HFT / Alpine 实测新增）。
     *
     * 现象：选了「视频+音频」后会话能建立、视频也正常，但音频起流约 6s 后车机把整条
     * AirPlay 会话拆掉（`EVENT: 读取结束 - Connection reset`），只推视频则稳定。
     * 车机侧不会告诉我们「哪一档音频不兼容」，只能用存活时间反推：
     *  - 音频起流后**没跑满** [AUDIO_PROFILE_PROBE_MS] 就整链被拆 → 判该档位不兼容，
     *    记入本车机名单（全部档位都被判死则置 disabled，下次直接只推视频）；
     *  - 跑满了 → 记为该档位可用，下次连接直接首选。
     *
     * 注意只在「确实起过音频」时归因；纯视频会话结束不做任何记忆改写。
     */
    private fun attributeAudioTeardown(stoppedByPeer: Boolean) {
        val profile = activeAudioProfile ?: return
        val startedAt = activeAudioStartedAt
        val identity = activeAudioReceiverKey
        val lived = if (startedAt > 0) android.os.SystemClock.uptimeMillis() - startedAt else -1L
        // 本轮记忆很快失效：下次连接重新按最新记忆选档
        activeAudioProfile = null
        activeAudioStartedAt = 0L
        if (startedAt == 0L || identity.isBlank()) return
        // 视频数据口先被对端拆掉（Broken pipe / reset / 网络不可达）时，整链收摊与音频
        // 无关——此时按存活时长反推会把唯一可用档位误判成不兼容（2026-10-25 15:48:05
        // 与 15:54:55 两次实测：VIDEO 先失败，随后把 t102/media 记入黑名单）。
        if (stoppedByPeer) {
            appendLog(
                "AUDIO: 档位 ${profile.label} 起流 ${lived.coerceAtLeast(0) / 1000}s 后被拆——" +
                    "本次是视频/网络侧先断开（不归因于音频档位，记忆不改）",
            )
            return
        }
        val ctx = appContext?.applicationContext
        if (lived >= AUDIO_PROFILE_PROBE_MS) {
            AudioProfileMemory.recordWorking(ctx, identity, profile.key)
            appendLog("AUDIO: 档位 ${profile.label} 存活 ${lived / 1000}s——记为本车机可用档位")
            return
        }
        val allKeys = audioCandidateKeys(identity)
        AudioProfileMemory.recordRejected(ctx, identity, profile.key, allKeys)
        val after = AudioProfileMemory.load(ctx, identity)
        appendLog(
            "AUDIO: ⚠ 档位 ${profile.label} 起流 ${lived / 1000}s 后整条会话被车机拆掉——" +
                "判定为本车机不兼容档位（已记入名单）。" +
                if (after.disabled) {
                    "全部档位均踩过拆链：下次连接仍会重试（已排到最后），声音可能再次中断。"
                } else {
                    "下次点「启动连接」会自动换用下一档；也可直接选「开启声音（兼容档）」。"
                },
        )
        uiHintSink?.invoke(
            if (after.disabled) {
                "本车机音频档位全部踩过雷，重连仍会尝试（可能再次断开）"
            } else {
                "该音频档位导致断开，已记录；重连会自动换档"
            },
        )
    }

    /** 该车机上「所有可能用到的档位键」——用于判断是否已全部试穿。 */
    private fun audioCandidateKeys(identity: String): Set<String> =
        runCatching {
            val client = currentClient ?: return@runCatching emptySet()
            val preferred = client.audioCandidates(preferCompat = audioPreferCompat).map { it.key }
            val alternate = client.audioCandidates(preferCompat = !audioPreferCompat).map { it.key }
            (preferred + alternate).toSet()
        }.getOrDefault(emptySet())

    /**
     * 端点补全：优先吸收 mDNS 解析出的地址，再决定是否等待/兜底。
     *
     * 关键（2026-10-19 奥迪 4851 实车日志）：0x4301 只给了
     * `fe80::3ca8:64ff:fe7c:293e`（IPv6 链路本地），而 `_airplay._tcp` 解析出的是
     * `10.174.189.1:7000`——车机实际用 IPv4 通的。**不能因为 0x4301 带了 IPv6 就不看
     * mDNS**：那样会把唯一验证过的 IPv4 路径丢掉。这里先做一次「零等待」缓存合并，
     * 拿到 IPv4 就按 EndpointHosts 的优先级（IPv4 → IPv6）使用；缓存没命中才按需等。
     */
    private fun resolveEndpointHost(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val cached = endpointBrowser?.awaitEndpoint(0)
        if (cached != null) {
            effective = mergeBrowserEndpoint(effective, cached, source, needPort = effective.airPlayPort <= 0)
        }
        val needHost = !EndpointHosts.hasUsable(effective.ipAddresses)
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        phaseHint = "mDNS 解析车机地址…"
        appendLog(">>> [$source] 0x4301 端点不完整（port=${effective.airPlayPort} ips=${effective.ipAddresses}），取 mDNS 车机 _airplay._tcp…")
        // 用常驻发现器的缓存（绑网时已开始后台发现，通常此时已命中）。
        // 此前现起一次发现并硬重试 3×6s：车机 mDNS 稳定时白等，不稳定时又拖满 18s。
        // 网段过滤（防误连同网 macOS 接收器）由 browser 的 accept 统一做。
        val info =
            endpointBrowser?.awaitEndpoint(ENDPOINT_RESOLVE_WAIT_MS) ?: run {
                // 兜底：热点场景车机 IP = 网关（Audi 实测 .1，但取网关更通用）。
                // mDNS 在部分网络/机型上不稳定（2026-10-11 实测车机 mDNS 时有时无）；
                // 网关地址 + 默认端口 7000 直接连，AirPlay 端口是固定约定。
                val gateway = wifiConnector?.gatewayAddress()
                if (gateway != null && EndpointHosts.candidates(listOf(gateway)).isNotEmpty()) {
                    appendLog(">>> [$source] mDNS 未发现，兜底使用网关地址 $gateway:7000（车机热点网关=车机）")
                    return effective.copy(
                        ipAddresses = listOf(gateway) + effective.ipAddresses,
                        airPlayPort = if (needPort) 7000 else effective.airPlayPort,
                    )
                }
                appendLog(">>> [$source] mDNS 未发现车机 AirPlay 服务（车机可能尚未启动无线会话）")
                return effective
            }
        return mergeBrowserEndpoint(effective, info, source, needPort)
    }

    /**
     * 把一次 mDNS 解析结果并入端点：地址**追加到最前**（EndpointHosts 会按
     * IPv4 → 全局 IPv6 → 链路本地 IPv6 重排，顺序只影响同类型内部）。
     * 其余字段只在缺失时补（0x4301 的权威值不覆盖）。
     */
    private fun mergeBrowserEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        info: android.net.nsd.NsdServiceInfo,
        source: String,
        needPort: Boolean,
    ): Iap2SenderSession.CarPlayEndpoint {
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
            appendLog(">>> [$source] mDNS 解析到车机地址 $address:${info.port}")
        }
        if (needPort && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        // TXT 公钥兜底（pair-verify 校验用；0x4301 缺失时只能从这里拿）
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        return result
    }

    /**
     * Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时的热更新入口。
     *
     * 三种归属：
     *  - 正在推流：冗余端点，忽略（与 [onCarPlayEndpoint] 同一防护，避免拆掉健康会话）；
     *  - 交接/配对进行中：热更新当前 AirPlayClient 的端点，配对循环下一轮即用新地址；
     *    若 client 尚未创建（还在绑网/等邀请）先暂存，配对线程起步时并入；
     *  - 上一轮已失败（无会话在跑）：按新端点重走一轮完整交接——Audi MMI 9310
     *    实测 0x4301 在 120s 配对窗口耗尽后 28s 才到，旧实现把它当日志垃圾丢掉，
     *    车机等 15s 无人连入即拆链，表现为「连上后自动断开」。
     */
    private fun onLateEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        if (connectCancelled) {
            appendLog(">>> [$source] 0x4301 晚到（已取消连接）——忽略")
            return
        }
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 0x4301 晚到（推流中）——忽略冗余端点")
            return
        }
        val client = currentClient
        if (sessionStarting.get()) {
            if (client != null && client.pairingInProgress) {
                // 合并而非替换：0x4301 可能不带 IPv4（Audi MMI 9310 实测端点解析依赖
                // mDNS/网关），直接替换会把已解析到的地址抹掉，配对循环反而连不上。
                val merged = preferEndpoint(endpoint, client.currentEndpoint)
                val changed = client.updateEndpoint(merged, "0x4301 晚到")
                appendLog(
                    if (changed) {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点已热更新，下一轮重试用新地址"
                    } else {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点无变化，忽略"
                    },
                )
            } else {
                pendingEndpointUpdate = endpoint
                appendLog(">>> [$source] 0x4301 晚到（交接尚未创建配对客户端）——暂存，配对起步时采用")
            }
            return
        }
        // 用户已断开（BT 组件已停）时不得用晚到消息复活会话
        if (bluetoothClient?.isRunning != true && bluetoothServer?.isRunning != true) {
            appendLog(">>> [$source] 0x4301 晚到（蓝牙链路已退役）——忽略")
            return
        }
        appendLog(">>> [$source] 0x4301 晚到（上一轮已结束）——按新端点重走一轮连接")
        onCarPlayEndpoint(endpoint, "$source/0x4301 晚到")
    }

    /** 把交接期间暂存的晚到端点并入新建的客户端（建 client 与收到 0x4301 的竞态窗口）。 */
    private fun drainPendingEndpoint(client: AirPlayClient) {
        val late = pendingEndpointUpdate ?: return
        pendingEndpointUpdate = null
        if (client.updateEndpoint(preferEndpoint(late, client.currentEndpoint), "交接期间晚到 0x4301")) {
            appendLog("AIRPLAY: 交界窗口的 0x4301 端点已并入配对客户端")
        }
    }

    /**
     * 配对循环的端点刷新器（非阻塞）：只读浏览器缓存的 `_airplay._tcp`，
     * 有则并入 IP/端口/公钥等字段。返回 null = 无新信息（配对循环继续用当前端点）。
     */
    private fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? {
        val info = endpointBrowser?.awaitEndpoint(0) ?: return null
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
        }
        if (result.airPlayPort <= 0 && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        if (result == endpoint) return null
        appendLog(
            ">>> [$source] 配对期间 mDNS 刷新端点 → " +
                "${result.ipAddresses.firstOrNull()}:${result.airPlayPort}",
        )
        return result
    }

    private fun txtValue(
        info: android.net.nsd.NsdServiceInfo,
        key: String,
    ): String? =
        info.attributes[key]
            ?.toString(Charsets.UTF_8)
            ?.trimEnd { it.code == 0 }
            ?.takeIf { it.isNotEmpty() }

    /**
     * 晚到端点优先：车机明确告知的端口/公钥优先于 mDNS 合成值，
     * 但 IP 列表取并集（0x4301 可能不带 IPv4，Audi MMI 9310 实测即如此）。
     */
    private fun preferEndpoint(
        late: Iap2SenderSession.CarPlayEndpoint,
        fallback: Iap2SenderSession.CarPlayEndpoint,
    ): Iap2SenderSession.CarPlayEndpoint =
        late.copy(
            airPlayPort = if (late.airPlayPort > 0) late.airPlayPort else fallback.airPlayPort,
            ipAddresses = (late.ipAddresses + fallback.ipAddresses).distinct(),
            publicKey = late.publicKey ?: fallback.publicKey,
            deviceIdentifier = late.deviceIdentifier ?: fallback.deviceIdentifier,
            sourceVersion = late.sourceVersion ?: fallback.sourceVersion,
            advertisedFeatures = late.advertisedFeatures ?: fallback.advertisedFeatures,
        )

    /**
     * 初始 SETUP（带重连重试）。
     *
     * 已配对路径：配对循环刚连通过，SETUP 一次即可（失败就是真失败，交给降级/收尾）。
     * 明文会话路径：车机的 AirPlay 端口可能要到「用户点车机图标」后才开，
     * catplay 同样是「收到邀请才连」；这里在 [INITIAL_SETUP_WINDOW_MS] 内每 2s
     * 重连一次并重试 SETUP，避免端口晚开 1~2 秒就整轮失败。
     */
    private fun runInitialSetup(
        client: AirPlayClient,
        plainSession: Boolean,
        noPairing: Boolean,
    ): AirPlayClient.SetupInfo? {
        val first = client.runSetup()
        if (first != null || !plainSession) return first
        val deadline = android.os.SystemClock.uptimeMillis() + INITIAL_SETUP_WINDOW_MS
        var attempt = 1
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            attempt++
            appendLog("AIRPLAY: 明文 SETUP 第 ${attempt - 1} 次未通过——2s 后重连重试（车机 AirPlay 端口可能尚未开放）")
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return null
            }
            if (connectCancelled) return null
            client.close()
            if (!client.reconnect(2_000)) continue
            val retried = client.runSetup()
            if (retried != null) {
                appendLog("AIRPLAY: ✓ 明文 SETUP 在第 $attempt 次尝试成功")
                return retried
            }
            // 端口开了但 SETUP 仍被拒：TXT 判断可能错了，交给调用方的加密升级路径
            if (!noPairing) return null
        }
        return null
    }

    /** BT 会话交接点：拿到 0x5703/0x4301 后两条路径流程一致——绑 WiFi → AirPlay 配对 → SETUP → 推流。 */
    private fun onCarPlayEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        val context = appContext ?: return
        // 冗余端点防护：接收端控制器（DiPlay 实测）在我们配对推流后才完成既有的
        // RFCOMM 重连并重发 0x4301。此时重启配对会 retireSession 掉健康会话
        // （编码器 stop 竞态 = 「连上后自动断开」循环的根因）。推流中的端点一律忽略；
        // 真断连时推流自行终止（isRunning=false），下一个端点自然接续重连。
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 会话推流中——忽略冗余端点（接收端周期重连的重复 0x4301）")
            return
        }
        if (!sessionStarting.compareAndSet(false, true)) {
            appendLog(">>> [$source] 已有会话在处理中，忽略本次端点（等待上一轮结束）")
            return
        }
        // 新一轮交接开始：清掉上一轮（可能中途失败）遗留的晚到端点暂存，
        // 否则它会在本轮的 drain 阶段覆盖掉本会话刚解析出的端点。
        pendingEndpointUpdate = null
        appendLog(
            ">>> [$source] 拿到接收端热点：SSID=${endpoint.ssid}；" +
                "密码=${if (endpoint.passphrase.isEmpty()) "(空)" else "已获取"}",
        )
        phaseHint = "蓝牙已连上车机，解析热点地址…"
        ssidSink?.invoke(endpoint.ssid)
        // 诊断（老车机排查必看）：地址是 IPv4 还是只有 IPv6、TXT features 是否声明配对能力。
        // 奥迪 4xxx 两种「连不上」都源于这里——旧版日志里完全没有这两项。
        val addresses = endpoint.ipAddresses
        val hasIpv4 = addresses.any { EndpointHosts.isIpv4(it) }
        val pairingFlag = AirPlayFeatures.supportsPairing(endpoint.advertisedFeatures)
        appendLog(
            ">>> [$source] 端点诊断：地址=${addresses.ifEmpty { listOf("(无)") }}" +
                "（${if (hasIpv4) {
                    "IPv4"
                } else if (addresses.isEmpty()) {
                    "无地址"
                } else {
                    "仅 IPv6"
                }}）" +
                " features=" + (endpoint.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "未知") +
                " HomeKit配对=" +
                when (pairingFlag) {
                    true -> "声明支持"
                    false -> "未声明（按 pre-HomeKit 明文会话处理）"
                    null -> "未知（按需配对）"
                },
        )
        // 先绑网络再解析端点：mDNS 组播跟随 bindProcessToNetwork 的网络，解析放前面
        // 只会拿到旧网络的结果（此前靠 resolveEndpointHost 内部重试掩盖，白等数秒）。
        // 绑定完成后再并行做三件事：ctrl 广播针对新网络重注册、后台发现 _airplay._tcp、
        // 等车机 ctrl 邀请（车机已发现我们的权威信号）。
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!bound) {
                phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${endpoint.ssid}"
                appendLog(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${endpoint.ssid}\" 后断开重连")
                sessionStarting.set(false)
                return@proceed
            }
            // 网络绑定完成：ctrl 广播重新注册（落到车机所在网络），同时开始后台 mDNS 发现。
            // 两者都发生在等邀请期间，不额外占用时间。
            ctrlAdvertiser?.ensureForNetwork(endpoint.ssid, force = true, network = wifiConnector?.currentNetwork)
            endpointBrowser?.resetCache()
            endpointBrowser?.start()
            // 0x4301 自带完整地址+端口 = 接收端已明确告知「连这里」，无需等邀请
            // （DiPlay 测试台路径：本轮直接配对，省掉固定等待）。
            // IPv6 也算地址：奥迪 4xxx 的 0x4301 常只给 fe80::（旧判据只看 IPv4，
            // 会把这类端点当成「不完整」，白等 ctrl 邀请再退回网关兜底）。
            val endpointSelfContained =
                endpoint.airPlayPort > 0 && EndpointHosts.hasUsable(endpoint.ipAddresses)
            Bg.run("airplay-pairing") {
                try {
                    if (connectCancelled) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
                    }
                    if (endpointSelfContained) {
                        appendLog("AIRPLAY: 0x4301 端点完整（${endpoint.ipAddresses.firstOrNull()}:${endpoint.airPlayPort}）——直接配对，不等 ctrl 邀请")
                    } else {
                        // 等 ctrl 邀请（catplay CarPlayCtrlServer：邀请=车机已发现手机且
                        // AirPlay 窗口已开，收到即刻连接）。此前是「固定睡 1.6s 赌它已到」，
                        // 快的时候白等、慢的时候不够；改为事件驱动。
                        awaitCtrlInvite(endpoint.ssid, CTRL_INVITE_WAIT_MS)
                    }
                    // 端点解析放在邀请之后：车机的 `_airplay._tcp` 常在受邀后才上线，
                    // 且这一步最长 3s——必须在后台线程做，不能占住网络回调线程。
                    var effectiveEndpoint = resolveEndpointHost(endpoint, source)
                    // 交接流程起步期间车机补发的 0x4301（比 mDNS 合成更权威）在此并入。
                    pendingEndpointUpdate?.let { late ->
                        pendingEndpointUpdate = null
                        effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)
                        appendLog(
                            "AIRPLAY: 并入交接期间晚到的 0x4301 端点 → " +
                                "${effectiveEndpoint.ipAddresses.firstOrNull()}:${effectiveEndpoint.airPlayPort}",
                        )
                    }
                    if (connectCancelled) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
                    }

                    fun newClient() =
                        AirPlayClient(
                            effectiveEndpoint,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },
                            retryTick = { attempt ->
                                // 配对重试期间车机仍可能没探测到 ctrl（Bonjour 漏掉首次公告）：
                                // 周期性强制重播一次，并把 mDNS 现状写进日志——否则排查时
                                // 只看得到「端口未开」的重试刷屏，无法判断是车机没邀请还是
                                // 地址不对（2026-10-19 奥迪 0.1.7 日志就缺这两条信息）。
                                ctrlAdvertiser?.ensureForNetwork(
                                    endpoint.ssid,
                                    force = true,
                                    network = wifiConnector?.currentNetwork,
                                )
                                val invites = ctrlAdvertiser?.inviteCount ?: 0
                                val found = endpointBrowser?.awaitEndpoint(0)
                                appendLog(
                                    "AIRPLAY: 重试 #$attempt —— 已重新公告 ctrl；" +
                                        "车机邀请=$invites 次，mDNS _airplay._tcp=" +
                                        (found?.let { "${it.host?.hostAddress}:${it.port}" } ?: "未发现"),
                                )
                            },
                        ).also { created ->
                            // 配对重试期间网络绑定被系统回收时就地重新加入车机热点，
                            // 避免对着死绑定空转到窗口耗尽（详见 recoverCarWifiNetwork）。
                            created.wifiRecovery = { recoverCarWifiNetwork() }
                            // 车机媒体键（iAP2 0x6802 通路）：方向盘/面板的上一曲、下一曲。
                            // 放在这里而不是调用点，保证重连重建 client 时也挂得上。
                            created.onMediaKey = { keyCode ->
                                val injected = dispatchMediaKey(keyCode)
                                appendLog(
                                    "EVENT: 车机媒体键（iAP2 0x6802）→ keyCode=$keyCode " +
                                        if (injected) "已注入(shell)" else "已派发(AudioManager，可能被 ROM 忽略)",
                                )
                            }
                        }
                    retireSession()
                    // 会话静默降噪（2026-10-10）：从交接点起暂停其它接收端的出站连接探测
                    // （典型：连着 DiPlay 测试台时不再噪音式探测 Audi）。会话结束/断开时解除。
                    BtSessionGate.sessionActive = true
                    // 第一阶段：保持 BT 在位直接配对 + SETUP（协议上正确的形态）
                    phaseHint = "AirPlay 配对中…"
                    var client = newClient()
                    currentClient = client
                    drainPendingEndpoint(client)
                    // pre-HomeKit 老车机（CarPlay 210.81 时代，奥迪 4xxx 固件）在
                    // `_airplay._tcp` 里**不声明** HK_PAIRING_AND_ENCRYPT：它们只接受
                    // 明文 SETUP（et=NONE）+ AES-CTR 屏幕流，走 pair-setup 必然失败。
                    // catplay 的接收端分类 1 即此形态；旧实现会在这里空转 120s 配对窗口，
                    // 用户看到的就是「连不上」。
                    val noPairing =
                        AirPlayFeatures.supportsPairing(client.currentEndpoint.advertisedFeatures) == false
                    var keys: PairVerifyClient.ControlKeys? =
                        if (noPairing) {
                            client.skipPairing("TXT features 未声明 HK_PAIRING_AND_ENCRYPT")
                            client.reconnect()
                            null
                        } else {
                            client.runPairing()
                        }
                    if (connectCancelled) {
                        retireSession()
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
                    }
                    if (keys == null && !noPairing && !client.pairSetupRejected && client.attemptedPairing) {
                        // 第二阶段（仅测试台 DiPlay）：复现「BT 控制已结束」状态再试一次。
                        // 真车（Audi）绝不能断 BT——真无线 CarPlay 的 BT 控制通道全程保活，
                        // 断开会让车机拆掉整轮 AirPlay 窗口（实车实测）。
                        // 门槛：仅当本轮真的连上过接收端 RTSP（attemptedPairing）才用此 hack；
                        // 端点未解析（无 IP）/端口未开时绝不值得断 BT（2026-10-11 踩过：
                        // mDNS 失败也触发 detach，白白拆掉车机窗口）。
                        detachActiveBtSessions()
                        client = newClient()
                        currentClient = client
                        drainPendingEndpoint(client)
                        keys = client.runPairing()
                        if (connectCancelled) {
                            retireSession()
                            appendLog("AIRPLAY: 用户已取消——中止配对")
                            sessionStarting.set(false)
                            return@run
                        }
                    }
                    // keys == null 时仍有合法路径：**catplay 的 et=NONE 明文会话**。
                    //  - 接收端明确不支持配对（pre-HomeKit / TXT 无 HK_PAIRING_AND_ENCRYPT）；
                    //  - 配对彻底失败但 RTSP 确实连上过（有些车只认明文会话，
                    //    pair-setup/pair-verify 全拒——catplay 正是「先试明文、失败才配对」，
                    //    我们反过来：优先配对（保住 DiPlay/已知车机），失败后降级明文兜底）。
                    val plainSession =
                        keys == null && (client.pairingUnsupported || client.attemptedPairing)
                    if (plainSession) {
                        phaseHint = "建立明文会话…"
                        appendLog(
                            "AIRPLAY: 配对不可用（${if (client.pairingUnsupported) "接收端不支持" else "配对失败"}）" +
                                "——改用 et=NONE 明文会话 + AES-CTR 屏幕流（catplay 接收端分类 1/2）",
                        )
                        // 配对循环结束时 socket 已关/状态机不可用：SETUP 前必须重连一次。
                        // 失败不直接判死：车机 AirPlay 端口可能还没开，下面会按窗口重试。
                        if (!client.reconnect()) {
                            appendLog("AIRPLAY: 明文会话首次重连未成功——继续按窗口重试 SETUP")
                        }
                    }
                    // keys 是 lambda 内的可变捕获变量：Kotlin 无法智能转换，先固化成不可变值。
                    val pairedKeys = keys
                    if (pairedKeys != null || plainSession) {
                        phaseHint = if (pairedKeys != null) "配对完成，建立 CarPlay 会话…" else "建立明文会话…"
                        if (pairedKeys != null) {
                            appendLog(
                                "AIRPLAY: ✓ 配对完成 writeKey=" +
                                    pairedKeys.phoneWriteKey.joinToString("") { "%02x".format(it) }.take(16) + "… " +
                                    "readKey=" + pairedKeys.phoneReadKey.joinToString("") { "%02x".format(it) }.take(16) + "…",
                            )
                        }
                        // 已配对 → 共享密钥；明文会话 → null（流加密降级为 AES-CTR 零密钥）
                        var sessionKey = pairedKeys?.sharedSecret
                        var setup = runInitialSetup(client, plainSession, noPairing)
                        if (setup == null && plainSession && noPairing) {
                            // catplay 的「加密升级」路径：只有在**我们主动跳过配对**（TXT 未声明
                            // HK_PAIRING_AND_ENCRYPT）且明文 SETUP 仍失败时才补做。
                            // 反过来（配对先试过且失败）再试一次没有意义，只会多等 30s。
                            val upgraded = client.retryWithPairing(PAIRING_UPGRADE_WINDOW_MS)
                            if (upgraded != null) {
                                sessionKey = upgraded.sharedSecret
                                setup = client.runSetup()
                            }
                        }
                        if (setup != null) {
                            // 记录会话凭据：投影授权晚于连接时用于动态补启音频。
                            // 用 client.currentEndpoint 而非 effectiveEndpoint：配对期间
                            // mDNS 刷新过端点时，真正连上的是 client 里的地址（2026-10-18：
                            // 0x4301 的定长 IP 字段带 NUL，mDNS 给的是干净地址，取旧值
                            // 会让视频端口/keepAlive 用脏地址 → 一律解析失败）。
                            currentSharedSecret = sessionKey
                            currentAudioHost =
                                client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                            client.connectEventChannel(sessionKey, setup) { command ->
                                when (command["type"]) {
                                    "hidSendReport" -> {
                                        val uuid = command["uuid"]?.toString()
                                        val report = command["hidReport"] as? ByteArray
                                        // 车机媒体键（Consumer Control）走 hidSendReport 通路，
                                        // 判定**以设备描述符为准**（不按 uuid、不按车机型号写死）：
                                        // 描述符声明了 Consumer 页(0x0C)的设备才当媒体键。
                                        // 报告语义两种（ConsumerKeys.usagesFor 统一处理）：
                                        //  - Array(81 00)：字节是 Usage 数组下标（奥迪）；
                                        //  - Variable/位图(81 02)：每 usage 占 1bit（本田、BMW——
                                        //    2026-10-23 实测 BMW ButtonInput 报 0x01/0x02/0x04 =
                                        //    bit0/1/2 → usages[0..2]=0xB5 下一曲/0xB6 上一曲/0xB0 播放；
                                        //    此前错用 Array 语义把位图当下标，全部「无对应键」丢弃）。
                                        val descriptor = uuid?.let { setup.hidDescriptors[it] }
                                        // 旋钮设备（含 Wheel）的描述符里也嵌了 Consumer 页（AC Back），
                                        // 必须先判旋钮：否则旋钮的滚轮报告（byte0=0）会被
                                        // 「Consumer 释放」分支吞掉，永远到不了旋钮处理。
                                        val knobMap =
                                            descriptor?.let(KnobKeys::parseDescriptor)
                                        val keyMap =
                                            if (knobMap == null) {
                                                descriptor?.let(ConsumerKeys::parseDescriptor)
                                            } else {
                                                null
                                            }
                                        val pressedUsages =
                                            when {
                                                // 有描述符：只有 Consumer 设备才解析（非 Consumer → 空）
                                                keyMap != null && report != null ->
                                                    keyMap.usagesFor(report)
                                                // 描述符缺失（未知车机）：保守回退——仅 1 字节报告，
                                                // 值当 Usage ID 直读
                                                descriptor == null && report != null && report.size == 1 ->
                                                    listOfNotNull(
                                                        (report[0].toInt() and 0xFF).takeIf { it != 0 },
                                                    )
                                                else -> emptyList()
                                            }
                                        val contacts =
                                            if (pressedUsages.isEmpty() && knobMap == null && report != null) {
                                                // 触摸格式按设备描述符判定（Digitizer 页 + 单指/多槽），
                                                // 描述符不可用时按报文长度回退——不依赖车机型号或 uuid
                                                val touchFormat =
                                                    descriptor?.let(TouchReports::formatFromDescriptor)
                                                TouchReports.parse(report, touchFormat)
                                            } else {
                                                null
                                            }
                                        if (pressedUsages.isNotEmpty()) {
                                            pressedUsages.forEach { consumerUsage ->
                                                val keyCode = ConsumerKeys.keyCodeFor(consumerUsage)
                                                if (keyCode != 0) {
                                                    val injected = dispatchMediaKey(keyCode)
                                                    appendLog(
                                                        (
                                                            "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                                "（uuid=$uuid usage=0x%02x → keyCode=$keyCode）" +
                                                                if (injected) {
                                                                    "已注入(shell)"
                                                                } else {
                                                                    "已派发(AudioManager，可能被 ROM 忽略)"
                                                                }
                                                        ).format(consumerUsage),
                                                    )
                                                } else {
                                                    appendLog(
                                                        "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                            "（uuid=$uuid usage=0x%02x）无对应 Android 键，忽略"
                                                                .format(consumerUsage),
                                                    )
                                                }
                                            }
                                        } else if (keyMap != null && report != null) {
                                            // Consumer 设备的「无按键」/释放报文（值为 0 或下标越界）：
                                            // 静默计数，避免释放事件刷屏
                                            consumerReleaseCount++
                                        } else if (knobMap != null && report != null) {
                                            // 旋钮（iDrive knob / 方控滚轮）：按钮位是状态位——
                                            // 边沿检测后发键；滚轮是相对增量，非零即发滚动。
                                            val event = knobMap.parse(report)
                                            val prevBits = knobButtonStates[uuid] ?: 0
                                            val nowBits =
                                                (if (event.confirm) 1 else 0) or (if (event.back) 2 else 0)
                                            knobButtonStates[uuid] = nowBits
                                            val knobDisplay = VirtualLauncher.appDisplayId
                                            if (event.confirm && prevBits and 1 == 0) {
                                                // 桌面在场走进程内派发（虚拟屏不接受 Shizuku 键注入：
                                                // injectInputEvent 对 DPAD 也返回 false），否则回退注入
                                                val handled = KnobNav.press()
                                                val ok =
                                                    handled ||
                                                        (knobDisplay >= 0 &&
                                                            CarInputClient.service?.key(
                                                                knobDisplay,
                                                                KeyEvent.KEYCODE_DPAD_CENTER,
                                                            ) == true)
                                                appendLog(
                                                    "EVENT: 方控确认键 → " +
                                                        when {
                                                            handled -> "打开选中项"
                                                            ok -> "DPAD_CENTER 已注入"
                                                            else -> "注入失败"
                                                        },
                                                )
                                            }
                                            if (event.back && prevBits and 2 == 0) {
                                                val ok =
                                                    knobDisplay >= 0 &&
                                                        CarInputClient.service?.key(knobDisplay, KeyEvent.KEYCODE_BACK) == true
                                                appendLog("EVENT: 方控返回键 → BACK " + if (ok) "已注入" else "注入失败")
                                            }
                                            if (event.wheelDelta != 0) {
                                                // 旋钮旋转 → 桌面进程内派发（GridActivity 注册的
                                                // KnobNav）：光标在应用列表内 → 移动选中格；越过
                                                // 列表边界 → 左右翻页。方向约定：正增量=顺时针=下一项。
                                                //
                                                // 不能靠 Shizuku 注入：虚拟屏不接受 injectInputEvent
                                                // 的键/滚动事件（ACTION_SCROLL 与 DPAD_* 均返回 false，
                                                // 触摸正常），桌面在场时直接走进程内回调；桌面不在
                                                // （其他 App 前台）才回退方向键注入。
                                                val sign = if (event.wheelDelta > 0) 1 else -1
                                                // 一格一格走：相对滚轮一齿通常 ±1，连转也只按齿数发，
                                                // 上限 3 防一次性甩飞
                                                val steps = event.wheelDelta.let { if (it < 0) -it else it }
                                                    .coerceIn(1, 3)
                                                var consumed = 0
                                                while (consumed < steps && KnobNav.rotate(sign)) consumed++
                                                val dispatched = consumed > 0
                                                var svc = if (dispatched) null else CarInputClient.service
                                                if (!dispatched && svc == null) {
                                                    CarInputClient.ensure(context)
                                                    svc = CarInputClient.service
                                                }
                                                val keyCode =
                                                    if (sign > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                                                val ok =
                                                    dispatched ||
                                                        (
                                                            knobDisplay >= 0 &&
                                                                svc != null &&
                                                                (0 until steps).all {
                                                                    svc!!.key(knobDisplay, keyCode)
                                                                }
                                                        )
                                                if (knobScrollLogCount < 20) {
                                                    knobScrollLogCount++
                                                    appendLog(
                                                        "EVENT: 方控滚轮 delta=${event.wheelDelta} → " +
                                                            "${if (sign > 0) "NEXT" else "PREV"}×$steps " +
                                                            when {
                                                                dispatched -> "已派发桌面"
                                                                ok -> "方向键已注入"
                                                                else ->
                                                                    "注入失败（display=$knobDisplay，" +
                                                                        "注入服务=${if (svc != null) "在线" else "离线"}）"
                                                            },
                                                    )
                                                }
                                            }
                                        } else if (contacts != null) {
                                            if (touchLogCount <= 30 || touchLogCount % 50 == 0) {
                                                appendLog(
                                                    "EVENT: 触摸 " +
                                                        contacts.joinToString {
                                                            "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                                        },
                                                )
                                            }
                                            touchLogCount++
                                            contacts.forEach {
                                                TouchRouter.onContact(
                                                    context.applicationContext,
                                                    it,
                                                )
                                            }
                                        } else if (report != null) {
                                            appendLog(
                                                "EVENT: 其他 HID 报告 uuid=$uuid bytes=${report.size}" +
                                                    " hex=${report.joinToString(" ") { "%02x".format(it) }}",
                                            )
                                        }
                                    }

                                    "iAPSendMessage" -> {
                                        // 事件通道上的 iAP2 虚拟链路字节（车机先发 marker 开场）。
                                        // 喂给链路引擎：握手 → EA 会话 → 触摸 HID。
                                        val params = command["params"] as? Map<*, *>
                                        val data = params?.get("data") as? ByteArray
                                        if (data == null) {
                                            appendLog("EVENT: iAPSendMessage 无 data（params=$params）")
                                        } else {
                                            iapEventMessageCount++
                                            client.onIapEventBytes(data)
                                        }
                                    }

                                    "forceKeyFrame" -> {
                                        // 车机解不出画面时会要 I 帧。必须立即响应：
                                        // 拖到下一个周期 I 帧（1s）车机可能一直卡在等帧状态。
                                        val forced = videoSender?.requestKeyFrame() == true
                                        appendLog(
                                            "EVENT: 车机请求关键帧（forceKeyFrame）→ " +
                                                if (forced) "已强制编码 I 帧" else "编码器未就绪，等周期 I 帧",
                                        )
                                    }

                                    "changeModes" -> {
                                        // 车机请求资源仲裁（如用户在 MMI 上点了手机）：
                                        // 回 modesChanged 声明手机持有主屏/主音频
                                        appendLog("EVENT: 车机请求 changeModes → 回 modesChanged 接管资源")
                                        client.assertModes()
                                    }

                                    "setNightMode" -> {
                                        // 车机昼夜信号（/command type=setNightMode params={nightMode:Boolean}）。
                                        // 交给 ThemePrefs：自动档下它优先于手机系统深色（显式白天/黑夜仍优先）。
                                        // 事件通道回调在读取线程：AppCompat 的全局 night mode 只能主线程改。
                                        val raw = (command["params"] as? Map<*, *>)?.get("nightMode")
                                        val night =
                                            when (raw) {
                                                is Boolean -> raw
                                                null -> null
                                                else -> raw.toString().toBooleanStrictOrNull()
                                            }
                                        if (night == null) {
                                            appendLog(
                                                "EVENT: 车机昼夜信号 setNightMode 缺少 nightMode（params=${command["params"]}，忽略）",
                                            )
                                        } else {
                                            appContext?.let { ctx ->
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    ThemePrefs.setCarNightSignal(ctx, night)
                                                }
                                            }
                                            appendLog(
                                                "EVENT: 车机昼夜信号 nightMode=$night → 外观主题切到" +
                                                    if (night) "黑夜" else "白天",
                                            )
                                        }
                                    }

                                    "requestSiri" -> {
                                        // 方控语音键（2026-10-23 BMW 实测：siriAction=1 按下、3 抬起）。
                                        // 按下即触发本机语音助手。语音助手是系统 UI，注到主屏(display 0)。
                                        val siriAction =
                                            (command["params"] as? Map<*, *>)
                                                ?.get("siriAction")?.toString()?.toIntOrNull()
                                        if (siriAction == 1) {
                                            val ok =
                                                CarInputClient.service?.key(0, KeyEvent.KEYCODE_VOICE_ASSIST) == true
                                            appendLog("EVENT: 方控语音键 → VOICE_ASSIST " + if (ok) "已注入" else "注入失败")
                                        } else {
                                            appendLog("EVENT: requestSiri siriAction=$siriAction（抬起/其他，忽略）")
                                        }
                                    }

                                    else ->
                                        appendLog(
                                            // 未识别的命令必须带参数：车机的私有命令（如 requestUI）
                                            // 到底要什么，全在 params 里——只打 type 等于没打。
                                            "EVENT: 命令 type=${command["type"]} " +
                                                command.entries
                                                    .filter { it.key?.toString() != "type" }
                                                    .joinToString(" ") { (key, value) ->
                                                        "$key=" +
                                                            when (value) {
                                                                is ByteArray -> value.joinToString("") { "%02x".format(it) }
                                                                else -> value.toString().take(240)
                                                            }
                                                    }.take(600),
                                        )
                                }
                            }
                            // 本次真会推音频才占用主音频资源；否则只声明主屏
                            client.claimsMainAudio = audioWillStreamNow(client, sessionKey)
                            // 资源声明（must）：真车对未声明资源的屏幕流 SETUP 直接 400
                            client.assertModes()
                            // HID 输入模式武装：/info hidDevices（奥迪字符串 "0"/"1"，触摸="1"）。
                            // display features=0x08（高保真触摸）已声明，缺这步车机不发 hidSendReport。
                            setup.hidDeviceUuids.forEach { uuid ->
                                client.sendHidSetInputMode(uuid, mode = 0)
                            }
                            val screen = client.runScreenStream(sessionKey, setup.displayUuid)
                            if (screen != null) {
                                // 地址取 client 真正连通的那个（配对期间可能被 mDNS 刷新过）：
                                // 视频数据端口与 keepAlive 都按它建 socket；IPv6 端点也算
                                // 可用地址（EndpointHosts 统一排序：IPv4 → 全局 IPv6 → 链路本地 IPv6）。
                                val host = client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                                // 心跳双保险（缺失任一都会被车机 ~10s 看门狗拆会话，2026-10-11 实测）：
                                //   keepAlive UDP ping（车机 SETUP 返回的 keepAlivePort，10s 周期）
                                //   + RTSP POST /feedback（1s 周期，catplay FEEDBACK_INTERVAL）
                                if (host != null && setup.keepAlivePort != null) {
                                    client.startKeepAlive(host, setup.keepAlivePort!!)
                                }
                                client.startFeedbackLoop()
                                // iAP 隧道（type-130）：车机→手机的 iAP2 EA 数据（触摸 HID 载体）。
                                // 建立失败不阻塞会话（触摸不可用但视频照常）。
                                val tunnel = client.runIapTunnel(sessionKey)
                                tunnel?.onIapMessage = { frame ->
                                    // 触摸证据采集：先全量记录，格式确认后接 TouchRouter
                                    if (frame.messageId != 0x0000) {
                                        val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                                        appendLog(
                                            "TUNNEL: rx 0x${frame.messageId.toString(16)} " +
                                                "(${Iap2MessageIds.nameOf(
                                                    frame.messageId,
                                                )}) ${frame.body.size}B" +
                                                if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                        )
                                    }
                                }
                                // 关键时序（2026-10-17 奥迪定谳）：屏幕 SETUP 后必须尽快连上
                                // dataPort——车机有 ~2s 看门狗。音频 SETUP 实测要 ~2.1s，
                                // 若排在前面，端口连接迟到即触发车机 reset 控制通道
                                // （日志特征：`音频 SETUP 往返 2091ms` 与 `EVENT 读取结束
                                // - Connection reset` 同一毫秒）。故先建 VideoSender 并
                                // openDataPort()，再协商音频，最后 RECORD + 启动编码器。
                                var pendingVideoW = 0
                                var pendingVideoH = 0
                                var pendingVideoDensity = 0
                                if (host != null) {
                                    // 「显示」偏好：流画布/虚拟屏按车机分辨率百分比生成。
                                    // 只在会话建立时生效——编码器与虚拟屏尺寸此时定型；
                                    // 密度同步缩放，逻辑布局（dp）与 100% 完全一致。
                                    val scalePct =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .scalePercent(it)
                                            } ?: 100
                                    val scale = scalePct / 100f
                                    // 缩放后向下对齐偶数（H.264 要求）：虚拟屏/合成器/编码器
                                    // 三方必须同一尺寸，故在源头对齐（VideoSender 内另有兜底）
                                    val streamW =
                                        ((setup.displayWidth * scale).toInt().coerceAtLeast(320) / 2) * 2
                                    val streamH =
                                        ((setup.displayHeight * scale).toInt().coerceAtLeast(180) / 2) * 2
                                    val prefFps =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .fps(it)
                                            } ?: 30
                                    // /info displays[0].maxFPS 是车机显示能力的上限
                                    // （BMW 82772 实测 60）。用户偏好选到 120 时编码器与
                                    // 合成器仍按 120 出帧，车机刷新跟不上——白耗带宽与
                                    // 编码器算力，链路上大量无效帧。按接收端上限夹紧。
                                    val maxFps = setup.maxFps
                                    carMaxFps = if (maxFps in 24..240) maxFps else 0
                                    val fps =
                                        if (maxFps in 24..240) prefFps.coerceAtMost(maxFps) else prefFps
                                    if (fps != prefFps) {
                                        appendLog("DISPLAY: 车机 maxFPS=$maxFps，帧率 $prefFps → $fps")
                                    }
                                    activeScalePercent = scalePct
                                    activeTargetFps = fps
                                    // 车机触摸在通告视图坐标系（如 1160×540），流已按比例
                                    // 缩放（如 1450×674）——注入前需映射，否则点击漂移
                                    TouchRouter
                                        .setCoordinateScale(
                                            setup.displayWidth,
                                            setup.displayHeight,
                                            streamW,
                                            streamH,
                                        )
                                    appendLog(
                                        "DISPLAY: 流 ${setup.displayWidth}x${setup.displayHeight} → " +
                                            "${streamW}x$streamH（$scalePct%）@${fps}fps",
                                    )
                                    pendingVideoW = streamW
                                    pendingVideoH = streamH
                                    // dock 屏密度随画布短边等比（设计基准 1160×540@420dpi，
                                    // 见 design/launcher-proto.html：dock 48dp=126px）：高分辨率
                                    // 车机（测试台 2320×1080=2×）下 dock 文字/图标与奥迪同比例
                                    // 放大——此前固定 420dpi，dock 相对画布减半显小（2026-10-17）。
                                    // 上限 4×（4K 级画布）。
                                    pendingVideoDensity =
                                        (420f * minOf(streamW, streamH) / 540f)
                                            .toInt()
                                            .coerceIn(120, 1680)
                                    videoSender =
                                        VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            frameCipher = screen.cipher,
                                            width = streamW,
                                            height = streamH,
                                            targetFps = fps,
                                            log = { line -> appendLog(line) },
                                            keepAliveWithStats = setup.keepAliveSendStatsAsBody,
                                            onEnded = {
                                                // 会话真实结束：解除静默降噪（客户端恢复探测/重连）
                                                appendLog(">>> CarPlay 会话结束（推流终止）——恢复蓝牙探测")
                                                BtSessionGate.sessionActive = false
                                                // 车机掉线类结束（无用户断开操作）：暗幕/常亮必须一起收掉。
                                                // 这条路径不经过 retireSession（VirtualLauncher 仍挂着），
                                                // 漏掉它就会出现「会话早就断了、45s 后手机屏突然变黑」
                                                // （2026-10-19 用户实测）。
                                                ScreenDimmer.setSessionActive(false)
                                                // 同样复原横屏覆盖。这里跑在推流收尾线程，不是连接
                                                // 关键路径，但统一走异步口以保持「串行、不重入」的语义。
                                                appContext?.let { ctx ->
                                                    AppOrientationCompat.resetAllAsync(ctx, ::appendLog)
                                                }
                                                // 音频拆链归因：起过音频却很快被拆 → 记下该档位
                                                // 不兼容，下次连接自动换档（见 attributeAudioTeardown）。
                                                val peerDrop = videoSender?.stoppedByPeer == true
                                                attributeAudioTeardown(stoppedByPeer = peerDrop)
                                                // 对端/网络打断：车机侧会话要看到一次「干净收尾」才会重新
                                                // 认人（详见 scheduleAbnormalRearm）。用户主动断开不走这里。
                                                if (peerDrop) scheduleAbnormalRearm("推流被对端/网络中止")
                                                // 推流一结束就核验车机热点是否仍被系统承认：EMUI 会在
                                                // 无互联网的应用专属网络上 ~70s 后静默切回仅扫描，
                                                // onLost 不回调、boundNetwork 残留，下一轮车机回连时
                                                // 只打印「网络已在位」而 socket 全部 ENONET
                                                // （2026-10-25 12:49:02 实测）。主动释放绑定，
                                                // 保证下一轮真正重新加入热点而不是复用死引用。
                                                Bg.run("wifi-health-after-session") {
                                                    if (wifiConnector?.ensureNetworkAlive() == false) {
                                                        boundCarSsid = null
                                                        appendLog(
                                                            ">>> 推流结束后车机热点已不可用" +
                                                                "——已释放网络绑定，下轮重新加入",
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    // 先连端口（车机看门狗），编码器等 RECORD 后再启动
                                    videoSender?.openDataPort()
                                }
                                // 先 RECORD 激活会话并起视频，再协商音频（2026-10-17 奥迪定谳）：
                                // 车机在「屏幕流 SETUP 之后」有约 2s 看门狗，等不到 RECORD/数据流即
                                // 拆掉整轮会话。音频 SETUP 实测本身要 ~2.05-2.16s（车机侧音频通路
                                // 初始化），排在 RECORD 之前必然超时——日志里 reset 与音频 SETUP
                                // 返回同一毫秒、且 5 次实测全部复现。故顺序改为：
                                //   屏幕 SETUP → 连数据端口 → RECORD → 起编码器（出帧）→ 音频 SETUP
                                // 音频 SETUP 失败只丢声音，视频会话保持。
                                client.record()
                                // 会话建立成功：重置退避阶梯，下次异常断链从头开始
                                residentBackoff.reset()
                                appendLog("LINK: 会话建立——退避阶梯已重置")
                                if (host != null) {
                                    val videoW = pendingVideoW
                                    val videoH = pendingVideoH
                                    val videoDensity = pendingVideoDensity
                                    videoSender?.start { surface ->
                                        // 唯一模式：隐形屏（双屏启动器：dock + 应用屏）
                                        val source =
                                            HiddenDisplaySource(
                                                context.applicationContext,
                                                log = { line -> appendLog(line) },
                                            )
                                        currentHiddenSource = source
                                        val ok =
                                            source.attach(
                                                surface,
                                                videoW,
                                                videoH,
                                                videoDensity,
                                            )
                                        appendLog(
                                            "MODE: 隐形屏 ${if (ok) "已启动（高德车机版优先）display=${source.displayId}" else "启动失败（Shizuku 就绪？）"}",
                                        )
                                        // 合成器在 attach 时按用户偏好建（可能高过车机 maxFPS），
                                        // 这里统一到本会话夹紧后的帧率（activeTargetFps），
                                        // 避免编码器 60fps 元数据却喂 120fps 帧。
                                        if (ok) VirtualLauncher.setStreamFps(activeTargetFps)
                                        // 暗幕/常亮只在真正开始投屏后生效（见 ScreenDimmer.sessionActive）：
                                        // 应用启动时只挂触摸探针，避免「会话早断了、手机过一会儿还是变暗」。
                                        if (ok) ScreenDimmer.setSessionActive(true)
                                    }
                                }
                                // 音频通道（可选）：SETUP type=100 → UDP RTP 推流。
                                // 会话已激活（RECORD 完成）后再协商；失败不影响视频主链路。
                                val audioStarted = startAudioChannel(context, client, sessionKey, host)
                                if (audioStarted) {
                                    // 流在 RECORD 之后加入：AirPlay 语义要求再发一次 RECORD
                                    // 把新流纳入已激活的会话。缺这一步时车机对 SETUP 回 200
                                    // 并给 dataPort，但不会真正播放（2026-10-17 奥迪实测：
                                    // 音频包持续发送、采集内容非零，车机无声）。
                                    appendLog("AUDIO: 音频流已加入——补发 RECORD 激活（流后加入会话）")
                                    client.record()
                                }
                            }
                        } else {
                            // 会话级 SETUP 被拒（典型：接收端按设备过滤回 403，见
                            // AirPlayClient.runSetup 的 deviceID 说明）。主动关闭控制通道：
                            // 否则接收端要等约 45s 视频超时才拆 BT 链路，用户只看到
                            // 「连上之后自动断开」，无从判断是会话建立失败。
                            phaseHint = "接收端拒绝了会话建立（SETUP 未通过）"
                            appendLog("AIRPLAY: 会话建立失败——主动关闭控制通道，本轮结束")
                            uiHintSink?.invoke("接收端拒绝了会话建立（SETUP）——请查看日志中的 deviceID 提示")
                            runCatching { client.close() }
                        }
                    }
                } catch (error: Throwable) {
                    // 配对线程任何异常都不能留下「已有会话在处理中」的卡死状态
                    appendLog("AIRPLAY: 配对线程异常 - ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    sessionStarting.set(false)
                    // 配对未成功建立推流（fail/异常/start 失败提前退出）时解除静默降噪，
                    // 否则 BT-C 会永久停在门闸外、连真车路径失效。
                    // 推流活跃时保持关闭，由 VideoSender.onEnded（推流终止）负责解除。
                    if (videoSender?.isRunning != true) {
                        BtSessionGate.sessionActive = false
                        // 收尾竞态：晚到 0x4301 若恰好在「配对已失败、互斥尚未释放」的
                        // 窗口内到达，会被暂存但没人消费。此处补投一次——车机等 15s
                        // 无人连入就拆链，错过这一投就是整轮失败（2026-10-18 分析）。
                        val pending = pendingEndpointUpdate
                        if (pending != null && !connectCancelled) {
                            pendingEndpointUpdate = null
                            appendLog(">>> 收尾时发现暂存的晚到 0x4301——立即按新端点重走一轮")
                            Bg.run("late-endpoint-retry") {
                                onCarPlayEndpoint(pending, "0x4301 晚到（本轮收尾补投）")
                            }
                        }
                    }
                }
            }
        }
        // 已绑定过网络（autoJoinCarWifi 刚绑好并重注册过）就不要再走一遍加入流程：
        // WifiCarPlayConnector 内部先 release() → bindProcessToNetwork(null)，
        // 会把刚注册好的 ctrl mDNS 广播打断，再等系统重建绑定。
        val wifiConfig =
            Iap2SenderSession.WifiConfiguration(
                endpoint.ssid,
                endpoint.passphrase,
                endpoint.channel,
                endpoint.securityType,
            )
        if (boundCarSsid == endpoint.ssid && wifiConnector?.isCurrentNetworkAlive() == true) {
            appendLog(">>> [$source] 已绑定车机热点 ${endpoint.ssid}，跳过重复绑定")
            continueWithNetwork(true)
        } else {
            // 端点先于 0x5703 到达（或上一轮换网失败）：同样走应用专属网络。
            // 不能再用「按 TRANSPORT_WIFI 盲绑当前任意 WiFi」——切网途中会绑到旧网络，
            // AirPlay 必然连不上，且会把无互联网热点留在系统默认网络里。
            Bg.run("wifi-join-on-endpoint") {
                while (!connectCancelled) {
                    val shellReady =
                        appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
                    when (val result = joinCarWifiSpecifier(wifiConfig, shellReady)) {
                        is WifiCarPlayConnector.JoinResult.Joined -> {
                            continueWithNetwork(true)
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unsupported -> {
                            // WEP 等无法用 specifier 表达：等用户在系统 WLAN 里连上后绑定
                            waitForManualWifiThenContinue(wifiConfig) { bound ->
                                continueWithNetwork(bound)
                            }
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unavailable -> {
                            promptSpecifierFailed(wifiConfig, shellReady)
                            // promptSpecifierFailed 内部可能已重试/转手动；两者都会
                            // 通过 joinCarWifiSpecifier → continueWithNetwork 续上流程，
                            // 这里只在彻底失败时结束本轮
                            if (boundCarSsid == wifiConfig.ssid &&
                                wifiConnector?.isCurrentNetworkAlive() == true
                            ) {
                                continueWithNetwork(true)
                            } else {
                                sessionStarting.set(false)
                            }
                            return@run
                        }
                    }
                }
                sessionStarting.set(false)
            }
        }
    }

    /** specifier 不可用时的手动兜底：轮询系统 WLAN 是否连上目标热点，而后绑定。 */
    private fun waitForManualWifiThenContinue(
        wifi: Iap2SenderSession.WifiConfiguration,
        onBound: (Boolean) -> Unit,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + CAR_WIFI_BIND_WAIT_MS
        var lastHintAt = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            val current = shellWifiStatus().second
            if (current == wifi.ssid) {
                onBound(bindCarWifiAndRegister(wifi.ssid))
                return
            }
            if (android.os.SystemClock.uptimeMillis() - lastHintAt > 5_000) {
                lastHintAt = android.os.SystemClock.uptimeMillis()
                phaseHint = "等待手机加入车机热点 ${wifi.ssid}…"
                appendLog("WIFI: 等待用户在系统 WLAN 中加入车机热点（当前 ${current ?: "未连接"}）…")
                openWlanSettings()
            }
            Thread.sleep(1_000)
        }
        if (!connectCancelled) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(">>> 等待车机热点超时——请到 设置>WLAN 加入 \"${wifi.ssid}\" 后重新连接")
            uiHintSink?.invoke("未连上车机热点 \"${wifi.ssid}\"——请到 WLAN 设置中主动连接")
        }
        onBound(false)
    }

    /** 触摸/隧道事件低频计数（迁移自 MainActivity 字段，保持日志节流行为一致）。 */
    @Volatile
    private var touchLogCount = 0

    /** 车机媒体键「释放」报文计数（值为 0 的 1 字节报告，静默丢弃，仅计数备查）。 */
    @Volatile
    private var consumerReleaseCount = 0

    /** 旋钮按钮位状态（uuid → bit0 确认/bit1 返回）：状态位报告的边沿检测用。 */
    private val knobButtonStates = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 旋钮滚动日志节流（前 20 条全打，之后静默——滚动报告频率高）。 */
    @Volatile
    private var knobScrollLogCount = 0

    @Volatile
    private var iapEventMessageCount = 0
}
