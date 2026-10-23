package com.fxxkmoondrop.secret

import android.os.Handler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GaiaReceiveValidationTest {
    private val writes = ArrayList<Int>()
    private val probe = CapabilityProbe(Handler(), { true }, { true }) { _, command, _ -> writes.add(command) }
    private val packets = GaiaPacketHandler(Handler(), { null }, { null }, probe,
        { AncProfileLib.DEFAULT_MAP }, { null }, { _, _ -> }, { null })

    private fun receive(feature: Int, command: Int, payload: ByteArray, type: Int = GaiaCommands.TYPE_RESPONSE) {
        packets.handlePacket(GaiaCommands.v3PacketVendor(GaiaConstants.GAIA_VENDOR, feature, type, command, payload))
    }

    @Test fun `only a complete ANC response confirms a path`() {
        probe.startProbes()
        packets.handlePacket(byteArrayOf(0, 0x1d))
        for (type in listOf(GaiaCommands.TYPE_COMMAND, GaiaCommands.TYPE_RESPONSE, 3)) {
            receive(GaiaConstants.FEATURE_ANC_V2, 3, byteArrayOf(), type)
        }
        receive(GaiaConstants.FEATURE_ANC_V2, 3, byteArrayOf(-1))
        receive(GaiaConstants.FEATURE_ANC_V2, 3, byteArrayOf(2, 3))
        receive(GaiaConstants.FEATURE_ANC_V2, 3, byteArrayOf(2), GaiaCommands.TYPE_COMMAND)
        receive(GaiaConstants.FEATURE_ANC_V2, 1, byteArrayOf(2)) // response ID 1 is not MODE_CHANGE
        receive(GaiaConstants.FEATURE_ANC_V2, 3, byteArrayOf(2), GaiaCommands.TYPE_NOTIFICATION)
        assertEquals(GaiaCommands.ANC_PATH_UNKNOWN, probe.ancPath)
        assertFalse(probe.probeDone)
        receive(GaiaConstants.FEATURE_ANC_V2, 3, byteArrayOf(2))
        assertEquals(GaiaCommands.ANC_PATH_ANC_V2, probe.ancPath)
        assertTrue(probe.probeDone)
    }

    @Test fun `mode change notification still confirms support`() {
        probe.startProbes()
        receive(GaiaConstants.FEATURE_ANC_V2, 1, byteArrayOf(2), GaiaCommands.TYPE_NOTIFICATION)
        assertEquals(GaiaCommands.ANC_PATH_ANC_V2, probe.ancPath)
    }

    @Test fun `malformed capability and device control packets supply no evidence`() {
        for (payload in listOf(byteArrayOf(), byteArrayOf(1, 13), byteArrayOf(2, 0, 0))) {
            receive(GaiaConstants.FEATURE_BASIC, 1, payload)
        }
        receive(GaiaConstants.FEATURE_BASIC, 1, byteArrayOf(0, 13, 1), GaiaCommands.TYPE_COMMAND)
        receive(GaiaConstants.FEATURE_DAC_GAIN, 1, byteArrayOf())
        receive(GaiaConstants.FEATURE_LED, 1, byteArrayOf(1), GaiaCommands.TYPE_NOTIFICATION)
        assertFalse(probe.basicAlive)
        assertFalse(probe.probeDone)
        assertTrue(probe.features.isEmpty())
        receive(GaiaConstants.FEATURE_DAC_GAIN, 1, byteArrayOf(2))
        assertTrue(probe.hasFeature(GaiaConstants.FEATURE_DAC_GAIN))
    }

    @Test fun `truncated bitmap retains complete words and marks discovery incomplete`() {
        receive(0, 1, byteArrayOf(0, 0, 0, 4, 0x12, 0x34))
        assertEquals(setOf(GaiaConstants.FEATURE_ANC_V1), probe.features)
        assertEquals(GaiaCommands.ANC_PATH_ANC_V1, probe.ancPath)
        assertTrue(probe.basicAlive)
        assertTrue(probe.probeDone)
        assertTrue(probe.probeTruncated)
        assertEquals(1, probe.status())
        assertTrue(writes.isEmpty())
    }

    @Test fun `each valid feature page replaces the timeout during a long discovery`() {
        val timeoutField = CapabilityProbe::class.java.getDeclaredField("featurePageTimeout").apply { isAccessible = true }
        var previousTimeout: Runnable? = null
        repeat(30) { page ->
            receive(0, if (page == 0) 1 else 2, byteArrayOf(1, (32 + page).toByte(), 1))
            val timeout = timeoutField.get(probe) as Runnable
            assertNotSame(previousTimeout, timeout)
            previousTimeout?.run() // A deadline from an earlier page must not end this discovery.
            assertFalse(probe.probeDone)
            receive(0, 2, byteArrayOf(1, 13)) // Malformed continuation must not extend the deadline.
            assertSame(timeout, timeoutField.get(probe))
            previousTimeout = timeout
        }
        assertEquals(30, writes.size)
        receive(0, 2, byteArrayOf(0, 2, 1))
        previousTimeout!!.run()
        assertTrue(probe.probeDone)
        assertFalse(probe.probeTruncated)
        assertEquals((32..61).toSet() + GaiaConstants.FEATURE_ANC_V1, probe.features)
        assertTrue(probe.hasFeature(GaiaConstants.FEATURE_ANC_V1))
        assertNull(timeoutField.get(probe))
    }

    @Test fun `page receipt and timeout retirement share an atomic critical section`() {
        val lock = Any()
        val racedProbe = CapabilityProbe(Handler(), { true }, { true }, lock) { _, _, _ -> }
        val field = CapabilityProbe::class.java.getDeclaredField("featurePageTimeout").apply { isAccessible = true }
        for (pageWins in listOf(true, false)) {
            racedProbe.reset()
            racedProbe.handleFeatureResponse(byteArrayOf(1, 13, 1))
            val deadline = field.get(racedProbe) as Runnable
            val entered = CountDownLatch(1)
            val contender = Thread {
                entered.countDown()
                if (pageWins) deadline.run()
                else racedProbe.handleFeatureResponse(byteArrayOf(1, 2, 1), 2)
            }
            try {
                synchronized(lock) {
                    contender.start()
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (contender.state != Thread.State.BLOCKED && contender.isAlive && System.nanoTime() < end) Thread.yield()
                    assertEquals(Thread.State.BLOCKED, contender.state)
                    if (pageWins) racedProbe.handleFeatureResponse(byteArrayOf(1, 2, 1), 2)
                    else deadline.run()
                }
            } finally {
                contender.join(2000)
            }
            assertFalse(contender.isAlive)
            if (pageWins) {
                assertFalse(racedProbe.probeDone)
                assertNotSame(deadline, field.get(racedProbe))
                racedProbe.handleFeatureResponse(byteArrayOf(0, 8, 1), 2)
                assertEquals(setOf(13, 2, 8), racedProbe.features)
                assertFalse(racedProbe.probeTruncated)
            } else {
                assertTrue(racedProbe.probeDone)
                assertTrue(racedProbe.probeTruncated)
                assertEquals(setOf(13), racedProbe.features)
                assertNull(field.get(racedProbe))
            }
        }
    }

    @Test fun `feature continuation is requested routed and merged with response evidence`() {
        probe.onFeatureResponseSeen(GaiaConstants.FEATURE_LED)
        receive(GaiaConstants.FEATURE_BASIC, 1, byteArrayOf(1, 13, 1))
        assertFalse(probe.probeDone)
        assertEquals(listOf(GaiaCommands.C_BASIC_GET_SUPPORTED_FEATURES_NEXT), writes)
        probe.startProbes()
        receive(GaiaConstants.FEATURE_BASIC, 1, byteArrayOf(1, 13, 1))
        assertEquals(1, writes.size) // Neither a restart nor a duplicate resets the device's cursor.
        receive(GaiaConstants.FEATURE_BASIC, 2, byteArrayOf(0, 2, 1))
        assertTrue(probe.probeDone)
        assertFalse(probe.probeTruncated)
        assertEquals(setOf(13, 2, GaiaConstants.FEATURE_LED), probe.features)
        assertEquals(GaiaCommands.ANC_PATH_ANC_V1, probe.ancPath)
        assertEquals(1, probe.status())
        receive(GaiaConstants.FEATURE_BASIC, 2, byteArrayOf(0, 8, 1))
        assertFalse(probe.hasFeature(8)) // unsolicited NEXT after completion
    }

    @Test fun `empty final feature page is valid and endless pages are bounded`() {
        receive(0, 1, byteArrayOf(1, 13, 1))
        receive(0, 2, byteArrayOf(0))
        assertTrue(probe.probeDone)
        assertFalse(probe.probeTruncated)
        probe.reset()
        writes.clear()
        receive(0, 1, byteArrayOf(1, 13, 1))
        repeat(200) { receive(0, 2, byteArrayOf(1, 13, 1)) }
        assertEquals(127, writes.size)
        assertTrue(probe.probeDone)
        assertTrue(probe.probeTruncated)
    }

    @Test fun `missing continuation times out incomplete and reset retires timeout`() {
        val timeoutField = CapabilityProbe::class.java.getDeclaredField("featurePageTimeout").apply { isAccessible = true }
        receive(0, 1, byteArrayOf(1, 13, 1))
        val timeout = timeoutField.get(probe) as Runnable
        timeout.run()
        assertTrue(probe.probeDone)
        assertTrue(probe.probeTruncated)
        assertTrue(probe.hasFeature(13))
        probe.reset()
        receive(0, 1, byteArrayOf(1, 2, 1))
        timeout.run()
        assertFalse(probe.probeDone)
        (timeoutField.get(probe) as Runnable).run()
        assertTrue(probe.probeDone)
    }
}
