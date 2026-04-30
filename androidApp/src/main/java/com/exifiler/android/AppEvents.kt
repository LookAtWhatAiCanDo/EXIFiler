package com.exifiler.android

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-process event bus for app-wide signals that do not need Android IPC. */
object AppEvents {

    private val _quit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted when the user taps Quit in the notification. */
    val quit: SharedFlow<Unit> = _quit.asSharedFlow()

    fun notifyQuit() {
        _quit.tryEmit(Unit)
    }
}
