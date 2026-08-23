package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

@Suppress("DEPRECATION")
class HeadsetReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (i == null || i.action == null) return
        val action = i.action
        Log.i(TAG, "BR: $action")
        // alpha1.21: 静态 receiver 可能拉起进程（应用未运行时），确保广播通道 Context 可用
        AncBridge.bind(c)

        // alpha1.15: FastPair 弹窗里的三模式按钮回调
        if (ACTION_FP_MODE == action) {
            val mode = i.getIntExtra(EXTRA_FP_MODE, -1)
            if (mode in 0..2) {
                AncBridge.setAncMode(mode)
                Log.i(TAG, "fastpair mode change -> $mode")
            }
            return
        }

        // alpha1.20: GMS 弹窗请求当前降噪模式 -> 回发 MODE_STATE（驱动弹窗按钮高亮）
        if (AncBridge.ACTION_FP_MODE_REQUEST == action) {
            AncBridge.sendModeState()
            Log.i(TAG, "fastpair mode request -> answered")
            return
        }

        // alpha2.7: GMS 连接弹窗关闭 -> 若处于模拟连接状态则自动恢复（真实连接不受影响）
        if (ACTION_FP_SHEET_CLOSED == action) {
            if (GaiaBleClient.isSimConnected()) {
                GaiaBleClient.setSimConnected(false)
                BatteryStore.clearGaia("AA:BB:CC:DD:EE:FF")
                PopupGate.clear("AA:BB:CC:DD:EE:FF", "Moondrop Golden Ages 2")
                // alpha2.4: 通知主界面刷新（电量行/GAIA/耳机连接同步复原）
                try {
                    val bi = Intent("com.fxxkmoondrop.secret.STATE_UPDATED")
                    bi.setPackage("com.fxxkmoondrop.secret")
                    c.sendBroadcast(bi)
                } catch (_: Exception) { }
                Log.i(TAG, "sim state restored after popup closed")
            }
            return
        }

        val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        var name = dev?.name
        if (name == null) name = i.getStringExtra("android.bluetooth.device.extra.NAME")
        var address = dev?.address
        if (address == null) address = i.getStringExtra("android.bluetooth.device.extra.ADDRESS")

        // 电量广播：与系统蓝牙设置同源（ACTION_BATTERY_LEVEL_CHANGED）
        if ("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" == action) {
            val level = i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
            if (address != null && level >= 0) {
                BatteryStore.set(address, level)
                Log.i(TAG, "battery $name ($address) = $level%")
            }
            return
        }

        var connected = false
        var disconnected = false

        when {
            BluetoothDevice.ACTION_ACL_CONNECTED == action -> connected = true
            BluetoothDevice.ACTION_ACL_DISCONNECTED == action -> disconnected = true
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED == action ||
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED == action -> {
                val state = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                connected = state == BluetoothProfile.STATE_CONNECTED
                disconnected = state == BluetoothProfile.STATE_DISCONNECTED
            }
            else -> return
        }

        if (name == null) name = "蓝牙设备"
        Log.i(TAG, "device: $name ($address) connected=$connected disconnected=$disconnected")

        if (connected && name.lowercase().contains("moondrop")) {
            PopupGate.tryShowConnectedDeferred(c, address, name)
        } else if (disconnected) {
            if (name.lowercase().contains("moondrop")) {
                // alpha1.40: 系统层断开（ACL/A2DP/HFP）立即清 HeadsetGate MAC 缓存，
                // 主界面降噪面板/模拟区随真实状态刷新（GATT 断开由 GaiaBleClient 负责）
                HeadsetGate.clearConnectedMac(c)
                // 断开标记保留在 PopupGate 里防重复弹窗（连接时会自动清除）
                PopupGate.tryShowDisconnected(c, address, name)
            }
        }
    }

    companion object {
        private const val TAG = "MoondropHeadset"

        // alpha1.15: FastPair 弹窗三模式按钮 -> GAIA 降噪控制
        private const val ACTION_FP_MODE = "com.fxxkmoondrop.secret.FASTPAIR_MODE_CHANGED"
        private const val ACTION_FP_SHEET_CLOSED = "com.fxxkmoondrop.secret.FASTPAIR_SHEET_CLOSED"
        private const val EXTRA_FP_MODE = "mode"
    }
}
