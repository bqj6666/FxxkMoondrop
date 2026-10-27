package com.fxxkmoondrop.secret

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

/** State only: a ready transport can still be waiting for its first ANC capability/mode reply. */
class AncBridgeRollbackTest {
    private val oldClient = GaiaBleClient.instance
    private val oldSimConnected = GaiaBleClient.isSimConnected()
    private val fields = listOf("currentMode", "currentModeVersion", "lastSentMode", "lastSentAt", "sCtx")
        .map { AncBridge::class.java.getDeclaredField(it).apply { isAccessible = true } }
    private val saved = fields.map { it.get(null) }
    private lateinit var client: GaiaBleClient

    @Before fun setup() {
        GaiaBleClient.instance = null
        client = GaiaBleClient.getInstance()
        GaiaBleClient.setSimConnected(false)
        for (name in listOf("connected", "gaiaReady", "useRfcomm")) {
            GaiaBleClient::class.java.getDeclaredField(name).apply {
                isAccessible = true
                setBoolean(client, true)
            }
        }
        assertTrue(client.isConnected())
        assertTrue(client.isGaiaReady())
        fields.first { it.name == "sCtx" }.set(null, null)
    }

    @After fun cleanup() {
        GaiaBleClient.instance?.shutdown()
        GaiaBleClient.instance = oldClient
        GaiaBleClient.setSimConnected(oldSimConnected)
        fields.zip(saved).forEach { (field, value) -> field.set(null, value) }
    }

    private fun priorMode(mode: Int) {
        fields.first { it.name == "currentMode" }.setInt(null, mode)
        fields.first { it.name == "lastSentMode" }.setInt(null, -1)
    }

    private fun onValidation(action: () -> Unit) {
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            check(method.name == "getInt")
            args[1]
        } as SharedPreferences
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                action()
                return preferences
            }
        }
        GaiaBleClient::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(client, context)
        }
    }

    @Test fun `rejected commands restore prior state only without a newer mode report`() {
        for (reported in listOf(null, 1, 2)) for (previous in listOf(-1, 1)) {
            onValidation {
                if (reported != null) {
                    // Pause the provider request after its optimistic update, before rejection.
                    val reply = Thread { AncBridge.notifyAncMode(reported) }.apply {
                        isDaemon = true
                        start()
                    }
                    reply.join(2000)
                    assertFalse("Mode report blocked by request", reply.isAlive)
                }
            }
            priorMode(previous)
            val version = AncBridge.getCurrentModeVersion()
            // No capability response yet: the real client rejects this request synchronously.
            AncBridge.setAncMode(2)
            val expected = reported ?: previous
            assertEquals("previous=$previous reported=$reported", expected, AncBridge.getCurrentMode())
            assertEquals(version + 2, AncBridge.getCurrentModeVersion())
            assertEquals(expected, FastPairPanel.selectedMode(AncBridge.getCurrentMode(),
                client.isConnected(), client.isGaiaReady()))
        }
    }

    @Test fun `overlapping rejected requests never retain another rejected selection`() {
        for (previous in listOf(-1, 1)) {
            priorMode(previous)
            val validating = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            fun request(mode: Int) = Thread {
                try { AncBridge.setAncMode(mode) } catch (t: Throwable) { failure.set(t) }
            }.apply { isDaemon = true }
            val first = request(2)
            val second = request(3)
            onValidation {
                if (Thread.currentThread() === first) {
                    validating.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            try {
                first.start()
                assertTrue(validating.await(2, TimeUnit.SECONDS))
                second.start()
                // Before the fix B rejects while A waits; serialized B waits for A to retire.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (second.isAlive && second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertTrue("Second request neither completed nor serialized",
                    !second.isAlive || second.state == Thread.State.BLOCKED)
            } finally {
                release.countDown()
                first.join(2000)
                second.join(2000)
            }
            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertNull(failure.get())
            assertEquals(previous, AncBridge.getCurrentMode())
        }
    }
}
