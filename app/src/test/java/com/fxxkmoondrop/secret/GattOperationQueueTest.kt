package com.fxxkmoondrop.secret

import org.junit.Assert.*
import org.junit.Test
import com.fxxkmoondrop.secret.GattOperationQueue.Kind

class GattOperationQueueTest {
    private class Clock {
        var now = 0L
        val tasks = linkedMapOf<Runnable, Long>()
        fun advance(ms: Long) {
            now += ms
            tasks.filterValues { it <= now }.keys.toList().forEach { tasks.remove(it); it.run() }
        }
    }

    @Test fun `descriptors GAIA 9ECA and reads wait for matching callback even past 120ms`() {
        val clock = Clock()
        val failures = mutableListOf<String>()
        val queue = GattOperationQueue({ r, ms -> clock.tasks[r] = clock.now + ms }, { clock.tasks.remove(it) }, failures::add)
        val session = Any()
        queue.open(session)
        val kinds = listOf(Kind.DESCRIPTOR, Kind.DESCRIPTOR, Kind.WRITE, Kind.WRITE, Kind.READ)
        val targets = List(kinds.size) { Any() }
        val started = mutableListOf<Int>()
        var busy = false
        kinds.forEachIndexed { i, kind ->
            assertTrue(queue.enqueue(session, kind, targets[i]) {
                check(!busy) { "concurrent ATT operation" }
                busy = true
                started.add(i)
                true
            })
        }
        var ready = false
        queue.whenIdle(session) { ready = true }
        kinds.forEachIndexed { i, kind ->
            clock.advance(250)
            assertEquals((0..i).toList(), started)
            assertFalse(ready)
            assertFalse(queue.complete(Any(), kind, targets[i], 0))
            assertFalse(queue.complete(session, kind, Any(), 0))
            assertFalse(queue.complete(session, if (kind == Kind.READ) Kind.WRITE else Kind.READ, targets[i], 0))
            busy = false
            assertTrue(queue.complete(session, kind, targets[i], 0))
        }
        assertTrue(ready)
        assertTrue(failures.isEmpty())
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun `rejected throwing and failed operations retire queue without starting subsequent work`() {
        for (failure in listOf("false", "throw", "status", "timeout")) {
            val clock = Clock()
            val failures = mutableListOf<String>()
            val queue = GattOperationQueue({ r, ms -> clock.tasks[r] = clock.now + ms }, { clock.tasks.remove(it) }, failures::add)
            val session = Any()
            val target = Any()
            queue.open(session)
            queue.enqueue(session, Kind.WRITE, target) {
                if (failure == "throw") error("platform failure")
                failure != "false"
            }
            var writes = 0
            queue.enqueue(session, Kind.WRITE, Any()) { writes++; true }
            if (failure == "status") queue.complete(session, Kind.WRITE, target, 133)
            if (failure == "timeout") clock.advance(10_000)
            assertEquals(failure, 1, failures.size)
            assertEquals(failure, 0, writes)
            assertFalse(queue.enqueue(session, Kind.READ, Any()) { true })
            assertTrue(clock.tasks.isEmpty())
        }
    }

    @Test fun `optional data rejection or failed completion preserves response setup and subsequent traffic`() {
        for (failure in listOf("false", "status")) {
            val clock = Clock()
            val skipped = mutableListOf<String>()
            val queue = GattOperationQueue({ r, ms -> clock.tasks[r] = clock.now + ms }, { clock.tasks.remove(it) }, { fail(it) })
            val session = Any()
            val response = Any()
            val data = Any()
            val otherDescriptor = Any()
            val started = mutableListOf<Any>()
            queue.open(session)
            queue.enqueue(session, Kind.DESCRIPTOR, response) { started.add(response); true }
            queue.enqueue(session, Kind.DESCRIPTOR, data, onRejected = skipped::add) {
                started.add(data)
                failure != "false"
            }
            queue.enqueue(session, Kind.DESCRIPTOR, otherDescriptor) { started.add(otherDescriptor); true }
            var ready = false
            queue.whenIdle(session) { ready = true }
            queue.complete(session, Kind.DESCRIPTOR, response, 0)
            if (failure == "status") {
                val oldTimeout = clock.tasks.keys.single()
                queue.complete(session, Kind.DESCRIPTOR, data, 133)
                oldTimeout.run()
            }
            assertEquals(listOf(response, data, otherDescriptor), started)
            assertEquals(1, skipped.size)
            assertFalse(ready)
            queue.complete(session, Kind.DESCRIPTOR, otherDescriptor, 0)
            assertTrue(ready)
            assertTrue(queue.enqueue(session, Kind.WRITE, response) { true })
            assertTrue(queue.complete(session, Kind.WRITE, response, 0))
            assertTrue(clock.tasks.isEmpty())
        }
    }

    @Test fun `optional subscription timeout or exception still retires an uncertain ATT session`() {
        for (failure in listOf("timeout", "throw")) {
            val clock = Clock()
            val failures = mutableListOf<String>()
            val queue = GattOperationQueue({ r, ms -> clock.tasks[r] = clock.now + ms }, { clock.tasks.remove(it) }, failures::add)
            val session = Any()
            val data = Any()
            queue.open(session)
            queue.enqueue(session, Kind.DESCRIPTOR, data, onRejected = { fail("uncertain operation skipped") }) {
                if (failure == "throw") error("platform failure")
                true
            }
            queue.whenIdle(session) { fail("uncertain setup became ready") }
            queue.enqueue(session, Kind.WRITE, Any()) { fail("continued on uncertain ATT session"); true }
            if (failure == "timeout") clock.advance(10_000)
            assertEquals(1, failures.size)
            assertFalse(queue.complete(session, Kind.DESCRIPTOR, data, 0))
            assertTrue(clock.tasks.isEmpty())
        }
    }

    @Test fun `retirement discards queued commands callbacks and a dequeued old timeout`() {
        val clock = Clock()
        val queue = GattOperationQueue({ r, ms -> clock.tasks[r] = clock.now + ms }, { clock.tasks.remove(it) }, { fail(it) })
        val old = Any()
        val target = Any()
        queue.open(old)
        queue.enqueue(old, Kind.WRITE, target) { true }
        val timeout = clock.tasks.keys.single()
        queue.enqueue(old, Kind.WRITE, target) { fail("retired command replayed"); true }
        queue.whenIdle(old) { fail("retired readiness callback") }
        queue.close()
        val replacement = Any()
        queue.open(replacement)
        var writes = 0
        queue.enqueue(replacement, Kind.WRITE, target) { writes++; true }
        timeout.run()
        assertFalse(queue.complete(old, Kind.WRITE, target, 0))
        assertFalse(queue.enqueue(old, Kind.WRITE, target) { fail("stale submit"); true })
        assertEquals(1, writes)
        assertTrue(queue.complete(replacement, Kind.WRITE, target, 0))
        assertTrue(clock.tasks.isEmpty())
    }
}
