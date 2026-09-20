package com.foamplayer.discord

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object DiscordAuthCoordinator {
    private val _redirects = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)

    val redirects: SharedFlow<String> = _redirects

    fun emit(uri: String) {
        _redirects.tryEmit(uri)
    }
}
