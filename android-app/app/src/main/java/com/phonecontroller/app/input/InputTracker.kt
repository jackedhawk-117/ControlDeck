package com.phonecontroller.app.input

import com.phonecontroller.app.net.DeltaEvent
import com.phonecontroller.app.net.Protocol

/**
 * One logical control action. A cell may hold a plain key, a key combination
 * (modifiers first), one or more mouse buttons, or both.
 *
 * Key order matters: modifiers must come before the main key so games see
 * Shift down, then W down. Release uses the reverse order.
 */
data class ControlAction(
    val keys: List<Int> = emptyList(),
    val mouseButtons: List<Int> = emptyList(),
) {
    init {
        require(keys.isNotEmpty() || mouseButtons.isNotEmpty()) { "empty control action" }
    }
}

/**
 * Tracks which actions are currently active and turns them into protocol
 * deltas with **reference-counted** keys/buttons.
 *
 * Two fingers may hold the same key (for example a plain Shift cell and a
 * Shift+W combo). Releasing one action must not release the key still held by
 * the other. Counts solve that; events are emitted only on the 0↔1 edges.
 *
 * Not thread-safe: use from the Compose/UI thread (or a single dispatcher).
 */
class InputTracker {

    /** action id -> action, in press order (used by [releaseAll]). */
    private val active = LinkedHashMap<String, ControlAction>()
    private val keyCounts = HashMap<Int, Int>()
    private val buttonCounts = HashMap<Int, Int>()

    fun isActive(actionId: String): Boolean = active.containsKey(actionId)

    /**
     * Activate [actionId]. Repeated presses while it is already active are
     * ignored, so a duplicate pointer callback cannot double-press a key.
     */
    fun press(actionId: String, action: ControlAction): List<DeltaEvent> {
        if (active.containsKey(actionId)) return emptyList()
        active[actionId] = action
        val out = ArrayList<DeltaEvent>(action.keys.size + action.mouseButtons.size)
        for (key in action.keys) {
            val count = keyCounts[key] ?: 0
            keyCounts[key] = count + 1
            if (count == 0) out += DeltaEvent.keyDown(key)
        }
        for (button in action.mouseButtons) {
            val count = buttonCounts[button] ?: 0
            buttonCounts[button] = count + 1
            if (count == 0) out += DeltaEvent.mouseDown(button)
        }
        return out
    }

    /** Deactivate [actionId]; emits releases only for inputs no longer held. */
    fun release(actionId: String): List<DeltaEvent> {
        val action = active.remove(actionId) ?: return emptyList()
        val out = ArrayList<DeltaEvent>(action.keys.size + action.mouseButtons.size)
        // Reverse order: main key up before its modifier.
        for (key in action.keys.asReversed()) {
            val count = (keyCounts[key] ?: 1) - 1
            if (count <= 0) {
                keyCounts.remove(key)
                out += DeltaEvent.keyUp(key)
            } else {
                keyCounts[key] = count
            }
        }
        for (button in action.mouseButtons.asReversed()) {
            val count = (buttonCounts[button] ?: 1) - 1
            if (count <= 0) {
                buttonCounts.remove(button)
                out += DeltaEvent.mouseUp(button)
            } else {
                buttonCounts[button] = count
            }
        }
        return out
    }

    /** Release every active action (disconnect/profile switch). */
    fun releaseAll(): List<DeltaEvent> {
        val out = ArrayList<DeltaEvent>()
        for (id in active.keys.toList().asReversed()) {
            out += release(id)
        }
        return out
    }

    /** Forget all state without producing events (the peer already reset). */
    fun clear() {
        active.clear()
        keyCounts.clear()
        buttonCounts.clear()
    }

    /** Sorted evdev codes currently held — the INPUT_SNAPSHOT key list. */
    fun heldKeys(): IntArray = keyCounts.keys.sorted().toIntArray()

    /** INPUT_SNAPSHOT mouse bits: bit0..4 = left, right, middle, back, forward. */
    fun mouseBits(): Int {
        var bits = 0
        for (button in buttonCounts.keys) {
            if (button in 1..5) bits = bits or (1 shl (button - 1))
        }
        return bits
    }

    /** True when no key or mouse button remains held. */
    fun isEmpty(): Boolean = keyCounts.isEmpty() && buttonCounts.isEmpty()

    companion object {
        /** Convenience ids used by the starter gameplay layout. */
        const val TAP_CLICK_ACTION = "touchpad-tap"
    }
}
