package com.androidrvt.viewer.video

import android.view.Surface
import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Runs one GStreamer pipeline on a native thread and renders it to a [Surface]. */
class VideoEngine(pipelineDescription: String) : AutoCloseable {
    private val mutableStatus = MutableStateFlow("Starting")
    val status: StateFlow<String> = mutableStatus.asStateFlow()

    private var handle: Long = nativeCreate(pipelineDescription)

    fun setSurface(surface: Surface?) {
        if (handle != 0L) nativeSetSurface(handle, surface)
    }

    fun play() {
        if (handle != 0L) nativePlay(handle)
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    @Keep
    private fun onNativeState(state: String) {
        mutableStatus.value = state
    }

    @Keep
    private fun onNativeError(message: String) {
        mutableStatus.value = "Error: $message"
    }

    private external fun nativeCreate(description: String): Long
    private external fun nativeSetSurface(handle: Long, surface: Surface?)
    private external fun nativePlay(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
