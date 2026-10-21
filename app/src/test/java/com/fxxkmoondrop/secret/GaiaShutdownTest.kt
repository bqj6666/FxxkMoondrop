package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Ownership/receiver cleanup only; JVM Android stubs cannot exercise real BLE or RFCOMM. */
class GaiaShutdownTest {
    private lateinit var client: GaiaBleClient

    @Before
    fun setUp() {
        GaiaBleClient.instance = null
        client = GaiaBleClient.getInstance()
    }

    @After
    fun tearDown() {
        client.disconnect()
        GaiaBleClient.instance = null
    }

    @Test
    fun `destroyed service releases its callback and connection address`() {
        val owner = Listener()
        client.deviceAddress = "00:11:22:33:44:55"
        client.setMonitorCallback(owner)
        client.releaseCallback(owner)
        assertNull(client.deviceAddress)
        // Intentional controller shutdown must not withdraw the headset's manual controls.
        assertEquals(0, owner.disconnects)
        client.disconnect()
        assertEquals(0, owner.disconnects)
    }

    @Test
    fun `service cleanup does not tear down a foreground UI owner`() {
        val service = Listener()
        val ui = Listener()
        client.setMonitorCallback(service)
        client.setCallback(ui)
        client.deviceAddress = "00:11:22:33:44:55"
        client.releaseCallback(service)
        assertEquals("00:11:22:33:44:55", client.deviceAddress)
        assertEquals(0, service.disconnects)
        assertEquals(0, ui.disconnects)
        client.releaseCallback(ui)
        assertNull(client.deviceAddress)
    }

    @Test
    fun `UI departure returns callbacks to the detector and its later shutdown releases everything`() {
        val service = Listener()
        val ui = Listener()
        client.setMonitorCallback(service)
        client.setCallback(ui)
        client.deviceAddress = "00:11:22:33:44:55"
        client.releaseCallback(ui)
        assertNotNull(client.deviceAddress)
        client.disconnect()
        assertEquals(1, service.disconnects)
        assertEquals(0, ui.disconnects)
        client.deviceAddress = "00:11:22:33:44:55"
        client.releaseCallback(service)
        assertNull(client.deviceAddress)
        client.disconnect()
        assertEquals(1, service.disconnects)
    }

    @Test
    fun `queued reconnect from an older registration cannot disturb a resumed UI session`() {
        val service = Listener()
        val ui = Listener()
        client.setMonitorCallback(service)
        val oldGeneration = client.setCallback(ui)
        client.releaseCallback(ui)
        val resumedGeneration = client.setCallback(ui)
        assertTrue(resumedGeneration > oldGeneration)
        client.deviceAddress = "00:11:22:33:44:55"
        val context = DiscoveryContext()
        client.forceReconnect(context, "00:11:22:33:44:66", ui, oldGeneration)
        assertEquals("00:11:22:33:44:55", client.deviceAddress)
        assertEquals(0, context.registrations)
    }

    @Test
    fun `explicit shutdown releases both owners and rejects queued UI work`() {
        val service = Listener()
        val ui = Listener()
        val eq = Any()
        client.setMonitorCallback(service)
        val generation = client.setCallback(ui)
        client.retainConnection(eq)
        client.deviceAddress = "00:11:22:33:44:55"
        client.shutdown()
        assertNull(client.deviceAddress)
        val context = DiscoveryContext()
        client.forceReconnect(context, "00:11:22:33:44:55", ui, generation)
        assertEquals(0, context.registrations)
        client.releaseConnection(eq)
        client.releaseCallback(ui)
        client.disconnect()
        assertEquals(0, service.disconnects)
        assertEquals(0, ui.disconnects)
    }

    @Test
    fun `EQ connection lease keeps the link after the overview releases its callback`() {
        val ui = Listener()
        val eq = Any()
        client.setCallback(ui)
        client.deviceAddress = "00:11:22:33:44:55"
        client.retainConnection(eq)
        client.releaseCallback(ui)
        assertEquals("00:11:22:33:44:55", client.deviceAddress)
        client.releaseConnection(eq)
        assertNull(client.deviceAddress)
    }

    @Test
    fun `disconnect unregisters automatic discovery and a later explicit connect restores it`() {
        val context = DiscoveryContext()
        client.connect(context, "00:11:22:33:44:55")
        assertNotNull(context.receiver)
        client.disconnect()
        assertNull(context.receiver)
        client.connect(context, "00:11:22:33:44:55")
        assertNotNull(context.receiver)
        assertEquals(2, context.registrations)
    }

    @Test
    fun `retired GATT callbacks cannot mutate a replacement session`() {
        // Framework objects here come from the mockable Android jar, not a Bluetooth stack.
        val constructor = BluetoothGatt::class.java.getDeclaredConstructor().apply { isAccessible = true }
        val oldGatt = constructor.newInstance()
        val gatt = GaiaBleClient::class.java.getDeclaredField("gatt").apply { isAccessible = true }
        gatt.set(client, oldGatt)
        client.disconnect()
        val replacement = constructor.newInstance()
        gatt.set(client, replacement)
        val owner = Listener()
        client.setCallback(owner)
        val callbacks = GaiaBleClient::class.java.getDeclaredField("gattCallback")
            .apply { isAccessible = true }.get(client) as BluetoothGattCallback
        callbacks.onConnectionStateChange(oldGatt, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
        callbacks.onServicesDiscovered(oldGatt, BluetoothGatt.GATT_FAILURE)
        assertSame(replacement, gatt.get(client))
        assertEquals(0, owner.disconnects)
        assertEquals(0, owner.errors)
    }

    private class Listener : GaiaBleClient.Callback {
        var disconnects = 0
        var errors = 0
        override fun onConnected(address: String) = Unit
        override fun onDisconnected(address: String) { disconnects++ }
        override fun onBatteryLevel(batteryId: Int, level: Int) = Unit
        override fun onAncMode(mode: Int) = Unit
        override fun onError(message: String) { errors++ }
    }

    private class DiscoveryContext : ContextWrapper(null) {
        var receiver: BroadcastReceiver? = null
        var registrations = 0
        private val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            check(method.name == "getString")
            args[1]
        } as SharedPreferences
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File("build/nonexistent-gaia-test-files")
        override fun getSharedPreferences(name: String?, mode: Int) = preferences
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            this.receiver = receiver
            registrations++
            return null
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver?) {
            assertSame(this.receiver, receiver)
            this.receiver = null
        }
    }
}
