package com.androidrvt.viewer.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.androidrvt.viewer.MainViewModel
import com.androidrvt.viewer.link.LinkEvent
import com.androidrvt.viewer.link.TrackState
import com.androidrvt.viewer.link.UdpLink
import com.androidrvt.viewer.video.Phase
import com.androidrvt.viewer.video.VideoEngine
import com.androidrvt.viewer.video.VideoStats
import com.androidrvt.viewer.video.VideoStatus
import com.androidrvt.viewer.video.ViewZoom
import com.androidrvt.viewer.video.clampZoom
import com.androidrvt.viewer.video.fittedVideoRect
import com.androidrvt.viewer.video.screenToVideo
import com.androidrvt.viewer.video.videoToScreen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

private const val MIN_BOX_FRACTION = 0.01f
private const val MESSAGE_DURATION_MS = 4000L

@Composable
fun LiveScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit, onOpenLog: () -> Unit) {
    val status by viewModel.videoStatus.collectAsState()
    val stats by viewModel.videoStats.collectAsState()
    val reconnects by viewModel.reconnects.collectAsState()
    val linkStatus by viewModel.link.status.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val track by viewModel.track.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val message by viewModel.message.collectAsState()
    val profile by viewModel.activeProfile.collectAsState()

    var viewSize by remember { mutableStateOf(Size.Zero) }
    var zoom by remember { mutableStateOf(ViewZoom()) }
    var trackMode by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf(Offset.Zero) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    val videoRect by rememberUpdatedState(fittedVideoRect(viewSize, stats.width, stats.height))

    LaunchedEffect(Unit) {
        viewModel.snapshotRequests.collect {
            val view = textureView ?: return@collect
            val frame = view.getBitmap() ?: return@collect
            viewModel.saveSnapshot(if (zoom.scale == 1f) cropToVideo(frame, videoRect) else frame)
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_DURATION_MS)
            viewModel.dismissMessage()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewSize = Size(it.width.toFloat(), it.height.toFloat()) },
    ) {
        viewModel.engine?.let { engine ->
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.pan.x
                        translationY = zoom.pan.y
                    },
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = EngineSurfaceListener(engine)
                        textureView = this
                    }
                },
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(trackMode) {
                    if (trackMode) {
                        detectTapGestures(
                            onTap = { point ->
                                val target = screenToVideo(point, viewSize, videoRect, zoom)
                                if (target.x in 0f..1f && target.y in 0f..1f) {
                                    viewModel.trackPoint(target.x, target.y)
                                }
                            },
                        )
                    } else {
                        detectTapGestures(onDoubleTap = { zoom = ViewZoom() })
                    }
                }
                .pointerInput(trackMode) {
                    if (trackMode) {
                        detectDragGestures(
                            onDragStart = {
                                dragStart = it
                                dragEnd = it
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragEnd = change.position
                            },
                            onDragEnd = {
                                dragStart?.let { start -> sendTrackBox(viewModel, start, dragEnd, viewSize, videoRect, zoom) }
                                dragStart = null
                            },
                            onDragCancel = { dragStart = null },
                        )
                    } else {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = clampZoom(ViewZoom(zoom.scale * gestureZoom, zoom.pan + pan), viewSize)
                        }
                    }
                },
        )

        TrackOverlay(track, viewSize, videoRect, zoom, dragStart, dragEnd)

        ConnectionBanner(status, Modifier.align(Alignment.Center))

        StatusBar(
            profileName = profile.name,
            status = status,
            stats = stats,
            reconnects = reconnects,
            linkStatus = linkStatus,
            recording = recording,
            zoomScale = zoom.scale,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PillButton(if (controlsVisible) "Hide" else "Show") { controlsVisible = !controlsVisible }
            PillButton("Log", onClick = onOpenLog)
            PillButton("Settings", onClick = onOpenSettings)
        }

        if (telemetry.isNotEmpty()) {
            TelemetryPanel(telemetry, Modifier.align(Alignment.CenterStart).padding(8.dp))
        }

        if (controlsVisible) {
            Joystick(
                diameter = 150.dp,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                onChange = viewModel::setJoystick,
            )
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HoldButton("Zoom -") { held -> viewModel.setZoomRate(if (held) -1f else 0f) }
                    HoldButton("Zoom +") { held -> viewModel.setZoomRate(if (held) 1f else 0f) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillButton("Track", active = trackMode) { trackMode = !trackMode }
                    PillButton("Stop track", onClick = viewModel::trackStop)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillButton("Snapshot", onClick = viewModel::requestSnapshot)
                    PillButton(
                        text = if (recording) "Stop rec" else "Record",
                        active = recording,
                        activeColor = AlertColor,
                        onClick = viewModel::toggleRecording,
                    )
                    PillButton("Reconnect", onClick = { viewModel.engine?.reconnectNow() })
                }
            }
        }

        message?.let { text ->
            Text(
                text = text,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PanelColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private fun sendTrackBox(
    viewModel: MainViewModel,
    start: Offset,
    end: Offset,
    viewSize: Size,
    video: Rect,
    zoom: ViewZoom,
) {
    val a = screenToVideo(start, viewSize, video, zoom)
    val b = screenToVideo(end, viewSize, video, zoom)
    val left = min(a.x, b.x).coerceIn(0f, 1f)
    val top = min(a.y, b.y).coerceIn(0f, 1f)
    val right = max(a.x, b.x).coerceIn(0f, 1f)
    val bottom = max(a.y, b.y).coerceIn(0f, 1f)
    if (abs(right - left) > MIN_BOX_FRACTION && abs(bottom - top) > MIN_BOX_FRACTION) {
        viewModel.trackBox(left, top, right - left, bottom - top)
    }
}

private fun cropToVideo(frame: Bitmap, video: Rect): Bitmap {
    val left = video.left.toInt().coerceIn(0, frame.width - 1)
    val top = video.top.toInt().coerceIn(0, frame.height - 1)
    val width = video.width.toInt().coerceIn(1, frame.width - left)
    val height = video.height.toInt().coerceIn(1, frame.height - top)
    return Bitmap.createBitmap(frame, left, top, width, height)
}

private class EngineSurfaceListener(private val engine: VideoEngine) : TextureView.SurfaceTextureListener {
    private var surface: Surface? = null

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(texture).also(engine::setSurface)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        surface?.let(engine::setSurface)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        engine.setSurface(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}

@Composable
private fun TrackOverlay(
    track: LinkEvent.Track?,
    viewSize: Size,
    video: Rect,
    zoom: ViewZoom,
    dragStart: Offset?,
    dragEnd: Offset,
) {
    Canvas(Modifier.fillMaxSize()) {
        if (track != null && track.state != TrackState.IDLE) {
            val topLeft = videoToScreen(Offset(track.x, track.y), viewSize, video, zoom)
            val bottomRight = videoToScreen(
                Offset(track.x + track.width, track.y + track.height),
                viewSize,
                video,
                zoom,
            )
            val color = when (track.state) {
                TrackState.LOCKED -> Color.Green
                TrackState.SEARCHING -> Color.Yellow
                else -> AlertColor
            }
            drawRect(
                color = color,
                topLeft = topLeft,
                size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        if (dragStart != null) {
            drawRect(
                color = AccentColor,
                topLeft = Offset(min(dragStart.x, dragEnd.x), min(dragStart.y, dragEnd.y)),
                size = Size(abs(dragEnd.x - dragStart.x), abs(dragEnd.y - dragStart.y)),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun ConnectionBanner(status: VideoStatus, modifier: Modifier) {
    val text = when (status.phase) {
        Phase.PLAYING -> return
        Phase.CONNECTING -> "Connecting..."
        Phase.RECONNECTING -> if (status.attempt > 1) "Reconnecting... attempt ${status.attempt}" else "Reconnecting..."
        Phase.STOPPED -> if (status.detail.isNotEmpty()) "Video unavailable" else return
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PanelColor)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White, fontSize = 18.sp)
        if (status.detail.isNotEmpty()) {
            Text(status.detail, color = Color.LightGray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatusBar(
    profileName: String,
    status: VideoStatus,
    stats: VideoStats,
    reconnects: Int,
    linkStatus: UdpLink.Status,
    recording: Boolean,
    zoomScale: Float,
    modifier: Modifier,
) {
    val (videoLabel, videoColor) = when (status.phase) {
        Phase.PLAYING -> "LIVE" to Color.Green
        Phase.CONNECTING -> "CONNECTING" to Color.Yellow
        Phase.RECONNECTING -> "RECONNECTING" to AlertColor
        Phase.STOPPED -> "STOPPED" to Color.LightGray
    }
    val (linkLabel, linkColor) = when {
        !linkStatus.enabled -> "UDP off" to Color.LightGray
        linkStatus.error != null -> "UDP error" to AlertColor
        linkStatus.peerAlive -> "UDP linked" to Color.Green
        else -> "UDP no reply" to Color.Yellow
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PanelColor)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(videoLabel, color = videoColor, fontSize = 12.sp)
            Text(linkLabel, color = linkColor, fontSize = 12.sp)
            if (recording) Text("REC", color = AlertColor, fontSize = 12.sp)
            Text(profileName, color = Color.White, fontSize = 12.sp)
        }
        Text(
            text = buildString {
                append("%.0f fps  %.2f Mbps".format(stats.fps, stats.kilobitsPerSecond / 1000.0))
                if (stats.width > 0) append("  ${stats.width}x${stats.height}")
                if (stats.decoder.isNotEmpty()) append("  ${stats.decoder}")
                append("  reconnects $reconnects")
                if (zoomScale > 1f) append("  zoom %.1fx".format(zoomScale))
                if (linkStatus.enabled) append("  tx ${linkStatus.txPackets} rx ${linkStatus.rxPackets}")
            },
            color = Color.LightGray,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TelemetryPanel(values: Map<String, String>, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PanelColor)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        values.entries.sortedBy { it.key }.forEach { (key, value) ->
            Text("$key: $value", color = Color.White, fontSize = 11.sp)
        }
    }
}
