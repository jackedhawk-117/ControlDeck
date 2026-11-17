package com.phonecontroller.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GyroMapperTest {

    private val base = quatAxisAngle(1f, 0f, 0f, Math.toRadians(-90.0).toFloat())

    private fun rv(q: Quat): FloatArray = floatArrayOf(q.x, q.y, q.z, q.w)

    /** Right-hand rotations composed in the world frame, as Android reports them. */
    private fun worldRotation(ax: Float, ay: Float, az: Float, degrees: Float): Quat =
        quatAxisAngle(ax, ay, az, Math.toRadians(degrees.toDouble()).toFloat()).times(base)

    private fun mapper(
        config: GyroConfig = GyroConfig(
            enabled = true,
            activationMode = GyroActivationMode.ALWAYS,
            sensitivityX = 4f,
            sensitivityY = 4f,
            deadzoneDps = 0f,
            smoothing = 0f,
            maxPixelsPerTick = 1_000f,
        ),
        active: Boolean = true,
    ): GyroMapper = GyroMapper(config).also { it.setActive(active) }

    private fun feed(mapper: GyroMapper, q: Quat, tsNs: Long, ads: Boolean = false): Motion =
        mapper.update(FloatArray(3), 0L, rv(q), tsNs, tsNs, ads)

    private fun q16(px: Float): Int = Math.round(px * 65536f)

    @Test
    fun `turning right produces positive horizontal movement`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        val out = feed(mapper, worldRotation(0f, 0f, 1f, -90f), 9_000_000L)

        assertEquals(q16(360f).toFloat(), out.dxQ16.toFloat(), 1_000f)
        assertTrue(abs(out.dyQ16) <= 1_000)
    }

    @Test
    fun `tilting up produces negative vertical movement`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        val out = feed(mapper, worldRotation(1f, 0f, 0f, 30f), 9_000_000L)

        assertTrue(abs(out.dxQ16) <= 1_000)
        assertEquals(q16(-120f).toFloat(), out.dyQ16.toFloat(), 1_000f)
    }

    @Test
    fun `rolling around the screen normal does not move the camera`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        val out = feed(mapper, worldRotation(0f, 1f, 0f, 90f), 9_000_000L)

        assertTrue(abs(out.dxQ16) <= 1_000)
        assertTrue(abs(out.dyQ16) <= 1_000)
    }

    @Test
    fun `heading wraps across the plus minus pi boundary`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        feed(mapper, worldRotation(0f, 0f, 1f, -179f), 9_000_000L)
        val out = feed(mapper, worldRotation(0f, 0f, 1f, -181f), 17_000_000L)

        // 179° -> 181° is a 2° step, not a 358° jump.
        assertEquals(q16(8f).toFloat(), out.dxQ16.toFloat(), 1_000f)
    }

    @Test
    fun `recenter resets the baseline without emitting a jump`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        feed(mapper, worldRotation(0f, 0f, 1f, -30f), 9_000_000L)

        mapper.recenter()
        val afterRecenter = feed(mapper, worldRotation(0f, 0f, 1f, -30f), 17_000_000L)
        assertTrue(abs(afterRecenter.dxQ16) <= 1_000)
        assertTrue(abs(afterRecenter.dyQ16) <= 1_000)

        val next = feed(mapper, worldRotation(0f, 0f, 1f, -60f), 25_000_000L)
        assertEquals(q16(120f).toFloat(), next.dxQ16.toFloat(), 1_000f)
    }

    @Test
    fun `recentering while aiming produces no output on that frame`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        mapper.recenter()
        val out = feed(mapper, worldRotation(0f, 0f, 1f, -45f), 9_000_000L)
        assertTrue(abs(out.dxQ16) <= 1_000)
    }

    @Test
    fun `activation prime produces no movement on the first sample`() {
        val mapper = mapper(active = false)
        feed(mapper, base, 1_000_000L)
        mapper.setActive(true)

        val primed = feed(mapper, worldRotation(0f, 0f, 1f, -45f), 9_000_000L)
        assertTrue(abs(primed.dxQ16) <= 1_000)

        val moved = feed(mapper, worldRotation(0f, 0f, 1f, -90f), 17_000_000L)
        assertEquals(q16(180f).toFloat(), moved.dxQ16.toFloat(), 1_000f)
    }

    @Test
    fun `disabling recenter on release keeps the old neutral pose`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                sensitivityX = 4f,
                sensitivityY = 4f,
                deadzoneDps = 0f,
                smoothing = 0f,
                maxPixelsPerTick = 1_000f,
                recenterOnRelease = false,
            ),
        )
        feed(mapper, base, 1_000_000L)
        mapper.setActive(false)
        // The phone moves 30° while aiming is disarmed.
        feed(mapper, worldRotation(0f, 0f, 1f, -30f), 9_000_000L)
        mapper.setActive(true)

        val moved = feed(mapper, worldRotation(0f, 0f, 1f, -30f), 17_000_000L)
        assertEquals(q16(120f).toFloat(), moved.dxQ16.toFloat(), 1_000f)
    }

    @Test
    fun `inactive mapper emits nothing`() {
        val mapper = mapper(active = false)
        feed(mapper, base, 1_000_000L)
        val out = feed(mapper, worldRotation(0f, 0f, 1f, -90f), 9_000_000L)
        assertTrue(abs(out.dxQ16) <= 1_000)
        assertTrue(abs(out.dyQ16) <= 1_000)
    }

    @Test
    fun `axis inversion flips both outputs`() {
        fun inverted(invertX: Boolean, invertY: Boolean) = mapper(
            GyroConfig(
                enabled = true,
                sensitivityX = 4f,
                sensitivityY = 4f,
                deadzoneDps = 0f,
                smoothing = 0f,
                maxPixelsPerTick = 1_000f,
                invertX = invertX,
                invertY = invertY,
            ),
        )

        val yaw = inverted(invertX = true, invertY = false)
        feed(yaw, base, 1_000_000L)
        val yawOut = feed(yaw, worldRotation(0f, 0f, 1f, -30f), 9_000_000L)
        assertTrue(yawOut.dxQ16 < 0)
        assertTrue(abs(yawOut.dyQ16) <= 1_000)

        val pitch = inverted(invertX = false, invertY = true)
        feed(pitch, base, 1_000_000L)
        val pitchOut = feed(pitch, worldRotation(1f, 0f, 0f, 30f), 9_000_000L)
        assertTrue(abs(pitchOut.dxQ16) <= 1_000)
        assertTrue(pitchOut.dyQ16 > 0)
    }

    @Test
    fun `disabled axes contribute nothing`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                sensitivityX = 4f,
                sensitivityY = 4f,
                deadzoneDps = 0f,
                smoothing = 0f,
                maxPixelsPerTick = 1_000f,
                enableYaw = false,
            ),
        )
        feed(mapper, base, 1_000_000L)
        val out = feed(mapper, worldRotation(1f, 0f, 1f, 30f), 9_000_000L)
        assertTrue(abs(out.dxQ16) <= 1_000)
        assertTrue(out.dyQ16 < 0)
    }

    @Test
    fun `deadzone suppresses tremor but not deliberate motion`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                sensitivityX = 4f,
                sensitivityY = 4f,
                deadzoneDps = 10f,
                smoothing = 0f,
                maxPixelsPerTick = 1_000f,
            ),
        )
        feed(mapper, base, 1_000_000L)
        // 0.03° in 8 ms = 3.75°/s, below the 10°/s deadzone.
        val tremor = feed(mapper, worldRotation(0f, 0f, 1f, -0.03f), 9_000_000L)
        assertEquals(0, tremor.dxQ16)

        // The next 10° step is well above the deadzone.
        val deliberate = feed(mapper, worldRotation(0f, 0f, 1f, -10.03f), 17_000_000L)
        assertTrue(deliberate.dxQ16 > 0)
    }

    @Test
    fun `per-tick clamp rejects a sensor spike`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                sensitivityX = 4f,
                sensitivityY = 4f,
                deadzoneDps = 0f,
                smoothing = 0f,
                maxPixelsPerTick = 50f,
            ),
        )
        feed(mapper, base, 1_000_000L)
        val out = feed(mapper, worldRotation(0f, 0f, 1f, -90f), 3_000_000L)
        assertEquals(q16(50f), out.dxQ16)
    }

    @Test
    fun `smoothing preserves total motion while lowering the first step`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                sensitivityX = 4f,
                sensitivityY = 4f,
                deadzoneDps = 0f,
                smoothing = 0.5f,
                maxPixelsPerTick = 1_000f,
            ),
        )
        feed(mapper, base, 1_000_000L)
        val first = feed(mapper, worldRotation(0f, 0f, 1f, -1f), 9_000_000L)
        assertTrue(first.dxQ16 in 1 until q16(4f))

        var total = first.dxQ16.toLong()
        var ts = 9_000_000L
        repeat(200) {
            ts += 8_000_000L
            total += feed(mapper, worldRotation(0f, 0f, 1f, -1f), ts).dxQ16
        }
        assertEquals(q16(4f).toFloat(), total.toFloat(), q16(0.2f).toFloat())
    }

    @Test
    fun `ads multiplier scales movement while aim is held`() {
        val config = GyroConfig(
            enabled = true,
            sensitivityX = 4f,
            sensitivityY = 4f,
            deadzoneDps = 0f,
            smoothing = 0f,
            maxPixelsPerTick = 1_000f,
            adsMultiplier = 0.5f,
        )
        val plain = mapper(config)
        feed(plain, base, 1_000_000L)
        val normal = feed(plain, worldRotation(0f, 0f, 1f, -30f), 9_000_000L)

        val adsMapper = mapper(config)
        feed(adsMapper, base, 1_000_000L)
        val ads = feed(adsMapper, worldRotation(0f, 0f, 1f, -30f), 9_000_000L, ads = true)

        assertEquals(normal.dxQ16 / 2f, ads.dxQ16.toFloat(), 2_000f)
    }

    @Test
    fun `gyro source integrates world yaw at the tick rate`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                sensorSource = GyroSensorSource.GYRO,
                sensitivityX = 1f,
                sensitivityY = 1f,
                deadzoneDps = 0f,
                smoothing = 0f,
                maxPixelsPerTick = 1_000f,
            ),
        )
        // Establish orientation and the tick baseline.
        mapper.update(FloatArray(3), 1_000_000L, rv(base), 1_000_000L, 1_000_000L, false)

        // Device-frame rotation that corresponds to a world yaw of 1 rad/s right.
        val device = base.inverse().rotate(0f, 0f, -1f)
        var dx = 0L
        for (i in 1..10) {
            val now = 1_000_000L + i * 10_000_000L
            dx += mapper.update(device, now, rv(base), 1_000_000L, now, false).dxQ16
        }

        // 1 rad/s right for 0.1 s = 5.73° = 5.73 px at 1 px/°.
        assertEquals((5.729578f * 65536f), dx.toFloat(), 3_000f)
    }

    @Test
    fun `gyro source ignores a repeated sensor sample`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                sensorSource = GyroSensorSource.GYRO,
                sensitivityX = 4f,
                sensitivityY = 4f,
                deadzoneDps = 0f,
                smoothing = 0f,
                maxPixelsPerTick = 1_000f,
            ),
        )
        val device = base.inverse().rotate(0f, 0f, -1f)
        mapper.update(device, 1_000_000L, rv(base), 1_000_000L, 1_000_000L, false)
        val first = mapper.update(device, 11_000_000L, rv(base), 1_000_000L, 11_000_000L, false)
        assertTrue(first.dxQ16 > 0)

        // Same sensor timestamp arrives on the next ticks: no further motion,
        // even though wall-clock time keeps advancing.
        val stale1 = mapper.update(device, 11_000_000L, rv(base), 1_000_000L, 19_000_000L, false)
        val stale2 = mapper.update(device, 11_000_000L, rv(base), 1_000_000L, 27_000_000L, false)
        assertEquals(0, stale1.dxQ16)
        assertEquals(0, stale2.dxQ16)
    }

    @Test
    fun `gyro source pitch rate is positive up`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                sensorSource = GyroSensorSource.GYRO,
                sensitivityX = 1f,
                sensitivityY = 1f,
                deadzoneDps = 0f,
                smoothing = 0f,
                maxPixelsPerTick = 1_000f,
            ),
        )
        mapper.update(FloatArray(3), 1_000_000L, rv(base), 1_000_000L, 1_000_000L, false)
        // World +X rotation with the phone facing north; device frame is the same.
        val device = base.inverse().rotate(1f, 0f, 0f)
        var dy = 0L
        for (i in 1..10) {
            val now = 1_000_000L + i * 10_000_000L
            dy += mapper.update(device, now, rv(base), 1_000_000L, now, false).dyQ16
        }
        // Pitch rate is positive (up), so mouse dy is negative.
        assertEquals((-5.729578f * 65536f), dy.toFloat(), 3_000f)
    }

    @Test
    fun `explicit calibration learns the at-rest bias`() {
        val mapper = mapper(active = false)
        mapper.beginCalibration(0L)
        var ts = 1_000_000L
        for (i in 0..120) {
            mapper.update(
                floatArrayOf(0.10f, -0.20f, 0.05f),
                ts,
                rv(base),
                ts,
                ts,
                false,
            )
            ts += 10_000_000L
        }
        val result = mapper.takeCalibrationResult()
        assertNotNull(result)
        assertTrue(result!!.accepted)
        assertEquals(0.10f, result.biasX, 0.001f)
        assertEquals(-0.20f, result.biasY, 0.001f)
        assertEquals(0.05f, result.biasZ, 0.001f)
        assertNull(mapper.takeCalibrationResult())
    }

    @Test
    fun `calibration rejects movement`() {
        val mapper = mapper(active = false)
        mapper.beginCalibration(0L)
        var ts = 1_000_000L
        for (i in 0..120) {
            val sign = if (i % 2 == 0) 1f else -1f
            mapper.update(
                floatArrayOf(2f * sign, 0f, 0f),
                ts,
                rv(base),
                ts,
                ts,
                false,
            )
            ts += 10_000_000L
        }
        val result = mapper.takeCalibrationResult()
        assertNotNull(result)
        assertFalse(result!!.accepted)
        assertEquals(0f, result.biasX, 0.0001f)
    }

    @Test
    fun `gyro and touchpad motions share one accumulator`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        val gyroOut = feed(mapper, worldRotation(0f, 0f, 1f, -10f), 9_000_000L)
        val touchpadOut = Motion(65_536, -32_768)

        val accumulator = MotionAccumulator()
        accumulator.add(touchpadOut)
        accumulator.add(gyroOut)
        val drained = accumulator.drain()

        assertNotNull(drained)
        assertEquals(touchpadOut.dxQ16 + gyroOut.dxQ16, drained!!.dxQ16)
        assertEquals(touchpadOut.dyQ16 + gyroOut.dyQ16, drained.dyQ16)
        assertNull(accumulator.drain())
    }

    @Test
    fun `cancelling calibration discards the pass`() {
        val mapper = mapper(active = false)
        mapper.beginCalibration(0L)
        var ts = 1_000_000L
        repeat(20) {
            mapper.update(floatArrayOf(0.1f, 0f, 0f), ts, rv(base), ts, ts, false)
            ts += 10_000_000L
        }
        assertTrue(mapper.isCalibrating)
        mapper.cancelCalibration()
        assertFalse(mapper.isCalibrating)

        repeat(120) {
            mapper.update(floatArrayOf(0.1f, 0f, 0f), ts, rv(base), ts, ts, false)
            ts += 10_000_000L
        }
        assertNull(mapper.takeCalibrationResult())
    }

    @Test
    fun `region hit test uses canvas fractions`() {
        val config = GyroConfig(
            regionXPct = 0.2f,
            regionYPct = 0.3f,
            regionWidthPct = 0.4f,
            regionHeightPct = 0.5f,
        )
        assertTrue(config.containsRegion(0.2f, 0.3f))
        assertTrue(config.containsRegion(0.6f, 0.8f))
        assertFalse(config.containsRegion(0.19f, 0.5f))
        assertFalse(config.containsRegion(0.5f, 0.81f))
    }

    @Test
    fun `config sanitizes hostile values`() {
        val clean = GyroConfig(
            sensitivityX = Float.NaN,
            sensitivityY = 99f,
            deadzoneDps = -5f,
            smoothing = 9f,
            maxPixelsPerTick = 0f,
            adsMultiplier = 99f,
            biasX = 99f,
            biasY = Float.POSITIVE_INFINITY,
            regionXPct = 0.95f,
            regionYPct = -1f,
            regionWidthPct = 9f,
            regionHeightPct = 9f,
        ).sanitized()

        assertEquals(3.0f, clean.sensitivityX, 0.0001f)
        assertEquals(GyroConfig.MAX_SENSITIVITY, clean.sensitivityY, 0.0001f)
        assertEquals(0f, clean.deadzoneDps, 0.0001f)
        assertEquals(0.9f, clean.smoothing, 0.0001f)
        assertEquals(GyroConfig.MIN_PIXELS_PER_TICK, clean.maxPixelsPerTick, 0.0001f)
        assertEquals(GyroConfig.MAX_ADS_MULTIPLIER, clean.adsMultiplier, 0.0001f)
        assertEquals(GyroConfig.MAX_BIAS, clean.biasX, 0.0001f)
        assertEquals(0f, clean.biasY, 0.0001f)
        assertEquals(0.90f, clean.regionXPct, 0.0001f)
        assertEquals(0f, clean.regionYPct, 0.0001f)
        assertTrue(clean.regionXPct + clean.regionWidthPct <= 1.0001f)
        assertTrue(clean.regionYPct + clean.regionHeightPct <= 1.0001f)
    }

    @Test
    fun `quaternion helpers normalize and compose`() {
        val q = quatAxisAngle(0f, 0f, 1f, Math.toRadians(90.0).toFloat()).normalized()
        assertEquals(0.7071068f, q.w, 0.0001f)
        assertEquals(0.7071068f, q.z, 0.0001f)

        val v = q.rotate(1f, 0f, 0f)
        assertEquals(0f, v[0], 0.0001f)
        assertEquals(1f, v[1], 0.0001f)
        assertEquals(0f, v[2], 0.0001f)
    }
}
