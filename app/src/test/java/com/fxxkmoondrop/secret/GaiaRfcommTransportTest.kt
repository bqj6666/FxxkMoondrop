package com.fxxkmoondrop.secret

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

internal class FakeRfcommSocket(blockConnect: Boolean = false, private val failConnect: Boolean = false) : GaiaRfcommTransport.Socket {
    val connectStarted = CountDownLatch(1)
    val allowConnect = CountDownLatch(if (blockConnect) 1 else 0)
    val eof = CountDownLatch(1)
    val closes = AtomicInteger()
    override val outputStream = ByteArrayOutputStream()
    override val inputStream = object : InputStream() {
        override fun read(): Int { eof.await(); return -1 }
    }
    override fun connect() {
        connectStarted.countDown()
        check(allowConnect.await(2, TimeUnit.SECONDS))
        if (failConnect) throw IOException("connect failed")
    }
    override fun close() {
        closes.incrementAndGet()
        allowConnect.countDown()
        eof.countDown()
    }
}

class GaiaRfcommTransportTest {
    @Test fun `close while connect blocks closes owned socket and rejects its late success`() {
        val socket = FakeRfcommSocket(blockConnect = true)
        val transport = GaiaRfcommTransport({ it() }, {}, { fail("intentional close") }, {})
        var result = true
        val worker = Thread { result = transport.connectSocket(socket) }.apply { start() }
        assertTrue(socket.connectStarted.await(2, TimeUnit.SECONDS))
        transport.disconnect()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertFalse(result)
        assertFalse(transport.isReady())
        assertEquals(1, socket.closes.get())
    }

    @Test fun `connect exception closes socket and reports failure`() {
        val socket = FakeRfcommSocket(failConnect = true)
        val errors = mutableListOf<String>()
        val transport = GaiaRfcommTransport({ it() }, {}, { fail("never connected") }, errors::add)
        assertFalse(transport.connectSocket(socket))
        assertFalse(transport.isReady())
        assertEquals(1, socket.closes.get())
        assertEquals(1, errors.size)
    }

    @Test fun `cancellation before socket creation closes socket without starting connect`() {
        val socket = FakeRfcommSocket()
        val transport = GaiaRfcommTransport({ it() }, {}, {}, {})
        transport.disconnect()
        assertFalse(transport.connectSocket(socket))
        assertEquals(1L, socket.connectStarted.count)
        assertEquals(1, socket.closes.get())
    }

    @Test fun `successful connection is ready and EOF retires it exactly once`() {
        val socket = FakeRfcommSocket()
        val disconnected = CountDownLatch(1)
        val calls = AtomicInteger()
        val transport = GaiaRfcommTransport({ it() }, {}, { calls.incrementAndGet(); disconnected.countDown() }, { fail(it) })
        assertTrue(transport.connectSocket(socket))
        assertTrue(transport.isReady())
        transport.send(byteArrayOf(0, 0x1D, 0x40, 3))
        assertTrue(socket.outputStream.size() > 0)
        socket.eof.countDown()
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertFalse(transport.isReady())
        transport.disconnect()
        assertEquals(1, socket.closes.get())
        assertEquals(1, calls.get())
    }

    @Test fun `response queued before EOF is delivered before disconnect on a delayed dispatcher`() {
        assertQueuedResponseAtEof(cancel = false)
    }

    @Test fun `explicit retirement suppresses queued response and EOF callbacks from old transport`() {
        assertQueuedResponseAtEof(cancel = true)
    }

    private fun assertQueuedResponseAtEof(cancel: Boolean) {
        val posted = LinkedBlockingQueue<() -> Unit>()
        val events = mutableListOf<String>()
        val packet = byteArrayOf(0, 0x1D, 0x41, 3, 2)
        val closes = AtomicInteger()
        val transport = GaiaRfcommTransport({ posted.add(it) }, {
            assertArrayEquals(packet, it)
            events.add("packet")
        }, { events.add("disconnected") }, { fail(it) })
        assertTrue(transport.connectSocket(object : GaiaRfcommTransport.Socket {
            override val inputStream = ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 4, 0, 1) + packet)
            override val outputStream = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun close() { closes.incrementAndGet() }
        }))
        val response = posted.poll(2, TimeUnit.SECONDS) ?: error("missing response callback")
        val eof = posted.poll(2, TimeUnit.SECONDS) ?: error("missing EOF callback")
        assertFalse(transport.isReady())
        assertTrue(events.isEmpty())
        if (cancel) transport.disconnect()
        response()
        eof()
        assertEquals(if (cancel) emptyList<String>() else listOf("packet", "disconnected"), events)
        transport.disconnect()
        assertEquals(1, closes.get())
    }

    @Test fun `input stream failure is terminal and closes socket`() {
        val disconnected = CountDownLatch(1)
        val closes = AtomicInteger()
        val transport = GaiaRfcommTransport({ it() }, {}, { disconnected.countDown() }, {})
        transport.connectSocket(object : GaiaRfcommTransport.Socket {
            override val inputStream: InputStream get() = throw IOException("stream unavailable")
            override val outputStream = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun close() { closes.incrementAndGet() }
        })
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertFalse(transport.isReady())
        assertEquals(1, closes.get())
    }
}
