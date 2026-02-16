package com.screencast.tv.player

interface PlayerCallback {
    fun onPlaying()
    fun onPaused()
    fun onStopped()
    fun onPositionUpdate(positionMs: Long, durationMs: Long)
    fun onError(message: String)
}
