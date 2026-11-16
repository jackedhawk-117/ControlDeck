package com.phonecontroller.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Directional state reported by [VirtualJoystick].
 */
data class JoystickState(
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
    val sprint: Boolean = false,
) {
    val isNeutral: Boolean get() = !up && !down && !left && !right
}

/**
 * High-performance, touch-tracked virtual analog joystick (spec §3, §4 & §5).
 *
 * Translates thumb displacement from center into 8-directional WASD inputs:
 * - Deadzone: <15% radius ignores micro-jitter and centers.
 * - 8-directional sectors: clean diagonal chording (e.g., W+D, S+A).
 * - Outer sprint zone: pushing thumb past 85% forward activates sprint (Shift),
 *   unless disabled per joystick.
 * - Multi-touch safe: tracks a single pointer ID until release; springs back on lift.
 */
@Composable
fun VirtualJoystick(
    size: Dp = 130.dp,
    enabled: Boolean = true,
    sprintEnabled: Boolean = true,
    opacity: Float = 0.85f,
    onStateChange: (JoystickState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val radiusPx = sizePx / 2f
    val knobRadiusPx = radiusPx * 0.38f
    val deadzonePx = radiusPx * 0.18f
    val sprintThresholdPx = radiusPx * 0.85f

    var knobOffsetX by remember { mutableFloatStateOf(0f) }
    var knobOffsetY by remember { mutableFloatStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }

    val currentOnStateChange by rememberUpdatedState(onStateChange)

    val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f * opacity)
    val ringColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isTouching) 0.85f else 0.40f * opacity)
    val knobColor = if (isTouching) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.90f * opacity)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f * opacity)
    }
    val knobBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.90f * opacity)

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(sizePx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val center = Offset(radiusPx, radiusPx)
                        isTouching = true

                        fun updatePosition(pos: Offset) {
                            val dx = pos.x - center.x
                            val dy = pos.y - center.y
                            val dist = hypot(dx, dy)
                            val clampedDist = min(dist, radiusPx)
                            val angle = atan2(dy, dx)

                            if (dist > 0.001f) {
                                knobOffsetX = clampedDist * cos(angle)
                                knobOffsetY = clampedDist * sin(angle)
                            } else {
                                knobOffsetX = 0f
                                knobOffsetY = 0f
                            }

                            if (clampedDist < deadzonePx) {
                                currentOnStateChange(JoystickState())
                            } else {
                                val nx = dx / radiusPx
                                val ny = dy / radiusPx

                                // Direction thresholds: 8-way directional sectors
                                val isUp = ny < -0.32f
                                val isDown = ny > 0.32f
                                val isLeft = nx < -0.32f
                                val isRight = nx > 0.32f
                                val isSprint = sprintEnabled &&
                                    clampedDist >= sprintThresholdPx && ny < -0.65f

                                currentOnStateChange(
                                    JoystickState(
                                        up = isUp,
                                        down = isDown,
                                        left = isLeft,
                                        right = isRight,
                                        sprint = isSprint,
                                    )
                                )
                            }
                        }

                        updatePosition(down.position)

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) break
                                updatePosition(change.position)
                                change.consume()
                            }
                        } finally {
                            isTouching = false
                            knobOffsetX = 0f
                            knobOffsetY = 0f
                            currentOnStateChange(JoystickState())
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(sizePx / 2f, sizePx / 2f)

            // Base circle
            drawCircle(
                color = baseColor,
                radius = radiusPx,
                center = center,
            )

            // Outer ring
            drawCircle(
                color = ringColor,
                radius = radiusPx - 2.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )

            // Sprint indicator ring / deadzone ring
            drawCircle(
                color = ringColor.copy(alpha = 0.20f),
                radius = deadzonePx,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            if (sprintEnabled) {
                drawCircle(
                    color = ringColor.copy(alpha = 0.25f),
                    radius = sprintThresholdPx,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // Cardinal tick marks
            val tickLen = 6.dp.toPx()
            val strokeW = 2.dp.toPx()
            // North (W)
            drawLine(ringColor, Offset(center.x, 4.dp.toPx()), Offset(center.x, 4.dp.toPx() + tickLen), strokeWidth = strokeW)
            // South (S)
            drawLine(ringColor, Offset(center.x, sizePx - 4.dp.toPx()), Offset(center.x, sizePx - 4.dp.toPx() - tickLen), strokeWidth = strokeW)
            // West (A)
            drawLine(ringColor, Offset(4.dp.toPx(), center.y), Offset(4.dp.toPx() + tickLen, center.y), strokeWidth = strokeW)
            // East (D)
            drawLine(ringColor, Offset(sizePx - 4.dp.toPx(), center.y), Offset(sizePx - 4.dp.toPx() - tickLen, center.y), strokeWidth = strokeW)

            // Thumb Knob
            val knobCenter = Offset(center.x + knobOffsetX, center.y + knobOffsetY)
            drawCircle(
                color = knobColor,
                radius = knobRadiusPx,
                center = knobCenter,
            )
            drawCircle(
                color = knobBorderColor,
                radius = knobRadiusPx,
                center = knobCenter,
                style = Stroke(width = 2.5.dp.toPx()),
            )
            // Inner knob dot
            drawCircle(
                color = knobBorderColor,
                radius = 4.dp.toPx(),
                center = knobCenter,
            )
        }
    }
}
