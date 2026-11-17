package com.phonecontroller.app.profile

import com.phonecontroller.app.input.Keycodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroTest {

    @Test
    fun `tap duration includes hold and delay`() {
        val step = MacroStep(
            type = MacroStepType.TAP,
            keys = listOf(Keycodes.KEY_W),
            holdMs = 120,
            delayMs = 80,
        )
        assertEquals(200, step.estimatedDurationMs)
    }

    @Test
    fun `macro duration multiplies steps by repeats`() {
        val macro = MacroConfig(
            steps = listOf(
                MacroStep(MacroStepType.TAP, listOf(Keycodes.KEY_W), holdMs = 100, delayMs = 50),
                MacroStep(MacroStepType.WAIT, delayMs = 100),
            ),
            repeatCount = 3,
        )
        assertEquals(750L, macro.estimatedDurationMs)
    }

    @Test
    fun `sanitize clamps keys delays and repeats`() {
        val macro = MacroConfig(
            steps = listOf(
                MacroStep(
                    type = MacroStepType.TAP,
                    keys = listOf(1, 2, 3, 4, 5, 6, 0, -1, 0x300),
                    holdMs = 99_999,
                    delayMs = -50,
                ),
            ),
            repeatCount = 999,
        ).sanitized()

        assertEquals(1, macro.steps.size)
        assertEquals(4, macro.steps[0].keys.size)
        assertEquals(MacroLimits.MAX_HOLD_MS, macro.steps[0].holdMs)
        assertEquals(0, macro.steps[0].delayMs)
        // 2 s per pass with a 15 s cap: 7 repeats, still under MAX_REPEAT.
        assertEquals(7, macro.repeatCount)
        assertTrue(macro.estimatedDurationMs <= MacroLimits.MAX_DURATION_MS)
    }

    @Test
    fun `sanitize reduces repeats to fit the duration cap`() {
        val macro = MacroConfig(
            steps = listOf(
                MacroStep(MacroStepType.TAP, listOf(Keycodes.KEY_W), holdMs = 2_000, delayMs = 2_000),
            ),
            repeatCount = 10,
        ).sanitized()

        assertEquals(1, macro.steps.size)
        assertEquals(3, macro.repeatCount)
        assertTrue(macro.estimatedDurationMs <= MacroLimits.MAX_DURATION_MS)
    }

    @Test
    fun `sanitize trims steps that exceed the duration cap`() {
        val step = MacroStep(
            MacroStepType.TAP,
            listOf(Keycodes.KEY_W),
            holdMs = 2_000,
            delayMs = 2_000,
        )
        val macro = MacroConfig(
            steps = List(MacroLimits.MAX_STEPS) { step },
            repeatCount = 1,
        ).sanitized()

        assertTrue(macro.steps.size < MacroLimits.MAX_STEPS)
        assertTrue(macro.estimatedDurationMs <= MacroLimits.MAX_DURATION_MS)
    }

    @Test
    fun `wait steps never carry keys`() {
        val step = MacroStep(
            type = MacroStepType.WAIT,
            keys = listOf(Keycodes.KEY_SPACE),
            delayMs = 500,
        ).sanitized()
        assertEquals(MacroStepType.WAIT, step.type)
        assertTrue(step.keys.isEmpty())
    }

    @Test
    fun `a step with no keys becomes a wait`() {
        val step = MacroStep(type = MacroStepType.TAP, keys = emptyList()).sanitized()
        assertEquals(MacroStepType.WAIT, step.type)
    }

    @Test
    fun `an empty macro is not runnable`() {
        assertFalse(MacroConfig().isRunnable())
        assertFalse(MacroConfig(steps = listOf(MacroStep(MacroStepType.WAIT, delayMs = 10))).isRunnable())
        assertTrue(MacroConfig(steps = listOf(MacroStep(MacroStepType.TAP, listOf(1)))).isRunnable())
    }
}
