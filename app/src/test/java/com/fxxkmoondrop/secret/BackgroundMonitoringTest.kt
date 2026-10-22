package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for preference/receiver policy, not UI or Android service lifecycle emulation. */
class BackgroundMonitoringTest {
    private val context = MonitorContext()

    @After
    fun resetRunning() {
        HeadsetDetectService.RUNNING = false
    }

    @Test
    fun `both switches must be enabled`() {
        assertFalse(KeepAlive.enabled(null))
        assertTrue(KeepAlive.enabled(context))
        for (enable in listOf(false, true)) {
            for (auto in listOf(false, true)) {
                context.flags["enable"] = enable
                context.flags["auto_service"] = auto
                assertEquals(enable && auto, KeepAlive.enabled(context))
            }
        }
    }

    @Test
    fun `settings setter writes both flags and starts or stops monitoring`() {
        KeepAlive.setEnabled(context, false)
        assertEquals(mapOf("enable" to false, "auto_service" to false, "monitor_idle" to true), context.flags)
        assertEquals(1, context.stops)
        assertEquals(0, context.starts)
        KeepAlive.setEnabled(context, true)
        assertTrue(KeepAlive.enabled(context))
        assertEquals(1, context.starts)
        // The service lifecycle, not a caller that may fail, owns RUNNING.
        assertFalse(HeadsetDetectService.RUNNING)
    }

    @Test
    fun `a rejected start does not claim the service is running`() {
        context.rejectStart = true
        KeepAlive.setEnabled(context, true)
        assertEquals(1, context.starts)
        assertFalse(HeadsetDetectService.RUNNING)
    }

    @Test
    fun `boot connection and update receivers honor either disabled flag`() {
        for (flag in listOf("enable", "auto_service")) {
            context.flags.clear()
            context.flags[flag] = false
            for (intent in backgroundIntents()) BootReceiver().onReceive(context, intent)
        }
        assertEquals(0, context.starts)
    }

    @Test
    fun `native connection broadcasts restart an idle service without a Bluetooth hook`() {
        for (intent in backgroundIntents()) BootReceiver().onReceive(context, intent)
        assertEquals(backgroundIntents().size, context.starts)
    }

    @Test
    fun `profile disconnects and unknown broadcasts do not start an idle service`() {
        for (action in listOf(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
            BootReceiver().onReceive(context, Event(action, BluetoothProfile.STATE_DISCONNECTED))
            BootReceiver().onReceive(context, Event(action, BluetoothProfile.STATE_CONNECTING))
            BootReceiver().onReceive(context, Event(action))
        }
        BootReceiver().onReceive(context, null)
        BootReceiver().onReceive(context, Event("unrelated"))
        BootReceiver().onReceive(context, Event(BootReceiver.ACTION_BT_EVENT, event = "invalid"))
        assertEquals(0, context.starts)
    }

    @Test
    fun `legacy Bluetooth hook events still work when enabled`() {
        BootReceiver().onReceive(context, Event(BootReceiver.ACTION_BT_EVENT, event = "disconnected"))
        assertEquals(0, context.starts)
        BootReceiver().onReceive(context, Event(BootReceiver.ACTION_BT_EVENT, event = "connected"))
        HeadsetDetectService.RUNNING = true
        BootReceiver().onReceive(context, Event(BootReceiver.ACTION_BT_EVENT, event = "disconnected"))
        assertEquals(2, context.starts)
    }

    @Test
    fun `watchdog does not prod an already running service`() {
        HeadsetDetectService.RUNNING = true
        AliveReceiver().onReceive(context, Event(KEEPALIVE))
        assertEquals(0, context.starts)
    }

    @Test
    fun `watchdog recovers a missing service only when both flags are enabled`() {
        for (flag in listOf("enable", "auto_service")) {
            context.flags.clear()
            context.flags[flag] = false
            AliveReceiver().onReceive(context, Event(KEEPALIVE))
        }
        assertEquals(0, context.starts)
        context.flags.clear()
        AliveReceiver().onReceive(context, Event(KEEPALIVE))
        assertEquals(1, context.starts)
    }

    @Test
    fun `receivers tolerate platform background start rejection`() {
        context.rejectStart = true
        BootReceiver().onReceive(context, Event(BluetoothDevice.ACTION_ACL_CONNECTED))
        AliveReceiver().onReceive(context, Event(KEEPALIVE))
        assertEquals(2, context.starts)
        assertFalse(HeadsetDetectService.RUNNING)
    }

    @Test
    fun `a queued watchdog cannot undo intentional idle even after process recreation`() {
        KeepAlive.markIdle(context)
        val recreated = MonitorContext()
        recreated.flags.putAll(context.flags)
        assertTrue(KeepAlive.enabled(recreated))
        assertFalse(KeepAlive.shouldRecover(recreated))
        AliveReceiver().onReceive(recreated, Event(KEEPALIVE))
        assertEquals(0, recreated.starts)
        BootReceiver().onReceive(recreated, Event(BluetoothDevice.ACTION_ACL_CONNECTED))
        assertTrue(KeepAlive.shouldRecover(recreated))
        assertEquals(1, recreated.starts)
    }

    @Test
    fun `unrelated ACL links do not revive idle monitoring`() {
        KeepAlive.markIdle(context)
        for (name in listOf("Fitness Band", "Keyboard", null)) {
            BootReceiver().onReceive(context, Event(BluetoothDevice.ACTION_ACL_CONNECTED, deviceName = name))
        }
        assertEquals(0, context.starts)
        assertFalse(KeepAlive.shouldRecover(context))
    }

    @Test
    fun `ACL connections require a Moondrop name and never use generic probing`() {
        assertTrue(BootReceiver.acceptsConnection(BluetoothDevice.ACTION_ACL_CONNECTED, -1, "MOONDROP"))
        assertFalse(BootReceiver.acceptsConnection(BluetoothDevice.ACTION_ACL_CONNECTED, -1, "Uncatalogued audio model"))
        assertFalse(BootReceiver.acceptsConnection(BluetoothDevice.ACTION_ACL_CONNECTED, -1, null))
        assertTrue(BootReceiver.acceptsConnection(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_CONNECTED, "Uncatalogued audio model"))
    }

    @Test
    fun `ACL null-name fallback only resolves the bonded device at the same address`() {
        val bondedNames = mapOf("target" to "MOONDROP Golden Ages", "other" to "Fitness Band")
        val lookedUp = mutableListOf<String>()
        val name = BootReceiver.resolveAclName(null, "target") { address ->
            lookedUp += address
            bondedNames[address]
        }

        assertEquals("MOONDROP Golden Ages", name)
        assertEquals(listOf("target"), lookedUp)
        assertFalse(BootReceiver.acceptsConnection(BluetoothDevice.ACTION_ACL_CONNECTED, -1,
            BootReceiver.resolveAclName(null, "other", bondedNames::get)))
        assertNull(BootReceiver.resolveAclName(null, "unpaired") { bondedNames[it] })
    }

    @Test
    fun `ACL name fallback fails closed when address is missing or Bluetooth access is denied`() {
        assertNull(BootReceiver.resolveAclName(null, null) { error("must not query without an address") })
        assertNull(BootReceiver.resolveAclName(null, "target") { throw SecurityException("permission denied") })
        assertEquals("MOONDROP", BootReceiver.resolveAclName("MOONDROP", null) {
            error("a readable remote name needs no bonded lookup")
        })
    }

    @Test
    fun `unknown audio models remain eligible for protocol probing`() {
        BootReceiver().onReceive(context, Event(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothProfile.STATE_CONNECTED, deviceName = "Uncatalogued audio model"))
        assertEquals(1, context.starts)
    }

    @Test
    fun `missing names and rejected audio devices do not start monitoring`() {
        val rejected = "Rejected monitoring test device"
        DeviceMatcher.reject(null, rejected)
        try {
            for (name in listOf(rejected, null)) {
                BootReceiver().onReceive(context, Event(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothProfile.STATE_CONNECTED, deviceName = name))
            }
            assertEquals(0, context.starts)
        } finally {
            DeviceMatcher.clearRejected(null)
        }
    }

    @Test
    fun `explicit enable clears the intentional idle marker`() {
        KeepAlive.markIdle(context)
        KeepAlive.setEnabled(context, true)
        assertTrue(KeepAlive.shouldRecover(context))
        assertEquals(1, context.starts)
    }

    private fun backgroundIntents() = listOf(
        Event(Intent.ACTION_BOOT_COMPLETED),
        Event(Intent.ACTION_MY_PACKAGE_REPLACED),
        Event(BluetoothDevice.ACTION_ACL_CONNECTED),
        Event(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTED),
        Event(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTED),
        Event(BootReceiver.ACTION_BT_EVENT, event = "connected"),
    )

    private class Event(
        private val name: String,
        private val state: Int = -1,
        private val event: String? = null,
        private val deviceName: String? = "MOONDROP",
    ) : Intent() {
        override fun getAction() = name
        override fun getIntExtra(name: String?, defaultValue: Int) =
            if (name == BluetoothProfile.EXTRA_STATE) state else defaultValue
        override fun getStringExtra(name: String?) = when (name) {
            "evt" -> event
            BluetoothDevice.EXTRA_NAME -> deviceName
            else -> null
        }
    }

    private class MonitorContext : ContextWrapper(null) {
        val flags = mutableMapOf<String, Boolean>()
        var starts = 0
        var stops = 0
        var rejectStart = false

        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { self, method, args ->
            when (method.name) {
                "putBoolean" -> { flags[args[0] as String] = args[1] as Boolean; self }
                "apply" -> null
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor

        private val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> flags[args[0] as String] ?: args[1] as Boolean
                "getString" -> args[1]
                "edit" -> editor
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        override fun getSharedPreferences(name: String?, mode: Int) = preferences
        // No real AlarmManager in JVM policy tests; delivery/cancellation is a device-test case.
        override fun getSystemService(name: String): Any? = null
        override fun startService(service: Intent?): ComponentName? {
            starts++
            if (rejectStart) throw IllegalStateException("Background start denied")
            return null
        }
        override fun stopService(service: Intent?): Boolean {
            stops++
            return true
        }
    }

    companion object {
        private const val KEEPALIVE = "com.fxxkmoondrop.secret.KEEPALIVE"
    }
}
