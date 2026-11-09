package com.androidrvt.viewer.video

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** The on-screen transform applied by digital zoom, around the view's centre. */
data class ViewZoom(val scale: Float = 1f, val pan: Offset = Offset.Zero)

/** The area of the view the picture fills once letterboxed. */
fun fittedVideoRect(view: Size, videoWidth: Int, videoHeight: Int): Rect {
    if (videoWidth <= 0 || videoHeight <= 0 || view.width <= 0f || view.height <= 0f) {
        return Rect(Offset.Zero, view)
    }
    val scale = minOf(view.width / videoWidth, view.height / videoHeight)
    val fitted = Size(videoWidth * scale, videoHeight * scale)
    val left = (view.width - fitted.width) / 2f
    val top = (view.height - fitted.height) / 2f
    return Rect(Offset(left, top), fitted)
}

/** Converts a screen point to 0..1 video coordinates; values outside 0..1 are off the picture. */
fun screenToVideo(point: Offset, view: Size, video: Rect, zoom: ViewZoom): Offset {
    val center = view.center
    val unzoomed = center + (point - center - zoom.pan) / zoom.scale
    return Offset(
        (unzoomed.x - video.left) / video.width,
        (unzoomed.y - video.top) / video.height,
    )
}

fun videoToScreen(point: Offset, view: Size, video: Rect, zoom: ViewZoom): Offset {
    val unzoomed = Offset(video.left + point.x * video.width, video.top + point.y * video.height)
    val center = view.center
    return center + (unzoomed - center) * zoom.scale + zoom.pan
}

/** Keeps the zoomed picture covering the view. */
fun clampZoom(zoom: ViewZoom, view: Size): ViewZoom {
    val scale = zoom.scale.coerceIn(1f, MAX_DIGITAL_ZOOM)
    val maxX = view.width * (scale - 1f) / 2f
    val maxY = view.height * (scale - 1f) / 2f
    return ViewZoom(scale, Offset(zoom.pan.x.coerceIn(-maxX, maxX), zoom.pan.y.coerceIn(-maxY, maxY)))
}

private const val MAX_DIGITAL_ZOOM = 8f
private val Size.center: Offset get() = Offset(width / 2f, height / 2f)
