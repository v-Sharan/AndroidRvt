package com.androidrvt.viewer.link

/** Everything the app can ask the camera to do. Coordinates are normalized to 0..1. */
sealed interface ControlCommand {
    /** Pan and tilt rates from -1 to 1. Positive is right and up. */
    data class Move(val pan: Float, val tilt: Float) : ControlCommand

    /** Zoom rate from -1 (out) to 1 (in). */
    data class Zoom(val rate: Float) : ControlCommand
    data object Stop : ControlCommand
    data class TrackPoint(val x: Float, val y: Float) : ControlCommand
    data class TrackBox(val x: Float, val y: Float, val width: Float, val height: Float) : ControlCommand
    data object TrackStop : ControlCommand
    data object Heartbeat : ControlCommand
}

enum class TrackState { IDLE, SEARCHING, LOCKED, LOST }

sealed interface LinkEvent {
    data class Track(
        val state: TrackState,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    ) : LinkEvent

    data class Telemetry(val values: Map<String, String>) : LinkEvent
}

/**
 * Converts between app commands and bytes on the wire.
 * To support a real protocol, implement this interface and use it in MainViewModel.
 */
interface ProtocolCodec {
    val name: String
    fun encode(command: ControlCommand): ByteArray?
    fun decode(data: ByteArray, length: Int): List<LinkEvent>
}
