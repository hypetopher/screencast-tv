package com.screencast.tv.common

sealed class CastEvent {
    data class Play(val url: String, val title: String? = null, val startPosition: Double = 0.0) : CastEvent()
    data class StartMirroring(val dummy: Unit = Unit) : CastEvent()
    data class Pause(val dummy: Unit = Unit) : CastEvent()
    data class Resume(val dummy: Unit = Unit) : CastEvent()
    data class Stop(val dummy: Unit = Unit) : CastEvent()
    data class Seek(val positionSeconds: Double) : CastEvent()
    data class SetVolume(val volume: Float) : CastEvent()
}
