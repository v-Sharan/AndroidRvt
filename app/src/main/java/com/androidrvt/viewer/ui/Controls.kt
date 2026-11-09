package com.androidrvt.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val PanelColor = Color(0xA0101418)
val AccentColor = Color(0xFF4FC3F7)
val AlertColor = Color(0xFFEF5350)

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = AccentColor,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor else PanelColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (active) Color.Black else Color.White, fontSize = 13.sp)
    }
}

/** A button that reports press and release, for controls that act while held. */
@Composable
fun HoldButton(text: String, modifier: Modifier = Modifier, onHoldChanged: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    val callback by rememberUpdatedState(onHoldChanged)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (held) AccentColor else PanelColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    held = true
                    callback(true)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                    }
                    held = false
                    callback(false)
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (held) Color.Black else Color.White, fontSize = 13.sp)
    }
}

/** Reports -1..1 on both axes, with up as positive, and returns to centre on release. */
@Composable
fun Joystick(diameter: Dp, modifier: Modifier = Modifier, onChange: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val callback by rememberUpdatedState(onChange)

    Canvas(
        modifier = modifier
            .size(diameter)
            .pointerInput(Unit) {
                val radius = size.width / 2f
                val center = Offset(radius, radius)

                fun update(position: Offset) {
                    var delta = position - center
                    val distance = delta.getDistance()
                    if (distance > radius) delta = delta * (radius / distance)
                    knob = delta
                    callback(delta.x / radius, -delta.y / radius)
                }

                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    update(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        update(change.position)
                    }
                    knob = Offset.Zero
                    callback(0f, 0f)
                }
            },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(PanelColor, radius)
        drawCircle(Color.White.copy(alpha = 0.5f), radius, style = Stroke(width = 2.dp.toPx()))
        drawCircle(AccentColor, radius * 0.32f, center + knob)
    }
}
