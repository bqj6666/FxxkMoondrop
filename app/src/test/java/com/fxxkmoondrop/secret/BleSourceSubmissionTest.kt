package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.function.BooleanSupplier
import org.junit.Assert.*
import org.junit.Test

class BleSourceSubmissionTest {
    private class Fixture {
        val gatt = BluetoothGatt::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val command = characteristic()
        val response = characteristic()
        val capability = characteristic()
        val info = characteristic()
        val client = BleSourceSwitchClient(Handler(Looper.getMainLooper()), null)
        val submissions = mutableListOf<Pair<GattOperationQueue.Kind, BluetoothGattCharacteristic>>()
        val queue = GattOperationQueue({ _, _ -> }, {}, { client.clear() })
        var accept = true
        init {
            queue.open(gatt)
            client.bind(gatt, command, response, characteristic(), capability, info)
            client.setGattOperations(object : BleSourceSwitchClient.GattOperations {
                override fun write(ch: BluetoothGattCharacteristic, value: ByteArray, start: BooleanSupplier) =
                    submit(GattOperationQueue.Kind.WRITE, ch, start)
                override fun read(ch: BluetoothGattCharacteristic, start: BooleanSupplier) =
                    submit(GattOperationQueue.Kind.READ, ch, start)
                private fun submit(kind: GattOperationQueue.Kind, ch: BluetoothGattCharacteristic, start: BooleanSupplier) =
                    queue.enqueue(gatt, kind, ch, isCurrent = { start.asBoolean }) {
                        submissions.add(kind to ch)
                        accept
                    }
            })
        }
        fun timeout() = BleSourceSwitchClient::class.java.getDeclaredField("timeout").apply { isAccessible = true }.get(client)
        companion object {
            fun characteristic() = BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0)
        }
    }
    private class Callback<T> : BleSourceSwitchClient.SrcCallback<T> {
        var errors = 0
        var successes = 0
        override fun onSuccess(value: T) { successes++ }
        override fun onError(message: String) { errors++ }
    }

    @Test fun `9ECA write waits behind GAIA and starts protocol timeout only on submission`() {
        val f = Fixture()
        val gaia = Any()
        f.queue.enqueue(f.gatt, GattOperationQueue.Kind.WRITE, gaia) { true }
        val cb = Callback<BleSourceProtocol.SrcResponse>()
        f.client.execute(BleSourceProtocol.CMD_GET_AUDIO_SOURCE, null, cb)
        assertTrue(f.submissions.isEmpty())
        assertNull(f.timeout())
        f.queue.complete(f.gatt, GattOperationQueue.Kind.WRITE, gaia, 0)
        assertEquals(listOf(GattOperationQueue.Kind.WRITE to f.command), f.submissions)
        assertNotNull(f.timeout())
        f.queue.close()
        f.client.clear()
        assertNull(f.timeout())
        assertFalse(f.client.isPresent)
        assertEquals(1, cb.errors)
    }

    @Test fun `both owned metadata reads use the shared queue and report immediate failure once`() {
        for (capability in listOf(true, false)) {
            val f = Fixture()
            f.accept = false
            val cap = Callback<BleSourceProtocol.SrcSourceCapability>()
            val info = Callback<BleSourceProtocol.SrcFwInfo>()
            if (capability) f.client.readSourceCapability(cap) else f.client.readProtocolFirmwareInfo(info)
            assertEquals(listOf(GattOperationQueue.Kind.READ to if (capability) f.capability else f.info), f.submissions)
            assertEquals(1, if (capability) cap.errors else info.errors)
            assertNull(f.timeout())
        }
    }

    @Test fun `retired queued writes are skipped without failing the ATT session`() {
        for (cancel in listOf(true, false)) {
            val f = Fixture()
            val gaia = Any()
            f.queue.enqueue(f.gatt, GattOperationQueue.Kind.WRITE, gaia) { true }
            val cb = Callback<BleSourceProtocol.SrcResponse>()
            f.client.execute(BleSourceProtocol.CMD_SET_PRESET_EQ, byteArrayOf(1), cb)
            if (cancel) f.client.clear()
            else f.client.onCharacteristicRead(f.response, BluetoothGatt.GATT_FAILURE, null)
            assertEquals(1, cb.errors)
            var followingStarted = false
            f.queue.enqueue(f.gatt, GattOperationQueue.Kind.WRITE, Any()) { followingStarted = true; true }
            f.queue.complete(f.gatt, GattOperationQueue.Kind.WRITE, gaia, 0)
            assertTrue(f.submissions.isEmpty())
            assertNull(f.timeout())
            assertTrue(followingStarted)
            assertEquals(1, cb.errors)
        }
    }

    @Test fun `expired command and stale deadline cannot retire a queued successor`() {
        val f = Fixture()
        val first = Callback<BleSourceProtocol.SrcResponse>()
        f.client.execute(BleSourceProtocol.CMD_GET_PRESET_EQ, null, first)
        val expired = f.timeout() as Runnable
        expired.run()
        assertNull(f.timeout())
        assertEquals(1, first.errors)
        val second = Callback<BleSourceProtocol.SrcResponse>()
        f.client.execute(BleSourceProtocol.CMD_GET_PRESET_EQ, null, second)
        val reply = { seq: Int -> BleSourceProtocol.buildFrame(BleSourceProtocol.FRAME_RESPONSE,
            BleSourceProtocol.CMD_GET_PRESET_EQ, seq, byteArrayOf(0, 1, 8, 7)) }
        f.client.onCharacteristicChanged(f.response, reply(0))
        expired.run()
        assertEquals(0, second.errors)
        f.queue.complete(f.gatt, GattOperationQueue.Kind.WRITE, f.command, 0)
        assertEquals(2, f.submissions.size)
        val currentDeadline = f.timeout() as Runnable
        f.client.onCharacteristicChanged(f.response, reply(1).copyOf(6))
        assertSame(currentDeadline, f.timeout())
        f.client.onCharacteristicChanged(f.response, reply(1))
        assertEquals(1, second.successes)
        assertNull(f.timeout())
        expired.run()
        currentDeadline.run()
        assertEquals(0, second.errors)
    }

    @Test fun `cancelled metadata reads do not start after a queue wait`() {
        for (capability in listOf(true, false)) {
            val f = Fixture()
            val gaia = Any()
            f.queue.enqueue(f.gatt, GattOperationQueue.Kind.WRITE, gaia) { true }
            val cap = Callback<BleSourceProtocol.SrcSourceCapability>()
            val info = Callback<BleSourceProtocol.SrcFwInfo>()
            if (capability) f.client.readSourceCapability(cap) else f.client.readProtocolFirmwareInfo(info)
            f.client.clear()
            f.queue.complete(f.gatt, GattOperationQueue.Kind.WRITE, gaia, 0)
            assertTrue(f.submissions.isEmpty())
            assertNull(f.timeout())
            assertEquals(1, if (capability) cap.errors else info.errors)
        }
    }

    @Test fun `completion error clears pending 9ECA command once`() {
        val f = Fixture()
        val cb = Callback<BleSourceProtocol.SrcResponse>()
        f.client.execute(BleSourceProtocol.CMD_GET_AUDIO_SOURCE, null, cb)
        assertEquals(0, cb.errors)
        f.queue.complete(f.gatt, GattOperationQueue.Kind.WRITE, f.command, 133)
        f.client.clear()
        assertEquals(1, cb.errors)
        assertFalse(f.client.isPresent)
        assertNull(f.timeout())
    }
}
