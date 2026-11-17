package com.phonecontroller.app.input

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How gyro aiming is armed (docs/GYRO-AIMING.md §5). */
enum class GyroActivationMode {
    /** Contributes while a session is active and this profile is in gameplay. */
    ALWAYS,

    /** Active only while a GYRO activation cell is held. */
    HOLD,

    /** A GYRO activation cell latches gyro aiming on or off. */
    TOGGLE,

    /** Active only while at least one finger is inside the configured region. */
    REGION,
}

/**
 * Which sensor drives the fine-aim path.
 *
 * - [FUSED] uses the rotation-vector orientation deltas (drift-free, sensor-rate).
 * - [GYRO] integrates the calibrated gyroscope at the input tick rate (smoother
 *   at low rotation-vector rates, but it drifts until the next recenter).
 *
 * Both need an orientation sensor for a stable yaw/pitch reference; devices
 * without one show gyro as unavailable instead of guessing from accelerometer.
 */
enum class GyroSensorSource {
    FUSED,
    GYRO,
}

/** A special cell action: arm/disarm gyro aiming, or reset the neutral pose. */
enum class GyroCellAction {
    ACTIVATE,
    RECENTER,
}

/**
 * Per-profile gyro aiming configuration (docs/GYRO-AIMING.md §6).
 *
 * Sensitivities are pixels per degree, applied after the orientation math, so
 * the same value feels identical across phones. The ADS multiplier is applied
 * while mouse button 2 (right button / aim) is held.
 */
data class GyroConfig(
    val enabled: Boolean = false,
    val activationMode: GyroActivationMode = GyroActivationMode.HOLD,
    val sensorSource: GyroSensorSource = GyroSensorSource.FUSED,
    /** Pixels of mouse movement per degree of yaw. */
    val sensitivityX: Float = 3.0f,
    /** Pixels of mouse movement per degree of pitch. */
    val sensitivityY: Float = 3.0f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val enableYaw: Boolean = true,
    val enablePitch: Boolean = true,
    /** Angular-rate deadzone in degrees/second; suppresses hand tremor. */
    val deadzoneDps: Float = 1.0f,
    /** 0 = raw; 0.9 = heavy low-pass. The filter preserves total motion. */
    val smoothing: Float = 0.20f,
    /** Hard per-tick clamp so a sensor spike cannot fling the camera. */
    val maxPixelsPerTick: Float = 200f,
    /** Sensitivity multiplier while RMB/ADS is held (0.1–2.0). */
    val adsMultiplier: Float = 0.65f,
    /** Re-prime the neutral pose when a HOLD activation ends. */
    val recenterOnRelease: Boolean = true,
    /** Calibration mean from a previous session, in rad/s (device axes). */
    val biasX: Float = 0f,
    val biasY: Float = 0f,
    val biasZ: Float = 0f,
    // Region activation rectangle, fractions of the gameplay canvas.
    val regionXPct: Float = 0.30f,
    val regionYPct: Float = 0.30f,
    val regionWidthPct: Float = 0.40f,
    val regionHeightPct: Float = 0.40f,
) {
    fun sanitized(): GyroConfig {
        val x = finiteOr(regionXPct, 0.30f).coerceIn(0f, 0.90f)
        val y = finiteOr(regionYPct, 0.30f).coerceIn(0f, 0.90f)
        val maxWidth = (1f - x).coerceAtLeast(MIN_REGION)
        val maxHeight = (1f - y).coerceAtLeast(MIN_REGION)
        return copy(
            sensitivityX = finiteOr(sensitivityX, 3.0f)
                .coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY),
            sensitivityY = finiteOr(sensitivityY, 3.0f)
                .coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY),
            deadzoneDps = finiteOr(deadzoneDps, 1.0f).coerceIn(0f, MAX_DEADZONE_DPS),
            smoothing = finiteOr(smoothing, 0.20f).coerceIn(0f, 0.90f),
            maxPixelsPerTick = finiteOr(maxPixelsPerTick, 200f)
                .coerceIn(MIN_PIXELS_PER_TICK, MAX_PIXELS_PER_TICK),
            adsMultiplier = finiteOr(adsMultiplier, 0.65f)
                .coerceIn(MIN_ADS_MULTIPLIER, MAX_ADS_MULTIPLIER),
            biasX = finiteOr(biasX, 0f).coerceIn(-MAX_BIAS, MAX_BIAS),
            biasY = finiteOr(biasY, 0f).coerceIn(-MAX_BIAS, MAX_BIAS),
            biasZ = finiteOr(biasZ, 0f).coerceIn(-MAX_BIAS, MAX_BIAS),
            regionXPct = x,
            regionYPct = y,
            regionWidthPct = finiteOr(regionWidthPct, 0.40f)
                .coerceIn(MIN_REGION, maxWidth),
            regionHeightPct = finiteOr(regionHeightPct, 0.40f)
                .coerceIn(MIN_REGION, maxHeight),
        )
    }

    /** Hit test for [GyroActivationMode.REGION] using canvas fractions. */
    fun containsRegion(xFraction: Float, yFraction: Float): Boolean =
        xFraction >= regionXPct &&
            xFraction <= regionXPct + regionWidthPct &&
            yFraction >= regionYPct &&
            yFraction <= regionYPct + regionHeightPct

    private fun finiteOr(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    companion object {
        const val MIN_SENSITIVITY = 0.25f
        const val MAX_SENSITIVITY = 20f
        const val MAX_DEADZONE_DPS = 10f
        const val MIN_PIXELS_PER_TICK = 10f
        const val MAX_PIXELS_PER_TICK = 600f
        const val MIN_ADS_MULTIPLIER = 0.1f
        const val MAX_ADS_MULTIPLIER = 2.0f
        const val MIN_REGION = 0.10f
        const val MAX_BIAS = 1.0f

        /** Smoothing slider 0.9 maps to this time constant. */
        internal const val SMOOTHING_TAU_SECONDS = 0.08f
        internal const val CALIBRATION_MS = 1_000L
        internal const val CALIBRATION_MAX_STDDEV = 0.35f
        /** Below this horizontal component the heading is ill-defined. */
        internal const val POLE_HORIZONTAL = 0.08f
    }
}

/** Outcome of an explicit calibration pass. */
data class GyroCalibrationResult(
    val biasX: Float,
    val biasY: Float,
    val biasZ: Float,
    val accepted: Boolean,
)

/**
 * Unit quaternion in (w, x, y, z) order. Android's rotation-vector sensor emits
 * (x, y, z, w); [fromRotationVector] reorders it.
 */
internal data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-6f || n.isNaN()) return IDENTITY
        return Quat(w / n, x / n, y / n, z / n)
    }

    /** Hamilton product; `this * other` applies [other] first. */
    operator fun times(other: Quat): Quat = Quat(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun inverse(): Quat = Quat(w, -x, -y, -z)

    /** Rotates a device-frame vector into world coordinates. */
    fun rotate(vx: Float, vy: Float, vz: Float): FloatArray {
        val out = FloatArray(3)
        rotateInto(vx, vy, vz, out)
        return out
    }

    /** Allocation-free variant of [rotate] for the sensor hot path. */
    fun rotateInto(vx: Float, vy: Float, vz: Float, out: FloatArray) {
        // v' = v + 2w(qv × v) + 2 qv × (qv × v)
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)
        out[0] = vx + w * tx + (y * tz - z * ty)
        out[1] = vy + w * ty + (z * tx - x * tz)
        out[2] = vz + w * tz + (x * ty - y * tx)
    }

    /** Device +Z axis (the direction the screen faces) in world coordinates. */
    fun screenNormal(): FloatArray = rotate(0f, 0f, 1f)

    /** Allocation-free [screenNormal]. */
    fun screenNormalInto(out: FloatArray) = rotateInto(0f, 0f, 1f, out)

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        /** [values] is Android's (x, y, z, w) rotation-vector layout. */
        fun fromRotationVector(values: FloatArray): Quat {
            val q = Quat(values[3], values[0], values[1], values[2])
            return q.normalized()
        }
    }
}

/** Wraps an angle to (-π, π]. */
internal fun wrapPi(angle: Float): Float {
    var a = angle
    val twoPi = (2.0 * Math.PI).toFloat()
    while (a <= -Math.PI.toFloat()) a += twoPi
    while (a > Math.PI.toFloat()) a -= twoPi
    return a
}

/**
 * Converts rotation-vector deltas or calibrated gyro rates into the same
 * Q16.16 mouse movement format used by the touchpad.
 *
 * The mapper is deliberately pure Kotlin (no Android imports) so the yaw/pitch
 * math, recentering, deadzone, inversion and ADS scaling are JVM-testable.
 * Feed it from a single thread; [MotionAccumulator] handles cross-thread
 * conflation with the sender.
 */
class GyroMapper(initialConfig: GyroConfig = GyroConfig()) {

    private var config = initialConfig.sanitized()

    private var quat = Quat.IDENTITY
    private var haveQuat = false
    private var lastQuatTs = 0L
    private var lastProcessedQuatTs = 0L

    private var active = false
    private var haveAngles = false
    private var lastHeading = 0f
    private var lastPitch = 0f

    private var lastTickNs = 0L
    private var lastGyroSampleTs = 0L
    private var smoothedYawRate = 0f
    private var smoothedPitchRate = 0f
    private val normal = FloatArray(3)
    private val worldRate = FloatArray(3)

    private val bias = floatArrayOf(config.biasX, config.biasY, config.biasZ)
    private var lastAutoBiasTs = 0L

    private var calibrationActive = false
    private var calibrationStartNs = 0L
    private var lastCalibrationSampleTs = 0L
    private var calibrationSamples = 0
    private var calSumX = 0.0
    private var calSumY = 0.0
    private var calSumZ = 0.0
    private var calSumSq = 0.0
    private var calibrationResult: GyroCalibrationResult? = null

    val isCalibrating: Boolean get() = calibrationActive

    /** Applies sanitized settings. Explicit bias edits replace the runtime bias. */
    fun setConfig(next: GyroConfig) {
        val clean = next.sanitized()
        val biasChanged = clean.biasX != config.biasX ||
            clean.biasY != config.biasY ||
            clean.biasZ != config.biasZ
        val sourceChanged = clean.sensorSource != config.sensorSource
        config = clean
        if (biasChanged) {
            bias[0] = clean.biasX
            bias[1] = clean.biasY
            bias[2] = clean.biasZ
        }
        if (sourceChanged) {
            resetMotionState()
        }
    }

    /** Arms or disarms the mapper. Transitions never emit a jump. */
    fun setActive(next: Boolean) {
        if (next == active) return
        active = next
        if (config.recenterOnRelease) {
            // Deactivation forgets the neutral pose, so the next activation
            // primes against wherever the phone is then.
            resetMotionState()
        } else {
            // Keep the orientation baseline across disarm/rearm; the camera
            // reflects the pose change when aiming resumes. Raw-gyro
            // integration still starts from a fresh sample.
            smoothedYawRate = 0f
            smoothedPitchRate = 0f
            lastGyroSampleTs = 0L
        }
        lastTickNs = 0L
    }

    /**
     * Establish a new neutral pose. The next sensor sample becomes the
     * baseline, so calling this while aiming does not produce a delta.
     */
    fun recenter() {
        haveAngles = false
        lastProcessedQuatTs = 0L
        resetMotionState()
        // Also re-prime the raw-gyro integration; otherwise the tick after
        // recentering would still emit the accumulated one-tick delta.
        lastTickNs = 0L
    }

    /** Begin a 1 s explicit calibration pass; the phone must stay still. */
    fun beginCalibration(nowNs: Long) {
        calibrationActive = true
        calibrationStartNs = nowNs
        lastCalibrationSampleTs = 0L
        calibrationSamples = 0
        calSumX = 0.0
        calSumY = 0.0
        calSumZ = 0.0
        calSumSq = 0.0
        calibrationResult = null
    }

    /** Abandon an in-flight calibration (disconnect, disable, profile switch). */
    fun cancelCalibration() {
        if (!calibrationActive) return
        calibrationActive = false
        calibrationSamples = 0
        calibrationResult = null
        lastTickNs = 0L
        resetMotionState()
    }

    /** Returns the completed calibration exactly once. */
    fun takeCalibrationResult(): GyroCalibrationResult? =
        calibrationResult.also { calibrationResult = null }

    /** Current runtime bias (auto-calibration may have refined it). */
    fun biasSnapshot(): FloatArray = bias.copyOf()

    /**
     * Feed the latest sensor mailbox values and return the movement since the
     * previous call. [gyro] is device-frame rad/s; [quaternion] is Android's
     * (x, y, z, w) order. Pass a timestamp of 0 for an unavailable source.
     */
    fun update(
        gyro: FloatArray,
        gyroTsNs: Long,
        quaternion: FloatArray,
        quatTsNs: Long,
        nowNs: Long,
        adsActive: Boolean,
    ): Motion {
        val newQuat = quatTsNs > 0 && quatTsNs != lastQuatTs
        if (newQuat) {
            lastQuatTs = quatTsNs
            quat = Quat.fromRotationVector(quaternion)
            haveQuat = true
        }

        if (calibrationActive) {
            collectCalibration(gyro, gyroTsNs, nowNs)
            return Motion(0, 0)
        }

        if (gyroTsNs > 0) updateAutoBias(gyro, gyroTsNs)

        if (!config.enabled || !active || !haveQuat) return Motion(0, 0)

        return when (config.sensorSource) {
            GyroSensorSource.FUSED -> updateFused(newQuat, adsActive)
            GyroSensorSource.GYRO -> updateGyro(gyro, gyroTsNs, adsActive)
        }
    }

    private fun updateFused(newQuat: Boolean, adsActive: Boolean): Motion {
        if (!newQuat) return Motion(0, 0)
        return orientationDelta(adsActive)
    }

    private fun orientationDelta(adsActive: Boolean): Motion {
        quat.screenNormalInto(normal)
        val horizontal = hypot(normal[0], normal[1])
        val heading = atan2(normal[0], normal[1])
        val pitch = asin(normal[2].coerceIn(-1f, 1f))

        if (!haveAngles) {
            lastHeading = heading
            lastPitch = pitch
            haveAngles = true
            lastProcessedQuatTs = lastQuatTs
            return Motion(0, 0)
        }

        var deltaHeading = wrapPi(heading - lastHeading)
        val deltaPitch = pitch - lastPitch
        // Straight up/down makes heading undefined; do not emit yaw noise there.
        if (horizontal < GyroConfig.POLE_HORIZONTAL) deltaHeading = 0f
        lastHeading = heading
        lastPitch = pitch

        val dt = ((lastQuatTs - lastProcessedQuatTs) / 1_000_000_000.0)
            .toFloat()
            .coerceIn(0.002f, 0.2f)
        lastProcessedQuatTs = lastQuatTs

        val yawRate = Math.toDegrees(deltaHeading.toDouble()).toFloat() / dt
        val pitchRate = Math.toDegrees(deltaPitch.toDouble()).toFloat() / dt
        return ratesToMotion(yawRate, pitchRate, dt, adsActive)
    }

    private fun updateGyro(
        gyro: FloatArray,
        gyroTsNs: Long,
        adsActive: Boolean,
    ): Motion {
        // Never integrate the same sensor sample twice: the mailbox keeps the
        // last value, so a stalled sensor would otherwise drift forever.
        if (gyroTsNs <= 0 || gyroTsNs == lastGyroSampleTs) return Motion(0, 0)
        if (lastTickNs != 0L && gyroTsNs <= lastTickNs) return Motion(0, 0)
        lastGyroSampleTs = gyroTsNs

        // Sensor timestamps avoid jitter from the coroutine's wake-up time.
        val dt = if (lastTickNs == 0L) {
            0f
        } else {
            ((gyroTsNs - lastTickNs) / 1_000_000_000.0)
                .toFloat()
                .coerceIn(0.001f, 0.05f)
        }
        lastTickNs = gyroTsNs
        if (dt <= 0f) return Motion(0, 0)

        val wxDevice = gyro[0] - bias[0]
        val wyDevice = gyro[1] - bias[1]
        val wzDevice = gyro[2] - bias[2]
        quat.rotateInto(wxDevice, wyDevice, wzDevice, worldRate)
        val wx = worldRate[0]
        val wy = worldRate[1]
        val wz = worldRate[2]
        quat.screenNormalInto(normal)
        val d0 = normal[0]
        val d1 = normal[1]
        val d2 = normal[2]
        val h2 = d0 * d0 + d1 * d1
        val horizontal = sqrt(h2)

        // Heading rate of the screen-normal ray. For a horizontal ray this is
        // -ωz; the extra term accounts for tilting a slanted ray.
        val yawRateRad = if (h2 >= GyroConfig.POLE_HORIZONTAL * GyroConfig.POLE_HORIZONTAL) {
            (d2 * (wx * d0 + wy * d1)) / h2 - wz
        } else {
            -wz
        }
        // d(d.z)/dt = (ω × d).z, and d.z = sin(pitch), so
        // pitchRate = (ω × d).z / cos(pitch).
        val cosPitch = horizontal.coerceAtLeast(0.15f)
        val pitchRateRad = (wx * d1 - wy * d0) / cosPitch

        return ratesToMotion(
            Math.toDegrees(yawRateRad.toDouble()).toFloat(),
            Math.toDegrees(pitchRateRad.toDouble()).toFloat(),
            dt,
            adsActive,
        )
    }

    private fun ratesToMotion(
        yawRateDps: Float,
        pitchRateDps: Float,
        dt: Float,
        adsActive: Boolean,
    ): Motion {
        var yawRate = if (config.enableYaw) yawRateDps else 0f
        var pitchRate = if (config.enablePitch) pitchRateDps else 0f

        if (hypot(yawRate, pitchRate) < config.deadzoneDps) {
            yawRate = 0f
            pitchRate = 0f
        }

        val alpha = smootherAlpha(dt)
        smoothedYawRate += alpha * (yawRate - smoothedYawRate)
        smoothedPitchRate += alpha * (pitchRate - smoothedPitchRate)

        val adsScale = if (adsActive) config.adsMultiplier else 1f
        var dx = smoothedYawRate * dt * config.sensitivityX * adsScale
        var dy = -smoothedPitchRate * dt * config.sensitivityY * adsScale
        if (config.invertX) dx = -dx
        if (config.invertY) dy = -dy

        val limit = config.maxPixelsPerTick
        dx = dx.coerceIn(-limit, limit)
        dy = dy.coerceIn(-limit, limit)
        if (!dx.isFinite()) dx = 0f
        if (!dy.isFinite()) dy = 0f

        return Motion(
            dxQ16 = (dx * Q16).roundToInt(),
            dyQ16 = (dy * Q16).roundToInt(),
        )
    }

    private fun smootherAlpha(dt: Float): Float {
        if (config.smoothing <= 0f) return 1f
        val tau = (config.smoothing * GyroConfig.SMOOTHING_TAU_SECONDS)
            .coerceAtLeast(0.005f)
        return (1f - exp(-dt / tau)).coerceIn(0.01f, 1f)
    }

    /**
     * While disarmed, learn the bias from samples near the current estimate.
     * This quickly absorbs a stale imported calibration without letting
     * deliberate movement run away with the bias.
     */
    private fun updateAutoBias(gyro: FloatArray, gyroTsNs: Long) {
        if (gyroTsNs == lastAutoBiasTs) return
        lastAutoBiasTs = gyroTsNs
        if (active) return
        val ex = gyro[0] - bias[0]
        val ey = gyro[1] - bias[1]
        val ez = gyro[2] - bias[2]
        if (!ex.isFinite() || !ey.isFinite() || !ez.isFinite()) return
        if (sqrt(ex * ex + ey * ey + ez * ez) < 0.25f) {
            val k = 0.002f
            bias[0] += k * ex
            bias[1] += k * ey
            bias[2] += k * ez
        }
    }

    private fun collectCalibration(gyro: FloatArray, gyroTsNs: Long, nowNs: Long) {
        if (gyroTsNs > 0 && gyroTsNs != lastCalibrationSampleTs) {
            lastCalibrationSampleTs = gyroTsNs
            val x = gyro[0].toDouble()
            val y = gyro[1].toDouble()
            val z = gyro[2].toDouble()
            if (x.isFinite() && y.isFinite() && z.isFinite()) {
                calibrationSamples++
                calSumX += x
                calSumY += y
                calSumZ += z
                calSumSq += x * x + y * y + z * z
            }
        }

        val elapsedMs = (nowNs - calibrationStartNs) / 1_000_000L
        if (elapsedMs < GyroConfig.CALIBRATION_MS) return

        calibrationActive = false
        lastTickNs = 0L
        if (calibrationSamples < 10) {
            resetMotionState()
            calibrationResult = GyroCalibrationResult(bias[0], bias[1], bias[2], false)
            return
        }

        val n = calibrationSamples
        val mx = (calSumX / n).toFloat()
        val my = (calSumY / n).toFloat()
        val mz = (calSumZ / n).toFloat()
        val meanSq = calSumSq / n
        val meanNormSq = (mx * mx + my * my + mz * mz).toDouble()
        val variance = (meanSq - meanNormSq).coerceAtLeast(0.0)
        val accepted = sqrt(variance) <= GyroConfig.CALIBRATION_MAX_STDDEV
        if (accepted) {
            bias[0] = mx
            bias[1] = my
            bias[2] = mz
        }
        calibrationResult = GyroCalibrationResult(bias[0], bias[1], bias[2], accepted)
        // Calibration suppresses output for up to a second; re-prime so the
        // first post-calibration sample cannot replay that gap as a jump.
        resetMotionState()
    }

    private fun resetMotionState() {
        smoothedYawRate = 0f
        smoothedPitchRate = 0f
        haveAngles = false
        lastGyroSampleTs = 0L
    }

    private companion object {
        const val Q16 = 65536f
    }
}

/** Utility used by tests and diagnostics. */
internal fun gyroMagnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

/** Returns |x| of a Q16.16 motion, mainly for assertions. */
internal fun Motion.absDxQ16(): Int = abs(dxQ16)
internal fun Motion.absDyQ16(): Int = abs(dyQ16)

/** True when the value is a finite, non-NaN float. */
internal fun isFiniteFloat(value: Float): Boolean = value.isFinite() && !value.isNaN()

/** Unit quaternion for a rotation about a normalized axis. Test helper. */
internal fun quatAxisAngle(ax: Float, ay: Float, az: Float, radians: Float): Quat {
    val len = sqrt(ax * ax + ay * ay + az * az)
    if (len < 1e-6f) return Quat.IDENTITY
    val half = radians / 2f
    val s = sin(half) / len
    return Quat(cos(half), ax * s, ay * s, az * s)
}
