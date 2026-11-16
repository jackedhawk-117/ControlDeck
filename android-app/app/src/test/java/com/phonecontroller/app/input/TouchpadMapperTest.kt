package com.phonecontroller.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadMapperTest {

    private val q16 = 65536f

    private fun mapper(
        sensitivity: Float = 1f,
        acceleration: Float = 0f,
        smoothing: Float = 0f,
        invertX: Boolean = false,
        invertY: Boolean = false,
    ) = TouchpadMapper(
        TouchpadConfig(
            sensitivityX = sensitivity,
            sensitivityY = sensitivity,
            acceleration = acceleration,
            smoothing = smoothing,
            invertX = invertX,
            invertY = invertY,
        ),
    )

    @Test
    fun `raw movement is scaled by sensitivity`() {
        val motion = mapper().onMove(10f, -5f, 8)
        assertEquals((10 * q16).toInt(), motion.dxQ16)
        assertEquals((-5 * q16).toInt(), motion.dyQ16)
    }

    @Test
    fun `fractional pixels survive in q16`() {
        val motion = mapper(sensitivity = 0.5f).onMove(1f, 0f, 1)
        assertEquals(32768, motion.dxQ16)
    }

    @Test
    fun `axis inversion flips sign only`() {
        val motion = mapper(sensitivity = 0.8f, invertX = true, invertY = true).onMove(10f, 6f, 10)
        assertTrue(motion.dxQ16 < 0)
        assertTrue(motion.dyQ16 < 0)
    }

    @Test
    fun `zero movement produces zero even with acceleration`() {
        assertTrue(mapper(acceleration = 2f, smoothing = 0.8f).onMove(0f, 0f, 8).isZero())
    }

    @Test
    fun `acceleration increases fast-swipe output`() {
        val raw = mapper(acceleration = 0f).onMove(30f, 0f, 8)
        val accelerated = mapper(acceleration = 1.5f).onMove(30f, 0f, 8)
        assertTrue(accelerated.dxQ16 > raw.dxQ16)
    }

    @Test
    fun `smoothing attenuates the first sample and converges`() {
        val m = mapper(smoothing = 0.5f)
        val raw = 10 * q16
        val first = m.onMove(10f, 0f, 8).dxQ16.toFloat()
        val second = m.onMove(10f, 0f, 8).dxQ16.toFloat()
        val third = m.onMove(10f, 0f, 8).dxQ16.toFloat()

        assertTrue(first < raw)
        assertTrue(second > first)
        assertTrue(third > second)
        assertTrue(third < raw)
    }

    @Test
    fun `dt is clamped to 1 and 50 ms`() {
        val zero = mapper().onMove(10f, 0f, 0)
        val one = mapper().onMove(10f, 0f, 1)
        assertEquals(one, zero)

        val fifty = mapper().onMove(10f, 0f, 50)
        val huge = mapper().onMove(10f, 0f, 100_000)
        assertEquals(fifty, huge)
    }

    @Test
    fun `config sanitizes out-of-range values`() {
        val clean = TouchpadConfig(
            sensitivityX = 90f,
            sensitivityY = 0f,
            acceleration = -4f,
            smoothing = 7f,
            widthFraction = 2f,
        ).sanitized()
        assertEquals(TouchpadConfig.MAX_SENSITIVITY, clean.sensitivityX)
        assertEquals(TouchpadConfig.MIN_SENSITIVITY, clean.sensitivityY)
        assertEquals(0f, clean.acceleration)
        assertEquals(0.9f, clean.smoothing)
        assertEquals(TouchpadConfig.MAX_WIDTH, clean.widthFraction)
    }

    @Test
    fun `config sanitizes non-finite values`() {
        val clean = TouchpadConfig(
            sensitivityX = Float.NaN,
            sensitivityY = Float.POSITIVE_INFINITY,
            acceleration = Float.NEGATIVE_INFINITY,
            smoothing = Float.NaN,
            widthFraction = Float.POSITIVE_INFINITY,
        ).sanitized()
        assertEquals(0.80f, clean.sensitivityX, 0.001f)
        assertEquals(0.80f, clean.sensitivityY, 0.001f)
        assertEquals(0.15f, clean.acceleration, 0.001f)
        assertEquals(0.35f, clean.smoothing, 0.001f)
        assertEquals(0.52f, clean.widthFraction, 0.001f)
    }

    @Test
    fun `accumulator conflates samples until drained`() {
        val acc = MotionAccumulator()
        assertEquals(null, acc.drain())
        acc.add(Motion(1000, -500))
        acc.add(Motion(250, 125))
        assertEquals(Motion(1250, -375), acc.drain())
        assertEquals(null, acc.drain())
    }

    @Test
    fun `accumulator clears and clamps`() {
        val acc = MotionAccumulator()
        acc.add(Motion(Int.MAX_VALUE, Int.MAX_VALUE))
        acc.add(Motion(Int.MAX_VALUE, Int.MAX_VALUE))
        val out = acc.drain()!!
        assertEquals(4096 * 65536, out.dxQ16)
        assertEquals(4096 * 65536, out.dyQ16)

        acc.add(Motion(100, 100))
        acc.clear()
        assertEquals(null, acc.drain())
    }
}
