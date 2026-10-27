package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
import com.fxxkmoondrop.secret.GattOperationQueue.Kind
import org.junit.Assert.*
import org.junit.Test

class GattWriteModeTest {
    @Test fun `dual-mode rejection retries within one operation and waits for fallback completion`() {
        val timers = mutableSetOf<Runnable>()
        var deadlines = 0
        val queue = GattOperationQueue({ task, _ -> deadlines++; timers.add(task) }, { timers.remove(it) }, { fail(it) })
        val session = Any()
        val command = Any()
        val next = Any()
        val modes = mutableListOf<Int>()
        var starts = 0
        var nextStarted = false
        queue.open(session)
        assertTrue(queue.enqueue(session, Kind.WRITE, command) {
            starts++
            GaiaBleClient.submitGattWrite(PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) { mode ->
                modes.add(mode)
                mode == WRITE_TYPE_NO_RESPONSE
            }
        })
        queue.enqueue(session, Kind.WRITE, next) { nextStarted = true; true }
        assertEquals(listOf(WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE), modes)
        assertEquals(1, starts)
        assertEquals(1, deadlines)
        assertFalse(nextStarted)
        assertEquals(1, timers.size)
        assertTrue(queue.complete(session, Kind.WRITE, command, 0))
        assertTrue(nextStarted)
        queue.complete(session, Kind.WRITE, next, 0)
        assertTrue(timers.isEmpty())
    }

    @Test fun `rejection only retries a supported alternative and both rejections retire the queue`() {
        for ((properties, expected) in listOf(
            PROPERTY_WRITE to listOf(WRITE_TYPE_DEFAULT),
            PROPERTY_WRITE_NO_RESPONSE to listOf(WRITE_TYPE_NO_RESPONSE),
            (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) to listOf(WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE)
        )) {
            val modes = mutableListOf<Int>()
            val failures = mutableListOf<String>()
            val queue = GattOperationQueue({ _, _ -> }, {}, failures::add)
            val session = Any()
            queue.open(session)
            assertFalse(queue.enqueue(session, Kind.WRITE, Any()) {
                GaiaBleClient.submitGattWrite(properties) { modes.add(it); false }
            })
            assertEquals(expected, modes)
            assertEquals(1, failures.size)
            assertFalse(queue.enqueue(session, Kind.WRITE, Any()) { fail("retired write submitted"); true })
        }
    }

    @Test fun `accepted writes and uncertain exceptions never trigger fallback`() {
        for (throws in listOf(false, true)) {
            val modes = mutableListOf<Int>()
            val failures = mutableListOf<String>()
            val queue = GattOperationQueue({ _, _ -> }, {}, failures::add)
            val session = Any()
            val command = Any()
            queue.open(session)
            queue.enqueue(session, Kind.WRITE, command) {
                GaiaBleClient.submitGattWrite(PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) {
                    modes.add(it)
                    if (throws) error("platform failure")
                    true
                }
            }
            if (!throws) queue.complete(session, Kind.WRITE, command, 133)
            assertEquals(listOf(WRITE_TYPE_DEFAULT), modes)
            assertEquals(1, failures.size)
        }
    }
}
