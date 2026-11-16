package com.phonecontroller.app.input

import com.phonecontroller.app.net.DeltaEvent
import com.phonecontroller.app.net.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTrackerTest {

    private val shiftW = ControlAction(keys = listOf(42, 17)) // Shift, W
    private val shift = ControlAction(keys = listOf(42))
    private val w = ControlAction(keys = listOf(17))
    private val rmb = ControlAction(mouseButtons = listOf(Protocol.MOUSE_RIGHT))

    @Test
    fun `combination presses modifiers first and releases them last`() {
        val tracker = InputTracker()
        assertEquals(
            listOf(DeltaEvent.keyDown(42), DeltaEvent.keyDown(17)),
            tracker.press("sprint", shiftW),
        )
        assertEquals(
            listOf(DeltaEvent.keyUp(17), DeltaEvent.keyUp(42)),
            tracker.release("sprint"),
        )
        assertTrue(tracker.isEmpty())
    }

    @Test
    fun `shared modifier stays held until every action releases it`() {
        val tracker = InputTracker()
        tracker.press("shift", shift)
        tracker.press("sprint", shiftW)
        assertEquals(intArrayOf(17, 42).toList(), tracker.heldKeys().toList())

        // Releasing plain Shift must not lift Shift while SPRINT still needs it.
        assertEquals(emptyList<DeltaEvent>(), tracker.release("shift"))
        assertFalse(tracker.isEmpty())

        // Releasing SPRINT lifts W first, then Shift.
        assertEquals(
            listOf(DeltaEvent.keyUp(17), DeltaEvent.keyUp(42)),
            tracker.release("sprint"),
        )
        assertTrue(tracker.isEmpty())
    }

    @Test
    fun `same mouse button held by two actions is reference counted`() {
        val tracker = InputTracker()
        tracker.press("rmb", rmb)
        tracker.press("aim", rmb)
        assertEquals(0b10, tracker.mouseBits())

        assertEquals(emptyList<DeltaEvent>(), tracker.release("rmb"))
        assertEquals(0b10, tracker.mouseBits())

        assertEquals(listOf(DeltaEvent.mouseUp(Protocol.MOUSE_RIGHT)), tracker.release("aim"))
        assertEquals(0, tracker.mouseBits())
    }

    @Test
    fun `mouse bits cover all five buttons`() {
        val tracker = InputTracker()
        for (button in 1..5) {
            tracker.press("b$button", ControlAction(mouseButtons = listOf(button)))
        }
        assertEquals(0b11111, tracker.mouseBits())
        // Buttons are represented by bits, not by heldKeys().
        assertEquals(0, tracker.heldKeys().size)

        for (button in 1..5) {
            tracker.release("b$button")
        }
        assertEquals(0, tracker.mouseBits())
    }

    @Test
    fun `duplicate press id is ignored`() {
        val tracker = InputTracker()
        assertEquals(1, tracker.press("w", w).size)
        assertEquals(emptyList<DeltaEvent>(), tracker.press("w", w))
        assertEquals(1, tracker.release("w").size)
        assertTrue(tracker.isEmpty())
    }

    @Test
    fun `release unknown action is a no-op`() {
        val tracker = InputTracker()
        assertEquals(emptyList<DeltaEvent>(), tracker.release("missing"))
        tracker.press("w", w)
        assertEquals(emptyList<DeltaEvent>(), tracker.release("missing"))
        assertEquals(listOf(17), tracker.heldKeys().toList())
    }

    @Test
    fun `releaseAll emits an up for every input and clears state`() {
        val tracker = InputTracker()
        tracker.press("w", w)
        tracker.press("sprint", shiftW)
        tracker.press("rmb", rmb)
        val events = tracker.releaseAll()

        assertEquals(2, events.count { it.op == Protocol.OP_KEY_UP })
        assertEquals(1, events.count { it.op == Protocol.OP_MOUSE_UP })
        assertTrue(tracker.isEmpty())
        assertEquals(0, tracker.heldKeys().size)
        assertEquals(0, tracker.mouseBits())
    }

    @Test
    fun `clear forgets held state without emitting events`() {
        val tracker = InputTracker()
        tracker.press("w", w)
        tracker.clear()
        assertTrue(tracker.isEmpty())
        assertTrue(tracker.release("w").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty action is rejected`() {
        ControlAction()
    }
}
