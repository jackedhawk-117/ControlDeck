package com.phonecontroller.app.input

import kotlin.math.abs
import kotlin.math.min

/**
 * Touchpad camera response curve (docs/TOUCHPAD.md).
 *
 * Pipeline per pointer sample:
 * 1. convert pixel movement to velocity (px/ms),
 * 2. optional exponential smoothing of velocity,
 * 3. optional speed-based acceleration,
 * 4. sensitivity and axis inversion,
 * 5. Q16.16 encoding (65536 = one mouse pixel).
 *
 * Fractional pixels are preserved on the wire and resolved by the receiver's
 * accumulator, so slow movements never round down to zero.
 */
data class TouchpadConfig(
    val sensitivityX: Float = 0.80f,
    val sensitivityY: Float = 0.80f,
    /** 0 = off; higher values accelerate fast swipes more aggressively. */
    val acceleration: Float = 0.15f,
    /** 0 = raw; 0.9 = very smooth/laggy. */
    val smoothing: Float = 0.35f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    /** Fraction of gameplay width reserved for the touchpad (0.35–0.75). */
    val widthFraction: Float = 0.52f,
    val tapToClick: Boolean = false,
) {
    fun sanitized(): TouchpadConfig = copy(
        sensitivityX = finiteOr(sensitivityX, 0.80f).coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY),
        sensitivityY = finiteOr(sensitivityY, 0.80f).coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY),
        acceleration = finiteOr(acceleration, 0.15f).coerceIn(0f, 2f),
        smoothing = finiteOr(smoothing, 0.35f).coerceIn(0f, 0.9f),
        widthFraction = finiteOr(widthFraction, 0.52f).coerceIn(MIN_WIDTH, MAX_WIDTH),
    )

    private fun finiteOr(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    companion object {
        const val MIN_SENSITIVITY = 0.2f
        const val MAX_SENSITIVITY = 3.0f
        const val MIN_WIDTH = 0.35f
        const val MAX_WIDTH = 0.75f

        /** Speed (px/ms) at which acceleration reaches half its extra gain. */
        internal const val ACCEL_REFERENCE_SPEED = 1.5f
        internal const val MAX_ACCEL_GAIN = 2.0f
        internal const val MAX_STEP_Q16 = 400f * 65536f
    }
}

/** A Q16.16 mouse movement produced from one or more pointer samples. */
data class Motion(val dxQ16: Int, val dyQ16: Int) {
    operator fun plus(other: Motion) = Motion(dxQ16 + other.dxQ16, dyQ16 + other.dyQ16)
    fun isZero(): Boolean = dxQ16 == 0 && dyQ16 == 0
}

/**
 * Stateful mapper for a single touchpad gesture. Create one per gesture so
 * smoothing starts from rest and does not leak between fingers.
 */
class TouchpadMapper(rawConfig: TouchpadConfig) {

    private val config = rawConfig.sanitized()
    private var smoothedVx = 0f
    private var smoothedVy = 0f

    /**
     * Feed one pointer movement. [dx]/[dy] are screen pixels and [dtMs] the
     * time since the previous sample (clamped to 1..50 ms).
     */
    fun onMove(dx: Float, dy: Float, dtMs: Long): Motion {
        if (dx == 0f && dy == 0f) return Motion(0, 0)
        val dt = dtMs.coerceIn(1L, 50L).toFloat()
        var vx = dx / dt
        var vy = dy / dt

        if (config.smoothing > 0f) {
            val alpha = (1f - config.smoothing).coerceIn(0.05f, 1f)
            smoothedVx += alpha * (vx - smoothedVx)
            smoothedVy += alpha * (vy - smoothedVy)
            vx = smoothedVx
            vy = smoothedVy
        }

        val speed = kotlin.math.sqrt(vx * vx + vy * vy)
        val gain = if (config.acceleration > 0f) {
            1f + config.acceleration * min(speed / TouchpadConfig.ACCEL_REFERENCE_SPEED, TouchpadConfig.MAX_ACCEL_GAIN)
        } else {
            1f
        }

        // Velocity (px/ms) * dt => pixels, then sensitivity + inversion.
        var outX = vx * dt * gain * config.sensitivityX
        var outY = vy * dt * gain * config.sensitivityY
        if (config.invertX) outX = -outX
        if (config.invertY) outY = -outY

        return Motion(
            dxQ16 = clampQ16(outX),
            dyQ16 = clampQ16(outY),
        )
    }

    private fun clampQ16(value: Float): Int {
        val scaled = value * 65536f
        return when {
            scaled >= TouchpadConfig.MAX_STEP_Q16 -> TouchpadConfig.MAX_STEP_Q16.toInt()
            scaled <= -TouchpadConfig.MAX_STEP_Q16 -> (-TouchpadConfig.MAX_STEP_Q16).toInt()
            else -> Math.round(scaled)
        }
    }
}

/**
 * Conflates touchpad samples until the ~8 ms sender tick drains them. This is
 * what turns per-frame pointer callbacks into a stable ~120 Hz UDP rate
 * without queueing unbounded work if the network stalls.
 *
 * Synchronized because pointer callbacks and the sender coroutine may run on
 * different dispatchers.
 */
class MotionAccumulator {

    private var dx = 0L
    private var dy = 0L

    @Synchronized
    fun add(motion: Motion) {
        dx = (dx + motion.dxQ16).coerceIn(-MAX, MAX)
        dy = (dy + motion.dyQ16).coerceIn(-MAX, MAX)
    }

    /** Returns the accumulated movement and clears it, or null if empty. */
    @Synchronized
    fun drain(): Motion? {
        if (dx == 0L && dy == 0L) return null
        // Q16.16 integers fit comfortably in Int at this clamp.
        val out = Motion(dx.toInt(), dy.toInt())
        dx = 0
        dy = 0
        return out
    }

    @Synchronized
    fun clear() {
        dx = 0
        dy = 0
    }

    private companion object {
        /** ±4096 device pixels per tick: far beyond any real swipe. */
        const val MAX = 4096L * 65536L
    }
}

/** Absolute magnitude helper used by tests and diagnostics. */
internal fun Motion.magnitudeQ16(): Int = abs(dxQ16) + abs(dyQ16)
