package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the provider's event target, not a UI or a live Bluetooth stack. */
class MonitoringOffPopupTest {
    @Test
    fun `default GAIA wait does not delay popup or reconnect when monitoring is off`() {
        checkConnections(popupEnabled = true, expectedPopups = 2)
    }

    @Test
    fun `explicitly disabling GAIA wait still shows a prompt popup`() {
        checkConnections(popupEnabled = true, expectedPopups = 2, waitGaia = false)
    }

    @Test
    fun `forwarded connection events still respect the popup off switch`() {
        checkConnections(popupEnabled = false, expectedPopups = 0)
    }

    @Test
    fun `configured popup delay still applies when monitoring is off`() {
        checkConnections(popupEnabled = true, expectedPopups = 0, delaySec = 2)
    }

    @Test
    fun `address-only disconnect permits reconnect even after the user closed the popup`() {
        checkConnections(popupEnabled = true, expectedPopups = 2, disconnectName = null,
            closeBeforeDisconnect = true)
    }

    @Test
    fun `disconnect with only EXTRA_NAME permits reconnect`() {
        checkConnections(popupEnabled = true, expectedPopups = 2, disconnectAddress = null)
    }

    @Test
    fun `empty disconnect name permits reconnect by the known address`() {
        checkConnections(popupEnabled = true, expectedPopups = 2, disconnectName = "")
    }

    @Test
    fun `unidentified or unrelated disconnect does not reset popup suppression`() {
        val context = PopupContext(true, null, 0)
        val receiver = HeadsetReceiver()
        PopupGate.tryShowDisconnected(context, ADDRESS, NAME)
        try {
            receiver.onReceive(context, Event(BluetoothDevice.ACTION_ACL_CONNECTED))
            receiver.onReceive(context, Event(BluetoothDevice.ACTION_ACL_DISCONNECTED, null, null))
            receiver.onReceive(context, Event(BluetoothDevice.ACTION_ACL_DISCONNECTED, null, "00:11:22:33:44:66"))
            receiver.onReceive(context, Event(BluetoothDevice.ACTION_ACL_CONNECTED))
            assertEquals(1, context.broadcasts)
            receiver.onReceive(context, Event(BluetoothDevice.ACTION_ACL_DISCONNECTED, null))
            receiver.onReceive(context, Event(BluetoothDevice.ACTION_ACL_CONNECTED))
            assertEquals(2, context.broadcasts)
            assertEquals(0, context.serviceStarts)
        } finally {
            receiver.onReceive(context, Event(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        }
    }

    private fun checkConnections(
        popupEnabled: Boolean, expectedPopups: Int, waitGaia: Boolean? = null, delaySec: Int = 0,
        disconnectName: String? = NAME, disconnectAddress: String? = ADDRESS, closeBeforeDisconnect: Boolean = false,
    ) {
        val context = PopupContext(popupEnabled, waitGaia, delaySec)
        val receiver = HeadsetReceiver()
        val connected = Event(BluetoothDevice.ACTION_ACL_CONNECTED)
        val disconnected = Event(BluetoothDevice.ACTION_ACL_DISCONNECTED, disconnectName, disconnectAddress)
        PopupGate.tryShowDisconnected(context, ADDRESS, NAME)
        try {
            receiver.onReceive(context, connected)
            // No timer or GAIA callback has fired: only an explicit delay may defer an enabled popup.
            assertEquals(expectedPopups / 2, context.broadcasts)
            receiver.onReceive(context, connected) // Multiple GMS processes must not duplicate the popup.
            if (closeBeforeDisconnect) PopupGate.markUserClosed(ADDRESS, NAME)
            receiver.onReceive(context, disconnected)
            receiver.onReceive(context, connected)
            assertEquals(expectedPopups, context.broadcasts)
            assertEquals(0, context.serviceStarts)
        } finally {
            receiver.onReceive(context, disconnected)
        }
    }

    private class Event(
        private val event: String, private val deviceName: String? = NAME, private val address: String? = ADDRESS,
    ) : Intent() {
        override fun getAction() = event
        override fun getStringExtra(name: String?): String? = when (name) {
            BluetoothDevice.EXTRA_NAME -> deviceName
            "android.bluetooth.device.extra.ADDRESS" -> address
            else -> null
        }
    }

    private class PopupContext(popupEnabled: Boolean, waitGaia: Boolean?, delaySec: Int) : ContextWrapper(null) {
        var broadcasts = 0
        var serviceStarts = 0
        private val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> when (args[0]) {
                    "feat_popup" -> popupEnabled
                    "enable", "auto_service" -> false
                    "popup_wait_gaia" -> waitGaia ?: args[1]
                    else -> args[1]
                }
                "getInt" -> if (args[0] == "popup_delay_sec") delaySec else args[1]
                else -> null
            }
        } as SharedPreferences
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int) = preferences
        override fun sendBroadcast(intent: Intent?) { broadcasts++ }
        override fun startService(service: Intent?): ComponentName? { serviceStarts++; return null }
    }

    companion object {
        private const val NAME = "MOONDROP popup regression test"
        private const val ADDRESS = "00:11:22:33:44:55"
    }
}
