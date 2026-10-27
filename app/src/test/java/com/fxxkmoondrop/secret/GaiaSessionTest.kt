package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real client state/callbacks with mockable Android handles and fake clocks/sockets, no radio. */
class GaiaSessionTest {
    private lateinit var client: GaiaBleClient
    private var now = 0L
    private val listener = Listener()

    @Before fun setup() {
        client = GaiaBleClient::class.java.getDeclaredConstructor(Function0::class.java)
            .apply { isAccessible = true }.newInstance({ now })
        GaiaBleClient.instance = client
        GaiaBleClient.setSimConnected(false)
        client.setCallback(listener)
    }

    @After fun cleanup() { client.shutdown(); GaiaBleClient.instance = null }

    private fun field(name: String) = GaiaBleClient::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun call(name: String) = GaiaBleClient::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(client)
    private fun begin(): BluetoothGatt {
        val gatt = BluetoothGatt::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        field("gatt").set(client, gatt)
        call("beginGattSession")
        return gatt
    }
    private fun callbacks() = field("gattCallback").get(client) as BluetoothGattCallback
    private fun generation() = field("connectionGeneration").getLong(client)
    private fun retireRfcomm(generation: Long) = GaiaBleClient::class.java.getDeclaredMethod("retireRfcomm", Long::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(client, generation)
    private fun adopt(transport: GaiaRfcommTransport, generation: Long) = GaiaBleClient::class.java.getDeclaredMethod(
        "adoptRfcomm", GaiaRfcommTransport::class.java, Long::class.javaPrimitiveType, String::class.java
    ).apply { isAccessible = true }.invoke(client, transport, generation, "PUDDING")

    private fun beginScan(timeoutMs: Long, done: () -> Unit): Runnable {
        field("scanning").setBoolean(client, true)
        field("scanCallback").set(client, object : ScanCallback() {})
        GaiaBleClient::class.java.getDeclaredMethod("scheduleScanTimeout", Long::class.javaPrimitiveType, Function0::class.java)
            .apply { isAccessible = true }.invoke(client, timeoutMs, done)
        return field("scanTimeout").get(client) as Runnable
    }

    @Test fun `capability deadlines use the same lock as Bluetooth callbacks`() {
        val probe = field("probe").get(client) as CapabilityProbe
        val lock = CapabilityProbe::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(probe)
        assertSame(client, lock)
    }

    @Test fun `name scan and completion survive a quick initial GATT failure`() {
        val gatt = begin()
        var completions = 0
        val timeout = beginScan(25_000) { completions++ }
        val oldGeneration = generation()
        callbacks().onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        assertTrue(generation() > oldGeneration)
        assertNull(field("gatt").get(client))
        assertTrue(field("scanning").getBoolean(client))
        assertSame(timeout, field("scanTimeout").get(client))
        assertNotSame(field("handler").get(client), field("scanHandler").get(client))
        timeout.run()
        timeout.run()
        assertEquals(1, completions)
        assertFalse(field("scanning").getBoolean(client))
    }

    @Test fun `generic scan retains results and completion across the five second connect poll`() {
        var completions = 0
        val timeout = beginScan(8_000) { completions++ }
        @Suppress("UNCHECKED_CAST")
        val hits = field("scanHits").get(client) as MutableList<ScanResult>
        hits.add(ScanResult(null, null, 0, 0L))
        val oldCached = GaiaBleClient.cachedLeAddress
        try {
            GaiaBleClient.cachedLeAddress = "00:11:22:33:44:55"
            val context = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
            }
            now = 5_000
            // The mockable Android adapter is null, but connect still retires the previous attempt.
            client.connect(context, GaiaBleClient.cachedLeAddress)
            call("startGenericScan")
            assertTrue(field("scanning").getBoolean(client))
            assertSame(timeout, field("scanTimeout").get(client))
            assertEquals(1, hits.size)
            now = 8_000
            timeout.run()
            assertEquals(1, completions)
        } finally {
            GaiaBleClient.cachedLeAddress = oldCached
        }
    }

    @Test fun `ready or shutdown cancels discovery and stale scan completion cannot stop replacement`() {
        for (finish in listOf("markReady", "shutdown")) {
            var completions = 0
            val old = beginScan(8_000) { completions++ }
            call(finish)
            assertFalse(field("scanning").getBoolean(client))
            assertNull(field("scanTimeout").get(client))
            val replacement = beginScan(8_000) { completions++ }
            old.run()
            assertTrue(field("scanning").getBoolean(client))
            assertSame(replacement, field("scanTimeout").get(client))
            assertEquals(0, completions)
            replacement.run()
            assertEquals(1, completions)
        }
    }

    @Test fun `normal disconnect retires a successful GATT and allows one replacement attempt`() {
        val old = begin()
        call("markReady")
        field("everConnected").setBoolean(client, true)
        assertTrue(client.isConnected())
        assertTrue(client.isGaiaReady())
        callbacks().onConnectionStateChange(old, 0, BluetoothProfile.STATE_DISCONNECTED)
        assertNull(field("gatt").get(client))
        assertFalse(client.isConnecting())
        assertFalse(client.isConnected())
        assertFalse(client.isGaiaReady())
        assertEquals(1, listener.disconnects)
        assertEquals(0, listener.errors)
        val replacement = begin()
        assertTrue(client.isConnecting())
        callbacks().onConnectionStateChange(old, 0, BluetoothProfile.STATE_DISCONNECTED)
        assertSame(replacement, field("gatt").get(client))
        assertEquals(1, listener.disconnects)
    }

    @Test fun `discovery failure retires GATT instead of leaving connecting guard stuck`() {
        val gatt = begin()
        callbacks().onServicesDiscovered(gatt, 133)
        assertNull(field("gatt").get(client))
        assertFalse(client.isConnecting())
        assertEquals(1, listener.errors)
        assertEquals(1, listener.disconnects)
    }

    @Test fun `missing connection or discovery callback expires independently of another connect`() {
        begin()
        val timeout = field("pendingDeadline").get(client) as Runnable
        assertTrue(client.isConnecting())
        now = GaiaConstants.GATT_PENDING_TIMEOUT_MS
        timeout.run()
        assertNull(field("gatt").get(client))
        assertNull(field("pendingDeadline").get(client))
        assertFalse(client.isConnecting())
        assertEquals(1, listener.errors)
    }

    @Test fun `discovery gets a fresh bounded deadline and ignores the expired connection deadline`() {
        begin()
        val connectionTimeout = field("pendingDeadline").get(client) as Runnable
        now = 8_000
        call("armPendingDeadline")
        val discoveryTimeout = field("pendingDeadline").get(client) as Runnable
        now = 12_000
        connectionTimeout.run()
        assertTrue(client.isConnecting())
        assertSame(discoveryTimeout, field("pendingDeadline").get(client))
        assertEquals(0, listener.errors)
        now = 20_000
        discoveryTimeout.run()
        assertFalse(client.isConnecting())
        assertEquals(1, listener.errors)
    }

    @Test fun `slow connection plus valid queued descriptors can finish after the original setup deadline`() {
        val gatt = begin()
        val connectionTimeout = field("pendingDeadline").get(client) as Runnable
        now = 8_000
        call("armPendingDeadline")
        val discoveryTimeout = field("pendingDeadline").get(client) as Runnable
        val queue = field("gattOperations").get(client) as GattOperationQueue
        val response = Any()
        val data = Any()
        queue.enqueue(gatt, GattOperationQueue.Kind.DESCRIPTOR, response) { true }
        queue.enqueue(gatt, GattOperationQueue.Kind.DESCRIPTOR, data) { true }
        // Successful discovery hands the remaining setup deadline to each queued operation.
        call("cancelPendingDeadline")
        queue.whenIdle(gatt) { call("markReady") }
        now = 11_000
        queue.complete(gatt, GattOperationQueue.Kind.DESCRIPTOR, response, 0)
        now = 12_000
        connectionTimeout.run()
        assertTrue(client.isConnecting())
        assertEquals(0, listener.errors)
        now = 14_000
        queue.complete(gatt, GattOperationQueue.Kind.DESCRIPTOR, data, 0)
        assertTrue(client.isGaiaReady())
        assertSame(gatt, field("gatt").get(client))
        now = 20_000
        discoveryTimeout.run()
        assertTrue(client.isGaiaReady())
    }

    @Test fun `retired pending deadline and queued operation cannot affect new generation`() {
        val old = begin()
        val timeout = field("pendingDeadline").get(client) as Runnable
        val queue = field("gattOperations").get(client) as GattOperationQueue
        val target = Any()
        queue.enqueue(old, GattOperationQueue.Kind.WRITE, target) { true }
        queue.enqueue(old, GattOperationQueue.Kind.WRITE, target) { fail("old command replayed"); true }
        call("disconnectInternal")
        val replacement = begin()
        now = 50_000
        timeout.run()
        assertSame(replacement, field("gatt").get(client))
        assertFalse(queue.complete(old, GattOperationQueue.Kind.WRITE, target, 0))
        assertEquals(0, listener.errors)
    }

    @Test fun `pending RFCOMM is connecting and shutdown closes blocked socket without adoption`() {
        val socket = FakeRfcommSocket(blockConnect = true)
        val transport = GaiaRfcommTransport({ it() }, {}, {}, {})
        val oldGeneration = generation()
        field("rfcommTransport").set(client, transport)
        call("armPendingDeadline")
        val worker = Thread { transport.connectSocket(socket) }.apply { start() }
        assertTrue(socket.connectStarted.await(2, TimeUnit.SECONDS))
        assertTrue(client.isConnecting())
        assertFalse(client.isConnected())
        client.shutdown()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertEquals(1, socket.closes.get())
        adopt(transport, oldGeneration)
        assertFalse(client.isConnected())
        assertFalse(client.isGaiaReady())
        assertFalse(client.isConnecting())
    }

    @Test fun `RFCOMM pending deadline closes a blocked connect without waiting for a poll`() {
        val socket = FakeRfcommSocket(blockConnect = true)
        val transport = GaiaRfcommTransport({ it() }, {}, {}, {})
        field("rfcommTransport").set(client, transport)
        call("armPendingDeadline")
        val timeout = field("pendingDeadline").get(client) as Runnable
        val worker = Thread { transport.connectSocket(socket) }.apply { start() }
        assertTrue(socket.connectStarted.await(2, TimeUnit.SECONDS))
        now = GaiaConstants.GATT_PENDING_TIMEOUT_MS
        timeout.run()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertEquals(1, socket.closes.get())
        assertFalse(client.isConnecting())
        assertFalse(client.isGaiaReady())
        assertEquals(1, listener.errors)
        assertEquals(1, listener.disconnects)
    }

    @Test fun `RFCOMM adoption initializes readiness and control bridge then EOF clears both`() {
        val socket = FakeRfcommSocket()
        val disconnected = CountDownLatch(1)
        val generation = generation()
        val transport = GaiaRfcommTransport({ it() }, {}, {
            retireRfcomm(generation)
            disconnected.countDown()
        }, { fail(it) })
        field("rfcommTransport").set(client, transport)
        assertTrue(transport.connectSocket(socket))
        adopt(transport, generation)
        assertTrue(client.isConnected())
        assertTrue(client.isGaiaReady())
        assertFalse(client.isConnecting())
        assertSame(DeviceControlBridge, field("dcBridge").get(client))
        assertEquals(1, listener.connects)
        socket.eof.countDown()
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertFalse(client.isConnected())
        assertFalse(client.isGaiaReady())
        assertFalse(client.isConnecting())
        assertNull(field("rfcommTransport").get(client))
        assertNull(field("dcBridge").get(client))
        assertEquals(1, listener.disconnects)
        val replacement = begin()
        retireRfcomm(generation)
        assertSame(replacement, field("gatt").get(client))
    }

    private class Listener : GaiaBleClient.Callback {
        var connects = 0
        var disconnects = 0
        var errors = 0
        override fun onConnected(address: String) { connects++ }
        override fun onDisconnected(address: String) { disconnects++ }
        override fun onError(message: String) { errors++ }
        override fun onBatteryLevel(batteryId: Int, level: Int) = Unit
        override fun onAncMode(mode: Int) = Unit
    }
}
