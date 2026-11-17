package com.phonecontroller.app.input

import com.phonecontroller.app.net.DeltaEvent
import com.phonecontroller.app.net.Protocol
import com.phonecontroller.app.profile.MacroConfig
import com.phonecontroller.app.profile.MacroStep
import com.phonecontroller.app.profile.MacroStepType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroEngineTest {

    private suspend fun MacroEngine.awaitIdle(cellId: String) {
        withTimeout(3_000) {
            while (isRunning(cellId)) delay(5)
        }
    }

    private fun codes(events: List<DeltaEvent>, op: Int): List<Int> =
        events.filter { it.op == op }.map { it.code }

    @Test
    fun `tap presses chord then releases in reverse order`() = runBlocking {
        val tracker = InputTracker()
        val events = mutableListOf<DeltaEvent>()
        val engine = MacroEngine(tracker, { events += it }, this)
        val macro = MacroConfig(
            steps = listOf(
                MacroStep(
                    MacroStepType.TAP,
                    listOf(Keycodes.KEY_LEFTSHIFT, Keycodes.KEY_W),
                    holdMs = 5,
                    delayMs = 5,
                ),
            ),
        )

        assertTrue(engine.start("macro-cell", macro))
        engine.awaitIdle("macro-cell")

        assertEquals(
            listOf(Keycodes.KEY_LEFTSHIFT, Keycodes.KEY_W),
            codes(events, Protocol.OP_KEY_DOWN),
        )
        assertEquals(
            listOf(Keycodes.KEY_W, Keycodes.KEY_LEFTSHIFT),
            codes(events, Protocol.OP_KEY_UP),
        )
        assertTrue(tracker.isEmpty())
        assertFalse(engine.isRunning("macro-cell"))
    }

    @Test
    fun `hold tap release sequence keeps shift held around the tap`() = runBlocking {
        val tracker = InputTracker()
        val events = mutableListOf<DeltaEvent>()
        val engine = MacroEngine(tracker, { events += it }, this)
        val macro = MacroConfig(
            steps = listOf(
                MacroStep(MacroStepType.HOLD, listOf(Keycodes.KEY_LEFTSHIFT), delayMs = 1),
                MacroStep(
                    MacroStepType.TAP,
                    listOf(Keycodes.KEY_W),
                    holdMs = 5,
                    delayMs = 1,
                ),
                MacroStep(MacroStepType.RELEASE, listOf(Keycodes.KEY_LEFTSHIFT), delayMs = 1),
            ),
        )

        assertTrue(engine.start("shift-w", macro))
        engine.awaitIdle("shift-w")

        val down = codes(events, Protocol.OP_KEY_DOWN)
        val up = codes(events, Protocol.OP_KEY_UP)
        assertEquals(listOf(Keycodes.KEY_LEFTSHIFT, Keycodes.KEY_W), down)
        assertEquals(listOf(Keycodes.KEY_W, Keycodes.KEY_LEFTSHIFT), up)
        assertTrue(tracker.isEmpty())
    }

    @Test
    fun `repeat count repeats the whole sequence`() = runBlocking {
        val tracker = InputTracker()
        val events = mutableListOf<DeltaEvent>()
        val engine = MacroEngine(tracker, { events += it }, this)
        val macro = MacroConfig(
            steps = listOf(
                MacroStep(MacroStepType.TAP, listOf(Keycodes.KEY_W), holdMs = 1, delayMs = 1),
            ),
            repeatCount = 3,
        )

        assertTrue(engine.start("repeat", macro))
        engine.awaitIdle("repeat")

        assertEquals(3, codes(events, Protocol.OP_KEY_DOWN).size)
        assertEquals(3, codes(events, Protocol.OP_KEY_UP).size)
        assertTrue(tracker.isEmpty())
    }

    @Test
    fun `cancel releases a held key immediately`() = runBlocking {
        val tracker = InputTracker()
        val events = mutableListOf<DeltaEvent>()
        val engine = MacroEngine(tracker, { events += it }, this)
        val macro = MacroConfig(
            steps = listOf(
                MacroStep(MacroStepType.HOLD, listOf(Keycodes.KEY_LEFTSHIFT), delayMs = 1),
                MacroStep(MacroStepType.WAIT, delayMs = 2_000),
            ),
        )

        assertTrue(engine.start("cancel", macro))
        withTimeout(1_000) {
            while (!tracker.isActive("macro_cancel_1_0_0")) delay(1)
        }
        engine.cancel("cancel")

        assertFalse(engine.isRunning("cancel"))
        assertTrue(tracker.isEmpty())
        assertEquals(
            listOf(Keycodes.KEY_LEFTSHIFT),
            codes(events, Protocol.OP_KEY_UP),
        )
    }

    @Test
    fun `cancel all releases every macro`() = runBlocking {
        val tracker = InputTracker()
        val engine = MacroEngine(tracker, {}, this)
        val macro = MacroConfig(
            steps = listOf(
                MacroStep(MacroStepType.HOLD, listOf(Keycodes.KEY_LEFTSHIFT), delayMs = 1),
                MacroStep(MacroStepType.WAIT, delayMs = 2_000),
            ),
        )

        assertTrue(engine.start("a", macro))
        assertTrue(engine.start("b", macro.copy(steps = listOf(
            MacroStep(MacroStepType.HOLD, listOf(Keycodes.KEY_LEFTCTRL), delayMs = 1),
            MacroStep(MacroStepType.WAIT, delayMs = 2_000),
        ))))
        withTimeout(1_000) {
            while (tracker.heldKeys().size < 2) delay(1)
        }

        engine.cancelAll()

        assertFalse(engine.isRunning("a"))
        assertFalse(engine.isRunning("b"))
        assertTrue(tracker.isEmpty())
        assertEquals(emptySet<String>(), engine.runningIds)
    }

    @Test
    fun `macro keys share reference counts with touch controls`() = runBlocking {
        val tracker = InputTracker()
        tracker.press("touch-w", ControlAction(keys = listOf(Keycodes.KEY_W)))
        val events = mutableListOf<DeltaEvent>()
        val engine = MacroEngine(tracker, { events += it }, this)

        assertTrue(
            engine.start(
                "refcount",
                MacroConfig(
                    steps = listOf(
                        MacroStep(MacroStepType.TAP, listOf(Keycodes.KEY_W), holdMs = 5, delayMs = 1),
                    ),
                ),
            ),
        )
        engine.awaitIdle("refcount")

        // The touch cell still holds W, so the macro must not release it.
        assertTrue(tracker.isActive("touch-w"))
        assertTrue(codes(events, Protocol.OP_KEY_UP).isEmpty())
        assertEquals(listOf(Keycodes.KEY_W), tracker.heldKeys().toList())
    }

    @Test
    fun `empty macro does not start`() = runBlocking {
        val engine = MacroEngine(InputTracker(), {}, this)
        assertFalse(engine.start("empty", MacroConfig()))
        assertFalse(engine.isRunning("empty"))
    }

    @Test
    fun `starting the same cell twice is rejected`() = runBlocking {
        val tracker = InputTracker()
        val engine = MacroEngine(tracker, {}, this)
        val macro = MacroConfig(
            steps = listOf(
                MacroStep(MacroStepType.HOLD, listOf(Keycodes.KEY_LEFTSHIFT), delayMs = 1),
                MacroStep(MacroStepType.WAIT, delayMs = 2_000),
            ),
        )

        assertTrue(engine.start("same", macro))
        assertFalse(engine.start("same", macro))
        engine.cancelAll()
        assertTrue(tracker.isEmpty())
    }
}
