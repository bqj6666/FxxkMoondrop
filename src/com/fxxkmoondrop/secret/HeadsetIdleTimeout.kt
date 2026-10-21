package com.fxxkmoondrop.secret

/** Tracks elapsed-realtime inactivity, including a cold start without any headset. */
internal class HeadsetIdleTimeout(private var lastSeenMs: Long) {
    companion object {
        const val TIMEOUT_MS = 30_000L
    }

    /** Automatic connections keep monitoring alive; manual control has a fixed bounded lease. */
    fun remainingMs(nowMs: Long, connected: Boolean, automatic: Boolean = true): Long? {
        if (connected && automatic) {
            lastSeenMs = nowMs
            return null
        }
        return (TIMEOUT_MS - (nowMs - lastSeenMs)).coerceAtLeast(0L)
    }
}
