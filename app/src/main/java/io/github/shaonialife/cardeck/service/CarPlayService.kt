package io.github.shaonialife.cardeck.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.shaonialife.cardeck.MainActivity
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * 前台保活服务：连接建立期间以 connectedDevice 类型常驻，
 * 防止 app 退到后台后被系统/MagicOS 省电策略杀死导致断连。
 * 连接逻辑仍运行在 MainActivity 派生的线程里——本服务负责保住进程。
 */
class CarPlayService : Service() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 息屏保活：CPU 唤醒锁维持编码/协议线程与 TCP 存活探测的响应；
     * WiFi 低延迟锁阻止无线进入激进省电（否则接收端 20s 内判死拆链）。
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock =
            power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CarPlaySender:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock =
            wifi
                .createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "CarPlaySender:wifi",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // 前台类型必须一次定对，不能「先报 connectedDevice 再 upgrade」：
        // 每次「启动连接」都会 startService 进来，若这里只报 connectedDevice，
        // 服务先前持有的 mediaProjection 前台类型会被摘掉，系统随即停止投影（onStop）。
        // 2026-10-21 真车日志正是这个时序：06:28:32.216 选「开启声音」→ .272 就
        // `MediaProjection 被系统停止`，于是音频拿不到投影、UI 从「已授权」翻回
        // 「未授权」，每次都得手动重新授权。
        // 但也不能无条件带 mediaProjection：Android 14 上该类型要求 AppOps
        // PROJECT_MEDIA（用户当次授权才有效），授权过期/进程重启后会 SecurityException
        // （2026-10-09 踩坑）。所以按「当前是否持有/待创建投影」决定。
        val needsProjection = pendingProjectionResult != null || mediaProjection != null
        val types =
            if (needsProjection) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        try {
            androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } catch (error: Exception) {
            // 带 mediaProjection 类型被拒（授权已失效）：退回仅 connectedDevice，待重新授权
            fileLog("SVC: startForeground(types=$types) 失败 - ${error.message}，退回仅 connectedDevice")
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
        acquireLocks()
        // 授权结果在（用户刚确认投屏）时创建投影
        if (pendingProjectionResult != null) takeProjection()
        return START_STICKY
    }

    private fun takeProjection() {
        val (code, data) = pendingProjectionResult ?: return
        pendingProjectionResult = null
        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = manager.getMediaProjection(code, data)
            // Android 14+：createVirtualDisplay 前必须注册回调（资源管理强制）
            projection?.registerCallback(
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        mediaProjection = null
                        // 关键诊断：授权丢失会导致音频/镜像不可用（此前只写 logcat，HKS 加密看不到）
                        fileLog("SVC: MediaProjection 被系统停止（onStop）——音频/镜像需重新授权")
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            mediaProjection = projection
            if (projection != null) {
                fileLog("SVC: MediaProjection 就绪（投影创建成功）")
            } else {
                fileLog("SVC: getMediaProjection 返回 null（授权结果已失效？）")
            }
        } catch (error: Exception) {
            fileLog("SVC: getMediaProjection 失败 - ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun onDestroy() {
        releaseLocks()
        // Android 14+：MediaProjection 与前台的 mediaProjection 类型绑定——
        // 保活服务停止（如用户点「断开连接」）时系统会连带停止投影。
        fileLog(
            "SVC: onDestroy（保活服务停止）mediaProjection=" +
                (if (mediaProjection != null) "仍在（即将被系统回收）" else "null"),
        )
        super.onDestroy()
    }

    /** 投影生命周期等关键事件：logcat + 文件双写（HKS 环境下文件是唯一可读通道）。 */
    private fun fileLog(message: String) {
        android.util.Log.i("CarPlayService", message)
        DebugFileLog
            .append(this, message)
    }

    companion object {
        private const val CHANNEL_ID = "carplay_connection"
        private const val NOTIFICATION_ID = 1

        /** 镜像模式：授权后由 MainActivity 写入；服务重声明 FGS 类型后方可创建虚拟屏。 */
        @Volatile
        var mediaProjection: android.media.projection.MediaProjection? = null

        /** 授权结果暂存：由服务在前台类型升级后调用 getMediaProjection（Android 14+ 顺序要求）。 */
        @Volatile
        var pendingProjectionResult: Pair<Int, Intent>? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarPlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarPlayService::class.java))
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "CarDeck 连接",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持与车机的连接"
                    setShowBadge(false)
                },
            )
        }
        // 点击通知 → 打开 App 主界面（此前缺 setContentIntent，点击无反应）
        val contentIntent =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CarDeck 运行中")
            .setContentText("正在保持与车机的连接，点开应用可查看日志")
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
