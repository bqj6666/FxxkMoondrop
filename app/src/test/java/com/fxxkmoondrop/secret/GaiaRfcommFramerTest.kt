package com.fxxkmoondrop.secret

import org.junit.Assert.*
import org.junit.Test

class GaiaRfcommFramerTest {
    private val mode = byteArrayOf(0, 0x1d, 0x41, 3, 2)
    private val gain = GaiaCommands.v3PacketVendor(0x1d, GaiaConstants.FEATURE_DAC_GAIN,
        GaiaConstants.TYPE_RESPONSE, GaiaConstants.CMD_DAC_GET_GAIN, byteArrayOf(1))

    @Test fun `bare fixed replies survive a quiet gap at every byte`() {
        val zeroReplies = listOf(
            GaiaConstants.FEATURE_ANC_V2 to GaiaConstants.CMD_GET_CURRENT_MODE,
            GaiaConstants.FEATURE_LED to GaiaConstants.CMD_LED_GET_STATE,
            GaiaConstants.FEATURE_DAC_GAIN to GaiaConstants.CMD_DAC_GET_GAIN,
            GaiaConstants.FEATURE_SPATIAL_AUDIO to GaiaConstants.CMD_SPATIAL_GET_STATE,
            GaiaConstants.FEATURE_SPATIAL_AUDIO to GaiaConstants.CMD_SPATIAL_GET_HEAD_TRACKING
        ).map { (feature, command) ->
            GaiaCommands.v3PacketVendor(0x1d, feature, GaiaConstants.TYPE_RESPONSE, command, byteArrayOf(0))
        }
        for (frame in listOf(mode, gain) + zeroReplies) {
            val standalone = GaiaRfcommFramer()
            assertArrayEquals(frame, standalone.feed(frame, true).single())
            assertEquals(0, standalone.pendingSize())
            for (split in 1 until frame.size) {
                val framer = GaiaRfcommFramer()
                assertTrue(framer.feed(frame.copyOfRange(0, split), true).isEmpty())
                assertEquals(split, framer.pendingSize())
                val result = framer.feed(frame.copyOfRange(split, frame.size), true)
                assertEquals(1, result.size)
                assertArrayEquals(frame, result.single())
                assertEquals(0, framer.pendingSize())
            }
            val framer = GaiaRfcommFramer()
            val result = frame.flatMap { framer.feed(byteArrayOf(it), true) }
            assertEquals(1, result.size)
            assertArrayEquals(frame, result.single())
        }
    }

    @Test fun `empty bare mode reply preserves the following bare or transport frame`() {
        val emptyMode = mode.copyOf(4)
        val wrappedGain = byteArrayOf(0xff.toByte(), 3, 0, 1) + gain
        for (next in listOf(gain, wrappedGain)) {
            for (splitHeader in listOf(false, true)) {
                val framer = GaiaRfcommFramer()
                if (splitHeader) assertTrue(framer.feed(emptyMode, true).isEmpty())
                val result = framer.feed(if (splitHeader) next else emptyMode + next, true)
                assertEquals(2, result.size)
                assertArrayEquals(emptyMode, result[0])
                assertArrayEquals(gain, result[1])
                assertEquals(0, framer.pendingSize())
            }
        }
    }

    @Test fun `ambiguous empty reply plus split vendor prefix prioritizes a complete zero reply`() {
        val emptyMode = mode.copyOf(4)
        val framer = GaiaRfcommFramer()
        assertTrue(framer.feed(emptyMode, true).isEmpty())
        assertArrayEquals(emptyMode + byteArrayOf(0), framer.feed(byteArrayOf(0), true).single())
        assertEquals(0, framer.pendingSize())
        // The malformed stream loses its split header; a subsequent complete frame resynchronizes.
        assertTrue(framer.feed(gain.copyOfRange(1, gain.size), true).isEmpty())
        assertArrayEquals(gain, framer.feed(gain, true).single())
    }

    @Test fun `zero state byte is not mistaken for the following vendor header`() {
        val zeroMode = mode.copyOf().apply { this[4] = 0 }
        val framer = GaiaRfcommFramer()
        val combined = framer.feed(zeroMode + gain, true)
        assertEquals(2, combined.size)
        assertArrayEquals(zeroMode, combined[0])
        assertArrayEquals(gain, combined[1])
        assertArrayEquals(zeroMode, framer.feed(zeroMode, true).single())
        assertEquals(0, framer.pendingSize())
        assertTrue(framer.feed(byteArrayOf(0), true).isEmpty())
        assertArrayEquals(gain, framer.feed(gain.copyOfRange(1, gain.size), true).single())
        assertEquals(0, framer.pendingSize())
        val wrappedZero = byteArrayOf(0xff.toByte(), 3, 0, 1) + zeroMode
        assertArrayEquals(zeroMode, framer.feed(wrappedZero, true).single())
        assertEquals(0, framer.pendingSize())
    }

    @Test fun `bare header and partial battery pair wait for payload`() {
        val frame = byteArrayOf(0, 0x1d, 0x1b, 1, 1, 80)
        val framer = GaiaRfcommFramer()
        for (b in frame.dropLast(1)) assertTrue(framer.feed(byteArrayOf(b), true).isEmpty())
        assertArrayEquals(frame, framer.feed(byteArrayOf(frame.last()), true).single())
    }

    @Test fun `framed replies still reassemble at every byte and concatenated bare replies split`() {
        val wrapped = byteArrayOf(0xff.toByte(), 3, 0, 1) + mode
        val framer = GaiaRfcommFramer()
        val result = wrapped.flatMap { framer.feed(byteArrayOf(it), true) }
        assertArrayEquals(mode, result.single())
        val bare = framer.feed(mode + gain, true)
        assertEquals(2, bare.size)
        assertArrayEquals(mode, bare[0])
        assertArrayEquals(gain, bare[1])
        assertEquals(0, framer.pendingSize())
    }
}
