package io.github.shaonialife.cardeck.bt

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.shaonialife.cardeck.SessionController

/**
 * 蓝牙 ACL 事件接收器（对照 CarDroid CarAutoConnectReceiver）：
 * 监听 `ACTION_ACL_CONNECTED`，当已配对车机物理连接蓝牙时唤醒常驻连接。
 *
 * 场景：用户上车点火，车机蓝牙模块上电后主动连手机（ACL），此时 App 若未在前台，
 * 本接收器触发 resident 模式启动连接流程——无需用户拿出手机点按钮。
 *
 * 仅在常驻模式开启时生效。
 */
class CarAutoConnectReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device =
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                ?: return
        // 仅当常驻模式开启时才自动启动
        if (!SessionController.shouldAutoStart(context)) return
        // 已选定目标时只响应目标设备；未选定时任一已配对设备都触发
        val target = TargetDevicePrefs.get(context)
        if (target != null && !TargetDevicePrefs.matches(target, device.address)) return
        Log.i(TAG, "ACL_CONNECTED: ${device.name} (${device.address})——唤醒常驻连接")
        SessionController.appendLog("RESIDENT: 检测到车机蓝牙连接（${device.name}），自动启动")
        SessionController.startResident(context)
    }

    companion object {
        private const val TAG = "CarAutoConnect"
    }
}
