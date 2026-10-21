package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restart on real headset connections, including when the idle service/process is gone. */
class BootReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED || !KeepAlive.enabled(c)) {
            AliveReceiver.cancel(c)
        }
        if (!KeepAlive.enabled(c)) return
        try {
            val start = Intent(c, HeadsetDetectService::class.java)
            when (i.action) {
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    DeviceMatcher.loadPersisted(c)
                    val device = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val remoteName = try { device?.name } catch (_: SecurityException) { null }
                    val name = if (i.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                        val address = if (remoteName == null) {
                            try { device?.address } catch (_: SecurityException) { null }
                        } else null
                        resolveAclName(remoteName, address) { target -> bondedName(target) }
                            ?: i.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    } else {
                        remoteName ?: i.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    }
                    if (!acceptsConnection(i.action, i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1), name)) {
                        return
                    }
                    start.action = HeadsetDetectService.ACTION_CONNECTION
                }
                ACTION_BT_EVENT -> {
                    val event = i.getStringExtra("evt")
                    if (event != "connected" && event != "disconnected") return
                    if (event == "disconnected" && !HeadsetDetectService.RUNNING) return
                    start.putExtra("evt", event)
                    start.action = ACTION_BT_EVENT
                }
                else -> return
            }
            KeepAlive.wake(c)
            c.startService(start)
            Log.i(TAG, "service requested: ${i.action}")
        } catch (e: Exception) {
            // Background-start restrictions still apply; never bypass them with root or wake alarms.
            Log.e(TAG, "start failed: $e")
        }
    }

    companion object {
        private const val TAG = "MoondropHeadset"
        const val ACTION_BT_EVENT = "com.fxxkmoondrop.secret.BT_EVENT"

        internal fun resolveAclName(
            deviceName: String?, address: String?, bondedName: (String) -> String?,
        ): String? {
            if (deviceName != null) return deviceName
            if (address == null) return null
            return try { bondedName(address) } catch (_: SecurityException) { null }
        }

        @Suppress("DEPRECATION")
        private fun bondedName(address: String): String? =
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull { it.address == address }?.name

        internal fun acceptsConnection(action: String?, state: Int, name: String?): Boolean =
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> DeviceMatcher.isMoondrop(name)
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED ->
                    state == BluetoothProfile.STATE_CONNECTED && DeviceMatcher.allowProbe(name)
                else -> false
            }
    }
}
