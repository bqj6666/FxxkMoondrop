package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BleSourceReceiveTest {
    private fun response(cmd: Int = BleSourceProtocol.CMD_GET_PRESET_EQ, seq: Int = 1,
                         payload: ByteArray = byteArrayOf(0, 1, 8, 7)) =
        BleSourceProtocol.buildFrame(BleSourceProtocol.FRAME_RESPONSE, cmd, seq, payload)

    private class Result<T> : BleSourceSwitchClient.SrcCallback<T> {
        val values = ArrayList<T>()
        val errors = ArrayList<String>()
        override fun onSuccess(value: T) { values.add(value) }
        override fun onError(message: String) { errors.add(message) }
    }

    // Isolate receive ownership from Android GATT submission and its separate queue tests.
    private fun installPending(client: BleSourceSwitchClient, seq: Int,
                               cb: BleSourceSwitchClient.SrcCallback<BleSourceProtocol.SrcResponse>) {
        val pendingClass = BleSourceSwitchClient::class.java.declaredClasses.single { it.simpleName == "Pending" }
        val pending = pendingClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(BleSourceProtocol.CMD_GET_PRESET_EQ, seq, cb)
        BleSourceSwitchClient::class.java.getDeclaredField("pending").apply { isAccessible = true }.set(client, pending)
    }

    @Test fun `parser requires valid header full bounded length and complete success payload`() {
        val valid = response()
        for (length in 0 until valid.size) assertNull(BleSourceProtocol.parseResponse(valid.copyOf(length)))
        for (index in 0..2) {
            val bad = valid.clone().apply { this[index] = 0 }
            assertFalse(BleSourceProtocol.isFrame(bad, BleSourceProtocol.FRAME_RESPONSE))
            assertNull(BleSourceProtocol.parseResponse(bad))
        }
        assertNull(BleSourceProtocol.parseResponse(valid + byteArrayOf(0)))
        val oversized = valid.copyOf(21).apply { this[5] = 15 }
        assertNull(BleSourceProtocol.parseResponse(oversized))
        assertNull(BleSourceProtocol.parseResponse(response(payload = byteArrayOf(0))))
        assertNotNull(BleSourceProtocol.parseResponse(valid))
        assertNotNull(BleSourceProtocol.parseResponse(response(payload = byteArrayOf(7)))) // status-only denial
        assertNull(BleSourceProtocol.parseNotification(valid))
        val notification = BleSourceProtocol.buildFrame(BleSourceProtocol.FRAME_NOTIFICATION,
            BleSourceProtocol.NOTIF_PRESET_EQ_CHANGED, 0, byteArrayOf(1, 0, 0))
        assertNotNull(BleSourceProtocol.parseNotification(notification))
        assertNull(BleSourceProtocol.parseNotification(notification.copyOf(6)))
    }

    @Test fun `late and malformed responses cannot consume the current transaction`() {
        val client = BleSourceSwitchClient(Handler(), null)
        val responseChar = BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0)
        client.bind(null, null, responseChar, null, null, null)
        val expired = Result<BleSourceProtocol.SrcResponse>()
        installPending(client, 0, expired)
        client.clear() // same retirement of pending as timeout; no Android scheduler in JVM stubs
        val current = Result<BleSourceProtocol.SrcResponse>()
        installPending(client, 1, current)
        client.onCharacteristicChanged(null, response(seq = 0))
        client.onCharacteristicChanged(null, response(cmd = BleSourceProtocol.CMD_SET_PRESET_EQ))
        client.onCharacteristicChanged(null, response().copyOf(6))
        client.onCharacteristicChanged(null, response(payload = byteArrayOf(0)))
        val badMagic = response().apply { this[0] = 0 }
        client.onCharacteristicRead(responseChar, BluetoothGatt.GATT_SUCCESS, badMagic)
        client.onCharacteristicRead(responseChar, BluetoothGatt.GATT_SUCCESS, null)
        client.onCharacteristicChanged(null, null)
        assertTrue(expired.values.isEmpty())
        assertTrue(current.values.isEmpty())
        assertTrue(current.errors.isEmpty())
        client.onCharacteristicChanged(null, response())
        client.onCharacteristicChanged(null, response())
        assertEquals(1, current.values.size)
        assertTrue(current.errors.isEmpty())
    }

    @Test fun `unsolicited late and duplicate information reads are ignored`() {
        val cap = BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0)
        val info = BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0)
        val client = BleSourceSwitchClient(Handler(), null)
        client.bind(null, null, null, null, cap, info)
        val capValue = byteArrayOf(1, 0, 0, 0)
        val infoValue = ByteArray(12)
        client.onCharacteristicRead(cap, BluetoothGatt.GATT_SUCCESS, capValue)
        client.onCharacteristicRead(info, BluetoothGatt.GATT_SUCCESS, infoValue)
        client.onCharacteristicRead(cap, BluetoothGatt.GATT_SUCCESS, null)
        client.onCharacteristicRead(info, BluetoothGatt.GATT_FAILURE, null)
        val capResult = Result<BleSourceProtocol.SrcSourceCapability>()
        val infoResult = Result<BleSourceProtocol.SrcFwInfo>()
        val capField = BleSourceSwitchClient::class.java.getDeclaredField("pendingCapability").apply { isAccessible = true }
        val infoField = BleSourceSwitchClient::class.java.getDeclaredField("pendingInfo").apply { isAccessible = true }
        capField.set(client, capResult)
        client.onCharacteristicRead(cap, BluetoothGatt.GATT_SUCCESS, capValue)
        client.onCharacteristicRead(cap, BluetoothGatt.GATT_SUCCESS, capValue)
        infoField.set(client, infoResult)
        client.onCharacteristicRead(info, BluetoothGatt.GATT_SUCCESS, infoValue)
        client.onCharacteristicRead(info, BluetoothGatt.GATT_SUCCESS, infoValue)
        assertEquals(1, capResult.values.size)
        assertEquals(1, infoResult.values.size)
        capField.set(client, capResult)
        infoField.set(client, infoResult)
        client.clear()
        client.onCharacteristicRead(cap, BluetoothGatt.GATT_SUCCESS, capValue)
        client.onCharacteristicRead(info, BluetoothGatt.GATT_SUCCESS, infoValue)
        assertEquals(1, capResult.values.size)
        assertEquals(1, infoResult.values.size)
        assertEquals(1, capResult.errors.size) // Retirement fails each outstanding owner once.
        assertEquals(1, infoResult.errors.size)
    }

    private class ReplyClient(var payload: ByteArray) : BleSourceSwitchClient(Handler(), null) {
        var requests = 0
        override fun execute(cmdId: Int, payload: ByteArray?, cb: SrcCallback<BleSourceProtocol.SrcResponse>) {
            requests++
            cb.onSuccess(BleSourceProtocol.parseResponse(BleSourceProtocol.buildFrame(
                BleSourceProtocol.FRAME_RESPONSE, cmdId, 0, this.payload))!!)
        }
    }

    @Test fun `denied preset change takes rollback path and successful change selects preset`() {
        val client = ReplyClient(byteArrayOf(7, 0, 1, 7))
        var selected = 1
        val previous = selected
        selected = 2 // caller's optimistic selection
        val cb = object : BleSourceSwitchClient.SrcCallback<BleSourceProtocol.SrcPresetEqChange> {
            override fun onSuccess(value: BleSourceProtocol.SrcPresetEqChange) { selected = value.currentPreset }
            override fun onError(message: String) { selected = previous }
        }
        client.setPresetEq(2, cb)
        assertEquals(previous, selected)
        client.payload = byteArrayOf(0, 2, 1, 7)
        client.setPresetEq(2, cb)
        assertEquals(2, selected)
    }

    @Test fun `failed or out of bounds preset and config responses never reach success`() {
        val client = ReplyClient(byteArrayOf())
        for (payload in listOf(byteArrayOf(7, 0, 8, 7), byteArrayOf(0, 0, -1, 7),
                byteArrayOf(0, 8, 8, 7), byteArrayOf(0, 0, 0, 7))) {
            client.payload = payload
            val result = Result<BleSourceProtocol.SrcPresetEqInfo>()
            client.getPresetEq(result)
            assertTrue(result.values.isEmpty())
            assertEquals(1, result.errors.size)
        }
        for ((status, count) in listOf(7 to 32, 0 to 33, 0 to 255)) {
            client.payload = byteArrayOf(status.toByte(), 7, count.toByte(), 0, 0, 0, 0, 0)
            val result = Result<BleSourceProtocol.SrcPeqConfig>()
            client.getPeqConfig(result)
            assertTrue(result.values.isEmpty()) // EQ cannot start its point loop
            assertEquals(1, result.errors.size)
        }
        client.payload = byteArrayOf(0, 7, 32, 0, 0, 0, 0, 0)
        val good = Result<BleSourceProtocol.SrcPeqConfig>()
        client.getPeqConfig(good)
        assertEquals(32, good.values.single().pointCount)
    }

    @Test fun `point requests and response indexes are bounded and status checked`() {
        val client = ReplyClient(byteArrayOf(0, 31, 100, 0, 0, 0, 1, 0, 0))
        val invalid = Result<BleSourceProtocol.SrcPeqPoint>()
        for (index in listOf(-1, 32, 255, 256)) client.getPeqPoint(index, invalid)
        val preset = Result<BleSourceProtocol.SrcPresetEqChange>()
        client.setPresetEq(-1, preset)
        client.setPresetEq(8, preset)
        assertEquals(0, client.requests)
        assertEquals(4, invalid.errors.size)
        assertEquals(2, preset.errors.size)
        for ((status, index) in listOf(7 to 31, 0 to 32, 0 to 0, 0 to 255)) {
            client.payload = byteArrayOf(status.toByte(), index.toByte(), 100, 0, 0, 0, 1, 0, 0)
            val result = Result<BleSourceProtocol.SrcPeqPoint>()
            client.getPeqPoint(31, result)
            assertTrue(result.values.isEmpty())
            assertEquals(1, result.errors.size)
        }
        client.payload = byteArrayOf(0, 31, 100, 0, 0, 0, 1, 0, 0)
        val good = Result<BleSourceProtocol.SrcPeqPoint>()
        client.getPeqPoint(31, good)
        assertEquals(31, good.values.single().pointIndex)
    }
}
