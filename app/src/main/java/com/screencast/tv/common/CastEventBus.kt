package com.screencast.tv.common

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Simple in-process event bus for cast events.
 * Replaces broadcast-based communication which is unreliable on newer Android.
 */
object CastEventBus {
    private const val TAG = "CastEventBus"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(CastEvent) -> Unit>()

    fun register(listener: (CastEvent) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
        Log.d(TAG, "Listener registered, total: ${listeners.size}")
    }

    fun unregister(listener: (CastEvent) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
        Log.d(TAG, "Listener unregistered, total: ${listeners.size}")
    }

    fun post(event: CastEvent) {
        Log.d(TAG, "Event posted: $event")
        mainHandler.post {
            synchronized(listeners) {
                listeners.forEach { it(event) }
            }
        }
    }
}
