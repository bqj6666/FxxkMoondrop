package com.fxxkmoondrop.secret

import java.util.ArrayDeque

/** One ATT operation at a time. The owner serializes calls with its session lock. */
internal class GattOperationQueue(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onFailure: (String) -> Unit
) {
    enum class Kind { WRITE, READ, DESCRIPTOR }
    private class Operation(val kind: Kind, val target: Any, val onRejected: ((String) -> Unit)?, val isCurrent: () -> Boolean, val start: () -> Boolean)
    private val waiting = ArrayDeque<Operation>()
    private var session: Any? = null
    private var active: Operation? = null
    private var timeout: Runnable? = null
    private var idle: (() -> Unit)? = null

    fun open(session: Any) {
        close()
        this.session = session
    }

    fun close() {
        session = null
        timeout?.let(cancel)
        timeout = null
        active = null
        waiting.clear()
        idle = null
    }

    /** onRejected permits best-effort work; timeout/exception still retires the session. */
    fun enqueue(session: Any, kind: Kind, target: Any, onRejected: ((String) -> Unit)? = null,
                isCurrent: () -> Boolean = { true }, start: () -> Boolean): Boolean {
        if (this.session !== session) return false
        waiting.add(Operation(kind, target, onRejected, isCurrent, start))
        drain()
        return this.session === session
    }

    fun whenIdle(session: Any, action: () -> Unit) {
        if (this.session !== session) return
        idle = action
        drain()
    }

    fun complete(session: Any, kind: Kind, target: Any, status: Int): Boolean {
        val op = active ?: return false
        if (this.session !== session || op.kind != kind || op.target !== target) return false
        finish(op, if (status != 0) "GATT ${op.kind} failed: $status" else null)
        return true
    }

    private fun finish(op: Operation, error: String?) {
        timeout?.let(cancel)
        timeout = null
        active = null
        if (error != null && op.onRejected == null) {
            fail(error)
            return
        }
        if (error != null) op.onRejected?.invoke(error)
        drain()
    }

    private fun drain() {
        if (session == null || active != null) return
        val op = waiting.poll()
        if (op == null) {
            val action = idle
            idle = null
            action?.invoke()
            return
        }
        if (!op.isCurrent()) {
            drain()
            return
        }
        active = op
        val task = Runnable {
            if (active === op) fail("GATT ${op.kind} callback timeout")
        }
        timeout = task
        schedule(task, 10_000L)
        val accepted = try { op.start() } catch (e: Exception) {
            fail("GATT ${op.kind} threw: ${e.message}")
            return
        }
        // Only definite rejection/completion errors are safe to skip for optional work.
        if (!accepted && active === op) finish(op, "GATT ${op.kind} rejected")
    }

    private fun fail(message: String) {
        // A timed-out operation may still complete: never reuse this ATT session.
        close()
        onFailure(message)
    }
}
