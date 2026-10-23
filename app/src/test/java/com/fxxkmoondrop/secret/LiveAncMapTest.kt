package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LiveAncMapTest {
    @After fun cleanup() { GaiaBleClient.instance?.disconnect(); GaiaBleClient.instance = null }

    @Test fun `live custom map keeps sentinel and rejects other invalid codes`() {
        GaiaBleClient.instance = null
        val client = GaiaBleClient.getInstance()
        val values = mapOf("anc_map_custom" to 1, "anc_map_0" to -1, "anc_map_1" to 99)
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            check(method.name == "getInt")
            values[args[0]] ?: args[1]
        } as SharedPreferences
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int) = preferences
        }
        GaiaBleClient::class.java.getDeclaredField("context").apply { isAccessible = true }.set(client, context)
        assertArrayEquals(intArrayOf(-1, 2, 3, 4), client.getEffectiveAncMap())
    }

    @Test fun `live Robin map preserves unsupported slots for SET and GET`() {
        GaiaBleClient.instance = null
        val client = GaiaBleClient.getInstance()
        GaiaBleClient::class.java.getDeclaredField("connectedDeviceName").apply {
            isAccessible = true
            set(client, "ROBIN'S EARPHONES")
        }
        val probe = GaiaBleClient::class.java.getDeclaredField("probe").apply { isAccessible = true }
            .get(client) as CapabilityProbe
        probe.handleFeatureResponse(byteArrayOf(0, 8, 1))
        val map = client.getEffectiveAncMap()
        assertArrayEquals(intArrayOf(-1, 1, 2, -1), map)
        assertArrayEquals(intArrayOf(1, 2), client.supportedUiModes())
        val getMap = GaiaBleClient::class.java.getDeclaredMethod("readAncGetMap").apply { isAccessible = true }
            .invoke(client) as IntArray?
        for (ui in 1..2) {
            val deviceMode = GaiaCommands.ancDevFromUi(probe.ancPath, ui, map)
            assertEquals(ui, deviceMode)
            assertEquals(ui, GaiaCommands.ancUiFromDev(probe.ancPath, deviceMode, map, getMap))
        }
        val writes = ArrayList<ByteArray>()
        val characteristic = object : BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0) {
            override fun setValue(value: ByteArray): Boolean { writes.add(value.clone()); return true }
        }
        val gatt = BluetoothGatt::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        GaiaBleClient::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(client, gatt)
        GaiaBleClient::class.java.getDeclaredField("cmdChar").apply { isAccessible = true }.set(client, characteristic)
        val queue = GaiaBleClient::class.java.getDeclaredField("gattOperations").apply { isAccessible = true }
            .get(client) as GattOperationQueue
        queue.open(gatt)
        client.setAncMode(2, null)
        assertArrayEquals(byteArrayOf(0, 0x1d, 0x10, 4, 2), writes.single())
        val packets = GaiaBleClient::class.java.getDeclaredField("packetHandler").apply { isAccessible = true }
            .get(client) as GaiaPacketHandler
        packets.handlePacket(byteArrayOf(0, 0x1d, 0x11, 3, 1))
        // Android's JVM Handler does not dispatch; inspect the real parser's decoded result log.
        assertTrue(AppLog.snapshot().lineSequence().last { "ancModeParse" in it }.endsWith("dev=1 ui=1"))
        var errors = 0
        val callback = object : GaiaBleClient.AncControlCallback {
            override fun onAncModeResult(mode: Int) { fail("Unsupported mode reported as success") }
            override fun onAncError(message: String) { errors++ }
        }
        client.setAncMode(0, callback)
        client.setAncMode(3, callback)
        assertEquals(2, errors)
        assertEquals(1, writes.size)
    }
}
