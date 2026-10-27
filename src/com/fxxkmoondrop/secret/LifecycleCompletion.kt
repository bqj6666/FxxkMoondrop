package com.fxxkmoondrop.secret

import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Deliver once on resume, or release the completion when its original owner is destroyed. */
@MainThread
internal fun Lifecycle.runWhenResumed(action: () -> Unit) {
    if (currentState == Lifecycle.State.DESTROYED) return
    addObserver(object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            removeObserver(this)
            action()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            removeObserver(this)
        }
    })
}
