package com.androidrvt.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidrvt.viewer.data.CameraProfile
import com.androidrvt.viewer.data.ProfileStore
import com.androidrvt.viewer.link.ControlCommand
import com.androidrvt.viewer.link.LinkEvent
import com.androidrvt.viewer.link.PlaceholderCodec
import com.androidrvt.viewer.link.UdpLink
import com.androidrvt.viewer.media.MediaSaver
import com.androidrvt.viewer.net.NetworkBinder
import com.androidrvt.viewer.video.GstRuntime
import com.androidrvt.viewer.video.Phase
import com.androidrvt.viewer.video.VideoEngine
import com.androidrvt.viewer.video.VideoStats
import com.androidrvt.viewer.video.VideoStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProfileStore(application)
    private val saver = MediaSaver(application)

    private val mutableProfiles = MutableStateFlow(store.loadProfiles())
    val profiles: StateFlow<List<CameraProfile>> = mutableProfiles.asStateFlow()

    private val mutableActive = MutableStateFlow(
        mutableProfiles.value.firstOrNull { it.id == store.loadActiveId() } ?: mutableProfiles.value.first(),
    )
    val activeProfile: StateFlow<CameraProfile> = mutableActive.asStateFlow()

    private var startupError: String? = null
    val engine: VideoEngine? = createEngine()

    val videoStatus: StateFlow<VideoStatus> =
        engine?.status ?: MutableStateFlow(VideoStatus(detail = startupError.orEmpty()))
    val videoStats: StateFlow<VideoStats> = engine?.stats ?: MutableStateFlow(VideoStats())
    val reconnects: StateFlow<Int> = engine?.reconnects ?: MutableStateFlow(0)

    val link = UdpLink(PlaceholderCodec(), viewModelScope)

    private val mutableRecording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = mutableRecording.asStateFlow()

    private val mutableTrack = MutableStateFlow<LinkEvent.Track?>(null)
    val track: StateFlow<LinkEvent.Track?> = mutableTrack.asStateFlow()

    private val mutableTelemetry = MutableStateFlow<Map<String, String>>(emptyMap())
    val telemetry: StateFlow<Map<String, String>> = mutableTelemetry.asStateFlow()

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    private val mutableSnapshotRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snapshotRequests: SharedFlow<Unit> = mutableSnapshotRequests.asSharedFlow()

    private val networkBinder = NetworkBinder(application, ::onNetworkChanged)

    @Volatile private var touchPan = 0f
    @Volatile private var touchTilt = 0f
    @Volatile private var touchZoom = 0f
    @Volatile private var padPan = 0f
    @Volatile private var padTilt = 0f
    @Volatile private var padZoom = 0f
    @Volatile private var lastTrackAt = 0L
    private var foreground = false

    init {
        engine?.configure(mutableActive.value)
        networkBinder.bindToWifi(mutableActive.value.bindToWifi)
        collectLinkEvents()
        startControlLoop()
        startTrackExpiry()
    }

    private fun createEngine(): VideoEngine? = try {
        GstRuntime.init(getApplication())
        VideoEngine(::onRecordingSegmentDone, ::onRecordingFailed)
    } catch (t: Throwable) {
        startupError = "${t.javaClass.simpleName}: ${t.message}"
        AppLog.add("Video engine failed to start: $startupError")
        null
    }

    fun onForeground() {
        foreground = true
        networkBinder.setHighPerformance(true)
        restartLink()
        engine?.play()
    }

    fun onBackground() {
        foreground = false
        stopMotion()
        link.send(ControlCommand.Stop)
        setRecording(false)
        engine?.stop()
        networkBinder.setHighPerformance(false)
        viewModelScope.launch {
            delay(STOP_FLUSH_MS)
            if (!foreground) link.stop()
        }
    }

    private fun onNetworkChanged() {
        viewModelScope.launch(Dispatchers.Main) {
            if (!foreground) return@launch
            restartLink()
            if (videoStatus.value.phase != Phase.PLAYING) engine?.reconnectNow()
        }
    }

    private fun restartLink() {
        val profile = mutableActive.value
        if (profile.udpHost.isBlank()) {
            link.stop()
        } else {
            link.start(
                UdpLink.Config(
                    host = profile.udpHost,
                    targetPort = profile.udpTargetPort,
                    localPort = profile.udpLocalPort,
                    heartbeatMs = profile.heartbeatMs,
                ),
            )
        }
    }

    fun saveProfile(profile: CameraProfile) {
        val current = mutableProfiles.value
        mutableProfiles.value = if (current.any { it.id == profile.id }) {
            current.map { if (it.id == profile.id) profile else it }
        } else {
            current + profile
        }
        activate(profile)
    }

    fun selectProfile(profile: CameraProfile) {
        if (profile.id != mutableActive.value.id) activate(profile)
    }

    fun deleteProfile(profile: CameraProfile) {
        val remaining = mutableProfiles.value.filterNot { it.id == profile.id }
        if (remaining.isEmpty()) return
        mutableProfiles.value = remaining
        if (profile.id == mutableActive.value.id) {
            activate(remaining.first())
        } else {
            store.save(remaining, mutableActive.value.id)
        }
    }

    private fun activate(profile: CameraProfile) {
        mutableActive.value = profile
        store.save(mutableProfiles.value, profile.id)
        setRecording(false)
        mutableTrack.value = null
        mutableTelemetry.value = emptyMap()
        networkBinder.bindToWifi(profile.bindToWifi)
        engine?.configure(profile)
        if (foreground) restartLink()
        AppLog.add("Using profile ${profile.name}: ${profile.rtspUrl}")
    }

    fun setJoystick(pan: Float, tilt: Float) {
        touchPan = pan
        touchTilt = tilt
    }

    fun setZoomRate(rate: Float) {
        touchZoom = rate
    }

    fun setGamepadAxes(pan: Float, tilt: Float, zoom: Float) {
        padPan = pan
        padTilt = tilt
        padZoom = zoom
    }

    fun trackPoint(x: Float, y: Float) = link.send(ControlCommand.TrackPoint(x, y))

    fun trackBox(x: Float, y: Float, width: Float, height: Float) =
        link.send(ControlCommand.TrackBox(x, y, width, height))

    fun trackStop() {
        link.send(ControlCommand.TrackStop)
        mutableTrack.value = null
    }

    fun requestSnapshot() {
        mutableSnapshotRequests.tryEmit(Unit)
    }

    fun saveSnapshot(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { saver.saveSnapshot(bitmap) }
                .onSuccess { showMessage("Snapshot saved: $it") }
                .onFailure { showMessage("Snapshot failed: ${it.message}") }
        }
    }

    fun toggleRecording() = setRecording(!mutableRecording.value)

    fun setRecording(enabled: Boolean) {
        if (enabled == mutableRecording.value) return
        val video = engine ?: return
        mutableRecording.value = enabled
        if (enabled) {
            video.startRecording(saver.recordingDirectory.path)
        } else {
            video.stopRecording()
        }
    }

    fun dismissMessage() {
        mutableMessage.value = null
    }

    private fun onRecordingSegmentDone(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { saver.publishRecording(File(path)) }
                .onSuccess { saved -> if (saved != null) showMessage("Recording saved: $saved") }
                .onFailure { showMessage("Could not save recording: ${it.message}") }
        }
    }

    private fun onRecordingFailed(error: String) {
        mutableRecording.value = false
        showMessage("Recording stopped: $error")
    }

    private fun showMessage(text: String) {
        AppLog.add(text)
        mutableMessage.value = text
    }

    private fun collectLinkEvents() {
        viewModelScope.launch {
            link.events.collect { event ->
                when (event) {
                    is LinkEvent.Track -> {
                        lastTrackAt = System.currentTimeMillis()
                        mutableTrack.value = event
                    }
                    is LinkEvent.Telemetry -> mutableTelemetry.value = mutableTelemetry.value + event.values
                }
            }
        }
    }

    private fun startTrackExpiry() {
        viewModelScope.launch {
            while (isActive) {
                delay(TRACK_EXPIRY_CHECK_MS)
                if (mutableTrack.value != null && System.currentTimeMillis() - lastTrackAt > TRACK_EXPIRY_MS) {
                    mutableTrack.value = null
                }
            }
        }
    }

    /** Sends motion at a fixed rate while held, then repeats the stop because UDP can lose it. */
    private fun startControlLoop() {
        viewModelScope.launch {
            var moving = false
            var zooming = false
            while (isActive) {
                val pan = if (padPan != 0f || padTilt != 0f) padPan else touchPan
                val tilt = if (padPan != 0f || padTilt != 0f) padTilt else touchTilt
                val zoom = if (padZoom != 0f) padZoom else touchZoom

                if (pan != 0f || tilt != 0f) {
                    link.send(ControlCommand.Move(pan, tilt))
                    moving = true
                } else if (moving) {
                    repeat(STOP_REPEATS) { link.send(ControlCommand.Stop) }
                    moving = false
                }

                if (zoom != 0f) {
                    link.send(ControlCommand.Zoom(zoom))
                    zooming = true
                } else if (zooming) {
                    repeat(STOP_REPEATS) { link.send(ControlCommand.Zoom(0f)) }
                    zooming = false
                }

                delay(1000L / mutableActive.value.controlRateHz.coerceIn(1, 100))
            }
        }
    }

    private fun stopMotion() {
        touchPan = 0f
        touchTilt = 0f
        touchZoom = 0f
        padPan = 0f
        padTilt = 0f
        padZoom = 0f
    }

    override fun onCleared() {
        networkBinder.setHighPerformance(false)
        networkBinder.bindToWifi(false)
        engine?.close()
        super.onCleared()
    }

    private companion object {
        const val STOP_REPEATS = 3
        const val STOP_FLUSH_MS = 200L
        const val TRACK_EXPIRY_MS = 1500L
        const val TRACK_EXPIRY_CHECK_MS = 500L
    }
}
