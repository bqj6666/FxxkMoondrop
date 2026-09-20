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
            if (mode in 0..5) {
                AncBridge.setAncMode(mode)
                Log.i(TAG, "fastpair mode change -> $mode")
            }
            return
        }

        // alpha1.20: GMS 弹窗请求当前降噪模式 -> 回发 MODE_STATE（驱动弹窗按钮高亮）
        // alpha2.22: 一并回发 ANC 能力状态，驱动弹窗降噪按钮三态
        if (AncBridge.ACTION_FP_MODE_REQUEST == action) {
            AncBridge.sendModeState()
            AncBridge.sendAncStatus(GaiaBleClient.getInstance().ancCapabilityStatus())
            Log.i(TAG, "fastpair mode request -> answered")
            return
        }

        // alpha2.7: GMS 连接弹窗关闭 -> 若处于模拟连接状态则自动恢复（真实连接不受影响）
        if (ACTION_FP_SHEET_CLOSED == action) {
            // alpha2.41.5: 弹窗关闭后取消排队中的连接弹窗——用户已关闭弹窗，
            // 之后 GAIA 就绪/超时不再触发第二次弹窗（用户可选等待弹窗变可控制，或关闭后在 App 操作）。
            // alpha2.41.6: 用户关闭弹窗后登记设备，本连接断开前不再自动重弹（防止 GAIA 就绪后二次弹窗）
            val closedName = i.getStringExtra("device_name")
            val closedAddr = i.getStringExtra("android.bluetooth.device.extra.ADDRESS")
            PopupGate.markUserClosed(closedAddr, closedName)
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
                // alpha2.27: 同步刷新 GMS 弹窗电量显示
                try {
                    val bi = Intent(com.fxxkmoondrop.secret.hook.FastPairHookEntry.ACTION_BATTERY_UPDATE)
                    bi.putExtra("sys", level)
                    bi.setPackage(com.fxxkmoondrop.secret.hook.FastPairHookEntry.PKG_GMS)
                    c.sendBroadcast(bi)
                } catch (_: Throwable) { }
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

        if (connected && DeviceMatcher.isMoondrop(name)) {
            PopupGate.tryShowConnectedDeferred(c, address, name)
            // 3.2.5: 系统广播已经把「耳机连上了」送到本进程，直接唤醒服务做一次增量轮询。
            // 此前连接只靠 5 秒轮询周期发现；GMS 那条 am broadcast 链需要 root，一旦不可用
            // 就只能干等整个轮询周期，用户感知就是「连上半天不出电量」。
            wakeDetectService(c)
        } else if (disconnected) {
            if (DeviceMatcher.isMoondrop(name)) {
                // alpha1.40: 系统层断开（ACL/A2DP/HFP）立即清 HeadsetGate MAC 缓存，
                // 主界面降噪面板/模拟区随真实状态刷新（GATT 断开由 GaiaBleClient 负责）
                HeadsetGate.clearConnectedMac(c)
                // 断开标记保留在 PopupGate 里防重复弹窗（连接时会自动清除）
                PopupGate.tryShowDisconnected(c, address, name)
            }
        }
    }

    /** 最近一次唤醒服务的时间戳：连接过程中 ACL / A2DP / HFP 会连发几个广播，
     *  1 秒内只唤醒一次即可（pollConnected 本身幂等，这里只是省掉重复开销）。 */
    @Volatile private var sLastWakeMs = 0L

    /**
     * 立即唤醒检测服务做一次增量轮询（复用既有的 BT_EVENT 通道，不新增机制）。
     *
     * 服务没在跑时后台 startService 会受限，所以整段 try 住 —— 这种情况本来就有
     * 轮询兜底，不影响功能。
     */
    private fun wakeDetectService(c: Context) {
        val now = System.currentTimeMillis()
        if (now - sLastWakeMs < 1000L) return
        sLastWakeMs = now
        try {
            c.startService(Intent(c, HeadsetDetectService::class.java)
                    .setAction(BootReceiver.ACTION_BT_EVENT)
                    .putExtra("evt", "connected"))
            Log.d(TAG, "wake detect service (connected event)")
        } catch (t: Throwable) {
            Log.d(TAG, "wake detect service failed: " + t)
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
