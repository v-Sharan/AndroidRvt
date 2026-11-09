package com.androidrvt.viewer.video

import android.view.Surface
import androidx.annotation.Keep
import com.androidrvt.viewer.AppLog
import com.androidrvt.viewer.data.CameraProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Order matches VideoState in video_engine.c. */
enum class Phase { STOPPED, CONNECTING, PLAYING, RECONNECTING }

data class VideoStatus(
    val phase: Phase = Phase.STOPPED,
    val attempt: Int = 0,
    val detail: String = "",
)

data class VideoStats(
    val fps: Double = 0.0,
    val kilobitsPerSecond: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val decoder: String = "",
)

/** Runs the GStreamer pipeline, its frame watchdog and reconnection on a native thread. */
class VideoEngine(
    private val onRecordingDone: (String) -> Unit,
    private val onRecordingError: (String) -> Unit,
) : AutoCloseable {
    private val mutableStatus = MutableStateFlow(VideoStatus())
    private val mutableStats = MutableStateFlow(VideoStats())
    private val mutableReconnects = MutableStateFlow(0)

    val status: StateFlow<VideoStatus> = mutableStatus.asStateFlow()
    val stats: StateFlow<VideoStats> = mutableStats.asStateFlow()
    val reconnects: StateFlow<Int> = mutableReconnects.asStateFlow()

    private var handle: Long = nativeCreate()

    fun configure(profile: CameraProfile) {
        if (handle == 0L) return
        mutableReconnects.value = 0
        nativeConfigure(
            handle,
            profile.rtspUrl,
            profile.transport.gstValue,
            profile.latencyMs,
            profile.frameTimeoutMs,
            profile.connectTimeoutMs,
            profile.hardwareDecoder,
        )
    }

    fun setSurface(surface: Surface?) {
        if (handle != 0L) nativeSetSurface(handle, surface)
    }

    fun play() {
        if (handle != 0L) nativePlay(handle)
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    fun reconnectNow() {
        if (handle != 0L) nativeReconnectNow(handle)
    }

    fun startRecording(directory: String) {
        if (handle != 0L) nativeStartRecording(handle, directory)
    }

    fun stopRecording() {
        if (handle != 0L) nativeStopRecording(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    @Keep
    private fun onNativeState(state: Int, attempt: Int, detail: String) {
        val phase = Phase.entries[state]
        if (phase == Phase.RECONNECTING && mutableStatus.value.phase == Phase.PLAYING) {
            mutableReconnects.value += 1
        }
        mutableStatus.value = VideoStatus(phase, attempt, AppLog.mask(detail))
    }

    @Keep
    private fun onNativeStats(fps: Double, kilobits: Double, width: Int, height: Int, decoder: String) {
        mutableStats.value = VideoStats(fps, kilobits, width, height, decoder)
    }

    @Keep
    private fun onNativeLog(message: String) {
        AppLog.add(message)
    }

    @Keep
    private fun onNativeRecordingDone(path: String) {
        onRecordingDone(path)
    }

    @Keep
    private fun onNativeRecordingError(message: String) {
        onRecordingError(message)
    }

    private external fun nativeCreate(): Long
    private external fun nativeConfigure(
        handle: Long,
        url: String,
        transport: String,
        latencyMs: Int,
        frameTimeoutMs: Int,
        connectTimeoutMs: Int,
        hardwareDecoder: Boolean,
    )

    private external fun nativeSetSurface(handle: Long, surface: Surface?)
    private external fun nativePlay(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeReconnectNow(handle: Long)
    private external fun nativeStartRecording(handle: Long, directory: String)
    private external fun nativeStopRecording(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
