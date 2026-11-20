package com.phonecontroller.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GyroMapperTest {

    // The physical phone is held flat, screen up: +X right, +Y top edge, +Z up.
    private val base = Quat.IDENTITY

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
    fun `fused source ignores an out-of-order orientation sample`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        val accepted = feed(mapper, worldRotation(0f, 0f, 1f, -10f), 9_000_000L)
        assertEquals(q16(40f).toFloat(), accepted.dxQ16.toFloat(), 1_000f)

        // A late sample must not corrupt the baseline or produce a delta.
        val stale = feed(mapper, worldRotation(0f, 0f, 1f, -20f), 5_000_000L)
        assertEquals(0, stale.dxQ16)
        assertEquals(0, stale.dyQ16)

        // The next in-order sample is still measured from the last accepted
        // sample (-10°), so this is the expected 10° step.
        val next = feed(mapper, worldRotation(0f, 0f, 1f, -20f), 17_000_000L)
        assertEquals(q16(40f).toFloat(), next.dxQ16.toFloat(), 1_000f)
    }

    @Test
    fun `fused source re-primes after a sensor stall instead of jumping`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        feed(mapper, base, 9_000_000L)

        // 491 ms later the phone has moved 90° while the orientation stream was
        // stalled. Replay would be a 360 px step; the guard must drop it.
        val stalled = feed(mapper, worldRotation(0f, 0f, 1f, -90f), 500_000_000L)
        assertEquals(0, stalled.dxQ16)
        assertEquals(0, stalled.dyQ16)

        val primed = feed(mapper, worldRotation(0f, 0f, 1f, -90f), 509_000_000L)
        assertEquals(0, primed.dxQ16)

        val resumed = feed(mapper, worldRotation(0f, 0f, 1f, -91f), 517_000_000L)
        assertEquals(q16(4f).toFloat(), resumed.dxQ16.toFloat(), 1_000f)
    }

    @Test
    fun `aim vector near vertical does not release a stored yaw jump`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        // Lift the far edge until the short axis is almost vertical.
        val pitched = worldRotation(0f, 1f, 0f, -89.5f)
        feed(mapper, pitched, 9_000_000L)

        // At the pole the aim axis is vertical and its heading is undefined;
        // spin around world Z by 30°.
        val spun = quatAxisAngle(0f, 0f, 1f, Math.toRadians(-30.0).toFloat()).times(pitched)
        val atPole = feed(mapper, spun, 17_000_000L)
        assertEquals(0, atPole.dxQ16)

        // Leaving the pole must not replay the suppressed heading change.
        val leaving = feed(mapper, worldRotation(0f, 1f, 0f, -40f), 25_000_000L)
        assertTrue(abs(leaving.dxQ16) <= 1_000)
    }

    @Test
    fun `tilting up produces negative vertical movement`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        // Far edge (+X) up is a negative rotation about the long axis (+Y).
        val out = feed(mapper, worldRotation(0f, 1f, 0f, -30f), 9_000_000L)

        assertTrue(abs(out.dxQ16) <= 1_000)
        assertEquals(q16(-120f).toFloat(), out.dyQ16.toFloat(), 1_000f)
    }

    @Test
    fun `roll about the short axis does not move the camera`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        // Twisting about the aim vector itself leaves its heading and tilt
        // unchanged, so it is ignored by the heading/tilt math.
        val out = feed(mapper, worldRotation(1f, 0f, 0f, 90f), 9_000_000L)

        assertTrue(abs(out.dxQ16) <= 1_000)
        assertTrue(abs(out.dyQ16) <= 1_000)
    }

    @Test
    fun `flat rotation about the screen normal is the yaw axis`() {
        // With the phone flat, the screen normal and the gravity axis coincide:
        // a "twist" about it is physically the same motion as yaw.
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        val out = feed(mapper, worldRotation(0f, 0f, 1f, -10f), 9_000_000L)
        assertEquals(q16(40f).toFloat(), out.dxQ16.toFloat(), 1_000f)
    }

    @Test
    fun `landscape grip keeps yaw and pitch axes physical`() {
        val mapper = mapper()
        val landscape = quatAxisAngle(0f, 0f, 1f, Math.toRadians(90.0).toFloat())
        feed(mapper, landscape, 1_000_000L)

        // Turn right about the gravity axis from a landscape grip.
        val turned = quatAxisAngle(0f, 0f, 1f, Math.toRadians(-10.0).toFloat())
            .times(landscape)
        val yawOut = feed(mapper, turned, 9_000_000L)
        assertEquals(q16(40f).toFloat(), yawOut.dxQ16.toFloat(), 1_000f)
        assertTrue(abs(yawOut.dyQ16) <= 1_000)

        // Return to the landscape neutral before testing pitch in isolation.
        feed(mapper, landscape, 17_000_000L)
        // Lift the physical far edge (+X end) by 30° about the long axis.
        val pitched = landscape.times(
            quatAxisAngle(0f, 1f, 0f, Math.toRadians(-30.0).toFloat()),
        )
        val pitchOut = feed(mapper, pitched, 25_000_000L)
        assertTrue(abs(pitchOut.dxQ16) <= 1_000)
        assertEquals(q16(-120f).toFloat(), pitchOut.dyQ16.toFloat(), 1_000f)
    }

    @Test
    fun `combined yaw and pitch stay independent`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        // 10° right and 20° far-edge-up in one pose.
        val combined = quatAxisAngle(0f, 0f, 1f, Math.toRadians(-10.0).toFloat())
            .times(quatAxisAngle(0f, 1f, 0f, Math.toRadians(-20.0).toFloat()))
        val out = feed(mapper, combined, 9_000_000L)

        assertEquals(q16(40f).toFloat(), out.dxQ16.toFloat(), 1_500f)
        assertEquals(q16(-80f).toFloat(), out.dyQ16.toFloat(), 1_500f)
    }

    @Test
    fun `vertical capture sweep about the long edge is pure pitch`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)

        // The vertical phyphox capture is 97.7% rotation about device Y. A
        // 60° far-edge-up sweep must therefore produce only vertical mouse
        // movement, with no horizontal leakage.
        var ts = 1_000_000L
        var dx = 0L
        var dy = 0L
        for (i in 1..40) {
            ts += 10_000_000L
            val out = feed(mapper, worldRotation(0f, 1f, 0f, -1.5f * i), ts)
            dx += out.dxQ16
            dy += out.dyQ16
        }
        assertEquals(0, dx)
        assertEquals(q16(-240f).toFloat(), dy.toFloat(), 2_000f)
    }

    @Test
    fun `roll about the aim axis is ignored from any pose`() {
        val clean = mapper()
        val rolled = mapper()
        feed(clean, base, 1_000_000L)
        feed(rolled, base, 1_000_000L)

        val yawPitch = quatAxisAngle(0f, 0f, 1f, Math.toRadians(-25.0).toFloat())
            .times(quatAxisAngle(0f, 1f, 0f, Math.toRadians(-15.0).toFloat()))
        val withRoll = yawPitch.times(
            quatAxisAngle(1f, 0f, 0f, Math.toRadians(50.0).toFloat()),
        )
        val a = feed(clean, yawPitch, 9_000_000L)
        val b = feed(rolled, withRoll, 9_000_000L)

        // The two poses differ only by a rotation about the aim axis; the
        // remaining 1/65536 px difference is float quantization.
        assertEquals(a.dxQ16.toFloat(), b.dxQ16.toFloat(), 2f)
        assertEquals(a.dyQ16.toFloat(), b.dyQ16.toFloat(), 2f)
        assertEquals(q16(100f).toFloat(), a.dxQ16.toFloat(), 1_000f)
        assertEquals(q16(-60f).toFloat(), a.dyQ16.toFloat(), 1_000f)
    }

    @Test
    fun `reverse landscape flips pitch but not yaw`() {
        val mapper = mapper()
        mapper.setDisplayRotation(3)
        feed(mapper, base, 1_000_000L)

        val yaw = feed(mapper, worldRotation(0f, 0f, 1f, -10f), 9_000_000L)
        assertEquals(q16(40f).toFloat(), yaw.dxQ16.toFloat(), 1_000f)
        assertTrue(abs(yaw.dyQ16) <= 1_000)

        // In reverse landscape the aim axis points toward the player, so the
        // same physical far-edge-up gesture is the opposite pitch sign.
        val pitched = quatAxisAngle(0f, 0f, 1f, Math.toRadians(-10.0).toFloat())
            .times(quatAxisAngle(0f, 1f, 0f, Math.toRadians(-30.0).toFloat()))
        val out = feed(mapper, pitched, 17_000_000L)
        assertTrue(abs(out.dxQ16) <= 1_000)
        assertEquals(q16(120f).toFloat(), out.dyQ16.toFloat(), 1_000f)
    }

    @Test
    fun `display rotation change re-primes instead of jumping`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        feed(mapper, worldRotation(0f, 1f, 0f, -30f), 9_000_000L)

        mapper.setDisplayRotation(3)
        val primed = feed(mapper, worldRotation(0f, 1f, 0f, -30f), 17_000_000L)
        assertEquals(0, primed.dxQ16)
        assertEquals(0, primed.dyQ16)

        // The next 1° change uses the reverse-landscape pitch sign.
        val next = feed(mapper, worldRotation(0f, 1f, 0f, -31f), 25_000_000L)
        assertEquals(q16(4f).toFloat(), next.dyQ16.toFloat(), 1_000f)
    }

    @Test
    fun `combined capture sweep keeps yaw and pitch independent`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)

        // The combined phyphox capture moves both axes at once. A 20° right
        // and 10° up sweep must accumulate exactly those two contributions.
        var ts = 1_000_000L
        var dx = 0L
        var dy = 0L
        for (i in 1..40) {
            val q = quatAxisAngle(
                0f,
                0f,
                1f,
                Math.toRadians((-0.5f * i).toDouble()).toFloat(),
            ).times(
                quatAxisAngle(0f, 1f, 0f, Math.toRadians((-0.25f * i).toDouble()).toFloat()),
            )
            ts += 10_000_000L
            val out = feed(mapper, q, ts)
            dx += out.dxQ16
            dy += out.dyQ16
        }
        assertEquals(q16(80f).toFloat(), dx.toFloat(), 2_000f)
        assertEquals(q16(-40f).toFloat(), dy.toFloat(), 2_000f)
    }

    @Test
    fun `recenter at a tilted pose makes it the new neutral`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        val tilted = worldRotation(0f, 1f, 0f, -30f)
        feed(mapper, tilted, 9_000_000L)

        mapper.recenter()
        val neutral = feed(mapper, tilted, 17_000_000L)
        assertTrue(abs(neutral.dxQ16) <= 1_000)
        assertTrue(abs(neutral.dyQ16) <= 1_000)

        val next = feed(mapper, worldRotation(0f, 1f, 0f, -31f), 25_000_000L)
        assertEquals(q16(-4f).toFloat(), next.dyQ16.toFloat(), 1_000f)
    }

    @Test
    fun `flat yaw from a tilted neutral does not leak into pitch`() {
        val mapper = mapper()
        // Real phyphox capture: the phone is flat enough to yaw, but held with
        // the far edge raised ~19°, so the principal rotation axis sits that
        // far from device Z. The reference must still be gravity-aligned.
        val tilted = quatAxisAngle(0f, 1f, 0f, Math.toRadians(-19.0).toFloat())
        feed(mapper, tilted, 1_000_000L)
        mapper.recenter()
        feed(mapper, tilted, 9_000_000L)

        // Rotate about the gravity-aligned vertical, not the tilted body Z.
        val turned = quatAxisAngle(0f, 0f, 1f, Math.toRadians(-10.0).toFloat())
            .times(tilted)
        val out = feed(mapper, turned, 17_000_000L)

        assertEquals(q16(40f).toFloat(), out.dxQ16.toFloat(), 1_000f)
        assertTrue(abs(out.dyQ16) <= 1_000)
    }

    @Test
    fun `pitch from a tilted neutral changes only the vertical axis`() {
        val mapper = mapper()
        val tilted = quatAxisAngle(0f, 1f, 0f, Math.toRadians(-19.0).toFloat())
        feed(mapper, tilted, 1_000_000L)
        mapper.recenter()
        feed(mapper, tilted, 9_000_000L)

        // Lift the far edge a further 20° about the long axis.
        val pitched = quatAxisAngle(0f, 1f, 0f, Math.toRadians(-20.0).toFloat())
            .times(tilted)
        val out = feed(mapper, pitched, 17_000_000L)

        assertTrue(abs(out.dxQ16) <= 1_000)
        assertEquals(q16(-80f).toFloat(), out.dyQ16.toFloat(), 1_000f)
    }

    @Test
    fun `tilted alternating sweeps from the captured horizontal motion stay pure yaw`() {
        val mapper = mapper()
        // Second phyphox capture (34.3 s, both directions): 22 alternating
        // strokes, 80.3% of rotational energy on device Z, principal axis
        // (0.39, -0.03, 0.92) — a 23.1° far-edge-up tilt about the long axis.
        // A true world yaw therefore appears in the body frame as
        // (0.39, 0, 0.92)·rate.
        val bank = quatAxisAngle(0f, 1f, 0f, Math.toRadians(-23.1).toFloat())
        var ts = 1_000_000L
        feed(mapper, bank, ts)
        ts += 8_000_000L
        feed(mapper, bank, ts)

        val perStroke = LongArray(6)
        var totalDy = 0L
        for (stroke in 0 until 6) {
            val left = stroke % 2 == 0
            val from = if (left) 0f else 140f
            val to = if (left) 140f else 0f
            var dx = 0L
            for (i in 1..140) {
                ts += 10_000_000L
                val worldYaw = from + (to - from) * i / 140f
                val q = quatAxisAngle(
                    0f,
                    0f,
                    1f,
                    Math.toRadians(worldYaw.toDouble()).toFloat(),
                ).times(bank)
                val out = feed(mapper, q, ts)
                dx += out.dxQ16
                totalDy += out.dyQ16
            }
            perStroke[stroke] = dx
        }

        // 140° at 4 px/° = 560 px; left is negative, right positive.
        for (stroke in 0 until 6) {
            val expected = if (stroke % 2 == 0) q16(-560f) else q16(560f)
            assertEquals(expected.toFloat(), perStroke[stroke].toFloat(), 2_000f)
        }
        assertEquals(0f, totalDy.toFloat(), 2_000f)
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
    fun `always on contributes without ADS and ignores hold inputs`() {
        assertTrue(
            resolveGyroActive(
                mode = GyroActivationMode.ALWAYS,
                holdActive = false,
                toggleActive = false,
                regionActive = false,
                adsHeld = false,
            ),
        )
    }

    @Test
    fun `ADS only follows logical right button and not other holds`() {
        assertFalse(
            resolveGyroActive(
                mode = GyroActivationMode.ADS_ONLY,
                holdActive = true,
                toggleActive = true,
                regionActive = true,
                adsHeld = false,
            ),
        )
        assertTrue(
            resolveGyroActive(
                mode = GyroActivationMode.ADS_ONLY,
                holdActive = false,
                toggleActive = false,
                regionActive = false,
                adsHeld = true,
            ),
        )
    }

    @Test
    fun `hold toggle and region modes stay independent of ADS`() {
        assertTrue(resolveGyroActive(GyroActivationMode.HOLD, true, false, false, false))
        assertFalse(resolveGyroActive(GyroActivationMode.HOLD, false, false, false, true))
        assertTrue(resolveGyroActive(GyroActivationMode.TOGGLE, false, true, false, false))
        assertFalse(resolveGyroActive(GyroActivationMode.TOGGLE, false, false, false, true))
        assertTrue(resolveGyroActive(GyroActivationMode.REGION, false, false, true, false))
        assertFalse(resolveGyroActive(GyroActivationMode.REGION, false, false, false, true))
    }

    @Test
    fun `ADS re-entry after movement does not replay a pose jump`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                activationMode = GyroActivationMode.ADS_ONLY,
                sensitivityX = 4f,
                sensitivityY = 4f,
                deadzoneDps = 0f,
                smoothing = 0f,
                maxPixelsPerTick = 1_000f,
                recenterOnRelease = false,
            ),
        )
        // ADS held: the mapper is active from the start.
        feed(mapper, base, 1_000_000L)
        feed(mapper, worldRotation(0f, 0f, 1f, -30f), 9_000_000L)

        // ADS released; the phone keeps moving while gyro is disarmed. With
        // recenterOnRelease=false a naive re-arm would replay all 50° of this.
        mapper.setActive(false)
        feed(mapper, worldRotation(0f, 0f, 1f, -80f), 17_000_000L)

        // ADS pressed again at -80°: entry must prime without emitting.
        mapper.setActive(true)
        val entry = feed(mapper, worldRotation(0f, 0f, 1f, -80f), 25_000_000L)
        assertEquals(0, entry.dxQ16)
        assertEquals(0, entry.dyQ16)

        val moved = feed(mapper, worldRotation(0f, 0f, 1f, -81f), 33_000_000L)
        assertEquals(q16(4f).toFloat(), moved.dxQ16.toFloat(), 1_000f)
    }

    @Test
    fun `recenter while ADS is held produces no movement`() {
        val mapper = mapper(
            GyroConfig(
                enabled = true,
                activationMode = GyroActivationMode.ADS_ONLY,
                sensitivityX = 4f,
                sensitivityY = 4f,
                deadzoneDps = 0f,
                smoothing = 0f,
                maxPixelsPerTick = 1_000f,
            ),
        )
        feed(mapper, base, 1_000_000L)
        feed(mapper, worldRotation(0f, 0f, 1f, -20f), 9_000_000L)
        mapper.recenter()
        val out = feed(mapper, worldRotation(0f, 0f, 1f, -60f), 17_000_000L)
        assertEquals(0, out.dxQ16)
        assertEquals(0, out.dyQ16)
    }

    @Test
    fun `recenter after a session reconnect primes without a jump`() {
        val mapper = mapper()
        feed(mapper, base, 1_000_000L)
        feed(mapper, worldRotation(0f, 0f, 1f, -30f), 9_000_000L)

        // Disconnect releases inputs and recenters; reconnecting re-arms.
        mapper.setActive(false)
        mapper.recenter()
        mapper.setActive(true)

        // The phone settled at a new pose while disconnected.
        val primed = feed(mapper, worldRotation(0f, 0f, 1f, -70f), 17_000_000L)
        assertEquals(0, primed.dxQ16)
        assertEquals(0, primed.dyQ16)

        val moved = feed(mapper, worldRotation(0f, 0f, 1f, -71f), 25_000_000L)
        assertEquals(q16(4f).toFloat(), moved.dxQ16.toFloat(), 1_000f)
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
        val pitchOut = feed(pitch, worldRotation(0f, 1f, 0f, -30f), 9_000_000L)
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
        val out = feed(mapper, worldRotation(0f, 1f, 0f, -30f), 9_000_000L)
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
        val telemetry = mapper.telemetry()
        assertTrue(telemetry.deadzoneActive)
        assertEquals(0f, telemetry.outputDxPx, 0.001f)
        assertEquals(3.75f, telemetry.yawRateDps, 0.1f)

        // The next 10° step is well above the deadzone.
        val deliberate = feed(mapper, worldRotation(0f, 0f, 1f, -10.03f), 17_000_000L)
        assertTrue(deliberate.dxQ16 > 0)
        assertFalse(mapper.telemetry().deadzoneActive)
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
    fun `ADS scaling leaves the touchpad contribution untouched`() {
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

        val aiming = mapper(config)
        feed(aiming, base, 1_000_000L)
        val ads = feed(aiming, worldRotation(0f, 0f, 1f, -30f), 9_000_000L, ads = true)
        assertEquals(normal.dxQ16 / 2f, ads.dxQ16.toFloat(), 2_000f)

        // The touchpad bypasses the ADS multiplier entirely: the combined
        // output is the full touchpad delta plus the reduced gyro delta.
        val touchpad = Motion(65_536, 32_768)
        val accumulator = MotionAccumulator()
        accumulator.add(touchpad)
        accumulator.add(ads)
        val combined = accumulator.drain()!!
        assertEquals(touchpad.dxQ16 + ads.dxQ16, combined.dxQ16)
        assertEquals(touchpad.dyQ16 + ads.dyQ16, combined.dyQ16)
    }

    @Test
    fun `fused source total is independent of the sample rate`() {
        fun totalFor(stepMs: Long, steps: Int): Long {
            val mapper = mapper()
            var ts = 1_000_000L
            feed(mapper, base, ts)
            var total = 0L
            repeat(steps) { i ->
                ts += stepMs * 1_000_000L
                val degrees = -10f * (i + 1) / steps
                total += feed(mapper, worldRotation(0f, 0f, 1f, degrees), ts).dxQ16
            }
            return total
        }

        // Same 10° of yaw delivered over 400 ms at 250 Hz and 62.5 Hz must
        // produce the same total movement.
        val fast = totalFor(stepMs = 4, steps = 100)
        val slow = totalFor(stepMs = 16, steps = 25)
        assertEquals(fast.toFloat(), slow.toFloat(), 2_000f)
        assertEquals(q16(40f).toFloat(), fast.toFloat(), 2_000f)
    }

    @Test
    fun `gyro source total is independent of the sample rate`() {
        fun totalFor(stepMs: Long): Long {
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
            var ts = 1_000_000L
            val rate = floatArrayOf(0f, 0f, -0.5f)
            mapper.update(rate, ts, rv(base), ts, ts, false)
            var total = 0L
            repeat((200 / stepMs).toInt()) {
                ts += stepMs * 1_000_000L
                total += mapper.update(rate, ts, rv(base), ts, ts, false).dxQ16
            }
            return total
        }

        val fast = totalFor(stepMs = 4)
        val slow = totalFor(stepMs = 20)
        assertEquals(fast.toFloat(), slow.toFloat(), 1_000f)
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
        val device = floatArrayOf(0f, 0f, -1f)
        var dx = 0L
        for (i in 1..10) {
            val now = 1_000_000L + i * 10_000_000L
            dx += mapper.update(device, now, rv(base), 1_000_000L, now, false).dxQ16
        }

        // 1 rad/s right for 0.1 s = 5.73° = 5.73 px at 1 px/°.
        assertEquals((5.729578f * 65536f), dx.toFloat(), 3_000f)
    }

    @Test
    fun `gyro source keeps world yaw pure from a pitched grip`() {
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
        val tilted = quatAxisAngle(0f, 1f, 0f, Math.toRadians(-19.0).toFloat())
        mapper.update(FloatArray(3), 1_000_000L, rv(tilted), 1_000_000L, 1_000_000L, false)
        // Body-frame rate equal to the world +Z (gravity) axis in this pose.
        val device = quatAxisAngle(0f, 1f, 0f, Math.toRadians(19.0).toFloat())
            .rotate(0f, 0f, 1f)
        var dx = 0L
        var dy = 0L
        for (i in 1..10) {
            val now = 1_000_000L + i * 10_000_000L
            val out = mapper.update(device, now, rv(tilted), 1_000_000L, now, false)
            dx += out.dxQ16
            dy += out.dyQ16
        }
        // 1 rad/s left for 0.1 s = 5.73° left = -22.9 px at 4 px/°.
        assertEquals((-5.729578f * 4f * 65536f), dx.toFloat(), 3_000f)
        assertEquals(0f, dy.toFloat(), 1_000f)
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
        val device = floatArrayOf(0f, 0f, -1f)
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
    fun `gyro source drops a stalled interval instead of integrating it`() {
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
        val device = floatArrayOf(0f, 0f, -1f)
        mapper.update(device, 1_000_000L, rv(base), 1_000_000L, 1_000_000L, false)
        val first = mapper.update(device, 11_000_000L, rv(base), 1_000_000L, 11_000_000L, false)
        assertTrue(first.dxQ16 > 0)

        // A 90 ms stall at 1 rad/s would add ~5.7 px if the interval were
        // integrated. The guard drops it and re-baselines instead.
        val stalled = mapper.update(device, 101_000_000L, rv(base), 1_000_000L, 101_000_000L, false)
        assertEquals(0, stalled.dxQ16)
        assertEquals(0, stalled.dyQ16)
        assertEquals(90f, mapper.telemetry().intervalMs, 0.5f)

        // The next normal interval integrates again from the fresh baseline.
        val resumed = mapper.update(device, 111_000_000L, rv(base), 1_000_000L, 111_000_000L, false)
        assertTrue(resumed.dxQ16 > 0)
    }

    @Test
    fun `gyro source pitch rate is positive for far-edge up`() {
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
        // Rate about world -Y lifts the far edge (+X) at 1 rad/s.
        val device = floatArrayOf(0f, -1f, 0f)
        var dy = 0L
        for (i in 1..10) {
            val now = 1_000_000L + i * 10_000_000L
            dy += mapper.update(device, now, rv(base), 1_000_000L, now, false).dyQ16
        }
        // Pitch rate is positive (up), so mouse dy is negative.
        assertEquals((-5.729578f * 65536f), dy.toFloat(), 3_000f)
    }

    @Test
    fun `gyro source ignores roll about the short axis`() {
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
        mapper.update(FloatArray(3), 1_000_000L, rv(base), 1_000_000L, 1_000_000L, false)
        // Angular velocity along the aim axis (+X) is roll around it; it must
        // not contribute to yaw or pitch.
        val device = floatArrayOf(1f, 0f, 0f)
        var dx = 0L
        var dy = 0L
        for (i in 1..10) {
            val now = 1_000_000L + i * 10_000_000L
            val out = mapper.update(device, now, rv(base), 1_000_000L, now, false)
            dx += out.dxQ16
            dy += out.dyQ16
        }
        assertEquals(0, dx)
        assertEquals(0, dy)
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
