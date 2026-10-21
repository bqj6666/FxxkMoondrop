package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadsetIdleTimeoutTest {
    @Test
    fun `cold start without a headset expires at thirty seconds`() {
        val idle = HeadsetIdleTimeout(0L)
        assertEquals(30_000L, idle.remainingMs(0L, false))
        assertEquals(1L, idle.remainingMs(29_999L, false))
        assertEquals(0L, idle.remainingMs(30_000L, false))
    }

    @Test
    fun `polling an absent headset does not extend the deadline`() {
        val idle = HeadsetIdleTimeout(100_000L)
        for (elapsed in 0L..30_000L step 5_000L) {
            assertEquals(30_000L - elapsed, idle.remainingMs(100_000L + elapsed, false))
        }
    }

    @Test
    fun `connected observations cancel sleep and refresh the deadline`() {
        val idle = HeadsetIdleTimeout(0L)
        assertNull(idle.remainingMs(25_000L, true))
        assertNull(idle.remainingMs(60_000L, true))
        assertEquals(25_000L, idle.remainingMs(65_000L, false))
        assertEquals(0L, idle.remainingMs(90_000L, false))
    }

    @Test
    fun `a reconnect before expiry cancels the old deadline`() {
        val idle = HeadsetIdleTimeout(0L)
        assertEquals(5_000L, idle.remainingMs(25_000L, false))
        assertNull(idle.remainingMs(29_000L, true))
        assertEquals(29_000L, idle.remainingMs(30_000L, false))
    }

    @Test
    fun `long suspend or Bluetooth unavailability does not reset inactivity`() {
        val idle = HeadsetIdleTimeout(42_000L)
        assertEquals(0L, idle.remainingMs(3_642_000L, false))
    }

    @Test
    fun `manual control stays bounded even while a headset remains connected`() {
        val idle = HeadsetIdleTimeout(0L)
        for (elapsed in 0L..30_000L step 5_000L) {
            assertEquals(30_000L - elapsed, idle.remainingMs(elapsed, true, automatic = false))
        }
    }

    @Test
    fun `a new service instance gets a fresh cold start deadline`() {
        val old = HeadsetIdleTimeout(0L)
        assertEquals(0L, old.remainingMs(60_000L, false))
        val restarted = HeadsetIdleTimeout(60_000L)
        assertEquals(30_000L, restarted.remainingMs(60_000L, false))
        assertEquals(0L, restarted.remainingMs(90_000L, false))
    }
}
