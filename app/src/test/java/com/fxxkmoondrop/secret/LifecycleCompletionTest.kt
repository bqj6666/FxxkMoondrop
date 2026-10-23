package com.fxxkmoondrop.secret

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class LifecycleCompletionTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun backgroundCompletionWaitsForResumeAndRunsOnlyOnce() {
        val owner = Owner()
        val lifecycle = owner.lifecycle
        lifecycle.currentState = Lifecycle.State.RESUMED
        lifecycle.currentState = Lifecycle.State.CREATED
        val results = mutableListOf<String>()
        lifecycle.runWhenResumed { results.add("logs.zip") }
        lifecycle.runWhenResumed { results.add("icon saved") }
        assertEquals(2, lifecycle.observerCount)
        lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(emptyList<String>(), results)
        lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(listOf("logs.zip", "icon saved"), results)
        assertEquals(0, lifecycle.observerCount)
        lifecycle.currentState = Lifecycle.State.CREATED
        lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(2, results.size)
    }

    @Test fun resumedOwnerDeliversImmediatelyWithoutRetainingObserver() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        var calls = 0
        owner.lifecycle.runWhenResumed { calls++ }
        assertEquals(1, calls)
        assertEquals(0, owner.lifecycle.observerCount)
    }

    @Test fun destroyedOwnerDropsPendingAndLateCompletions() {
        val original = Owner()
        original.lifecycle.currentState = Lifecycle.State.CREATED
        var calls = 0
        original.lifecycle.runWhenResumed { calls++ }
        original.lifecycle.currentState = Lifecycle.State.DESTROYED
        original.lifecycle.runWhenResumed { calls++ }
        val replacement = Owner()
        replacement.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(0, calls)
        assertEquals(0, original.lifecycle.observerCount)
        assertEquals(0, replacement.lifecycle.observerCount)
    }
}
