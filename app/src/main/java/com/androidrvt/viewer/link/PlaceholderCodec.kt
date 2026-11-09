package com.androidrvt.viewer.link

import java.util.Locale

/**
 * Temporary text protocol for testing with tools/udp_simulator.py.
 * It is not the real camera protocol; replace it once that specification is available.
 *
 * Sent:     HB,<seq>  MOVE,<pan>,<tilt>  ZOOM,<rate>  STOP
 *           TRACK_POINT,<x>,<y>  TRACK_BOX,<x>,<y>,<w>,<h>  TRACK_STOP
 * Received: TRACK,<IDLE|SEARCHING|LOCKED|LOST>,<x>,<y>,<w>,<h>
 *           TEL,<key>=<value>,<key>=<value>
 */
class PlaceholderCodec : ProtocolCodec {
    override val name = "Placeholder text protocol"
    private var heartbeatSequence = 0L

    override fun encode(command: ControlCommand): ByteArray {
        val text = when (command) {
            is ControlCommand.Move -> "MOVE,${format(command.pan)},${format(command.tilt)}"
            is ControlCommand.Zoom -> "ZOOM,${format(command.rate)}"
            ControlCommand.Stop -> "STOP"
            is ControlCommand.TrackPoint -> "TRACK_POINT,${format(command.x)},${format(command.y)}"
            is ControlCommand.TrackBox -> "TRACK_BOX,${format(command.x)},${format(command.y)}," +
                "${format(command.width)},${format(command.height)}"
            ControlCommand.TrackStop -> "TRACK_STOP"
            ControlCommand.Heartbeat -> "HB,${heartbeatSequence++}"
        }
        return text.toByteArray(Charsets.US_ASCII)
    }

    override fun decode(data: ByteArray, length: Int): List<LinkEvent> {
        val parts = String(data, 0, length, Charsets.US_ASCII).trim().split(',')
        return when (parts.firstOrNull()) {
            "TRACK" -> listOfNotNull(parseTrack(parts))
            "TEL" -> listOf(LinkEvent.Telemetry(parseTelemetry(parts)))
            else -> emptyList()
        }
    }

    private fun parseTrack(parts: List<String>): LinkEvent.Track? {
        if (parts.size != 6) return null
        val state = runCatching { TrackState.valueOf(parts[1]) }.getOrNull() ?: return null
        val x = parts[2].toFloatOrNull() ?: return null
        val y = parts[3].toFloatOrNull() ?: return null
        val width = parts[4].toFloatOrNull() ?: return null
        val height = parts[5].toFloatOrNull() ?: return null
        return LinkEvent.Track(state, x, y, width, height)
    }

    private fun parseTelemetry(parts: List<String>): Map<String, String> =
        parts.drop(1).mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator > 0) field.substring(0, separator) to field.substring(separator + 1) else null
        }.toMap()

    private fun format(value: Float) = String.format(Locale.US, "%.3f", value)
}
