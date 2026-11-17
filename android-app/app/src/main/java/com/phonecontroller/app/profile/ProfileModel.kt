package com.phonecontroller.app.profile

import com.phonecontroller.app.input.ControlAction
import com.phonecontroller.app.input.TouchpadConfig
import java.util.UUID

enum class CellType {
    KEY,
    KEY_COMBO,
    MOUSE_BUTTON,
    TOUCHPAD,
    JOYSTICK,
    MACRO,
    EMPTY,
}

/** One action inside a macro that runs on the phone. */
enum class MacroStepType {
    /** Press the keys, keep them held for [MacroStep.holdMs], release. */
    TAP,

    /** Press the keys and leave them held (released by a later RELEASE step). */
    HOLD,

    /** Release the most recent held action with exactly these keys. */
    RELEASE,

    /** Do nothing except wait [MacroStep.delayMs]. */
    WAIT,
}

/** Safety limits shared by the editor, JSON importer and runtime engine. */
object MacroLimits {
    const val MAX_STEPS = 24
    const val MAX_KEYS_PER_STEP = 4
    const val MAX_EVDEV_CODE = 0x2FF
    const val MAX_HOLD_MS = 2_000
    const val MAX_DELAY_MS = 2_000
    const val MAX_REPEAT = 10
    const val DEFAULT_HOLD_MS = 40
    const val DEFAULT_DELAY_MS = 40
    const val MAX_DURATION_MS = 15_000
}

data class MacroStep(
    val type: MacroStepType = MacroStepType.TAP,
    val keys: List<Int> = emptyList(),
    val holdMs: Int = MacroLimits.DEFAULT_HOLD_MS,
    val delayMs: Int = MacroLimits.DEFAULT_DELAY_MS,
) {
    /** Clamp imported/UI values to safe, deterministic bounds. */
    fun sanitized(): MacroStep {
        val cleanKeys = keys
            .filter { it in 1..MacroLimits.MAX_EVDEV_CODE }
            .distinct()
            .take(MacroLimits.MAX_KEYS_PER_STEP)
        val effectiveType = when {
            type == MacroStepType.WAIT -> MacroStepType.WAIT
            cleanKeys.isEmpty() -> MacroStepType.WAIT
            else -> type
        }
        return copy(
            type = effectiveType,
            keys = if (effectiveType == MacroStepType.WAIT) emptyList() else cleanKeys,
            holdMs = holdMs.coerceIn(0, MacroLimits.MAX_HOLD_MS),
            delayMs = delayMs.coerceIn(0, MacroLimits.MAX_DELAY_MS),
        )
    }

    /** Worst-case time this step occupies in one macro pass. */
    val estimatedDurationMs: Int
        get() = when (type) {
            MacroStepType.TAP -> holdMs + delayMs
            MacroStepType.HOLD,
            MacroStepType.RELEASE,
            MacroStepType.WAIT,
            -> delayMs
        }
}

data class MacroConfig(
    val steps: List<MacroStep> = emptyList(),
    val repeatCount: Int = 1,
) {
    /**
     * Sanitize steps and repeat count, then shrink the plan to
     * [MacroLimits.MAX_DURATION_MS] so a hostile import cannot create an
     * hours-long or effectively infinite macro.
     */
    fun sanitized(): MacroConfig {
        var cleanSteps = steps.take(MacroLimits.MAX_STEPS).map { it.sanitized() }
        while (cleanSteps.isNotEmpty() &&
            cleanSteps.sumOf { it.estimatedDurationMs } > MacroLimits.MAX_DURATION_MS
        ) {
            cleanSteps = cleanSteps.dropLast(1)
        }
        val singlePass = cleanSteps.sumOf { it.estimatedDurationMs }
        val repeats = repeatCount.coerceIn(1, MacroLimits.MAX_REPEAT)
        val fittingRepeats = if (singlePass <= 0) {
            repeats
        } else {
            (MacroLimits.MAX_DURATION_MS / singlePass).coerceAtLeast(1)
        }
        return MacroConfig(
            steps = cleanSteps,
            repeatCount = minOf(repeats, fittingRepeats),
        )
    }

    /** True when at least one step actually presses a key. */
    fun isRunnable(): Boolean = steps.any {
        it.keys.isNotEmpty() &&
            (it.type == MacroStepType.TAP || it.type == MacroStepType.HOLD)
    }

    /** Worst-case duration after clamping; used by the UI and engine. */
    val estimatedDurationMs: Long
        get() = steps.sumOf { it.estimatedDurationMs.toLong() } * repeatCount.toLong()
}

enum class ButtonShape {
    ROUNDED_RECT,
    CIRCLE,
    PILL,
}

data class CellConfig(
    val id: String = UUID.randomUUID().toString(),
    val row: Int = 0,
    val col: Int = 0,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val label: String,
    val type: CellType,
    val keys: List<Int> = emptyList(),
    val mouseButtons: List<Int> = emptyList(),
    val isToggle: Boolean = false,
    /** Joystick only: push past 85% forward to hold Shift (sprint). */
    val joystickSprint: Boolean = true,
    /** Macro only: phone-side sequence executed on tap. */
    val macro: MacroConfig = MacroConfig(),
    val customColor: Long? = null,
    // Freeform canvas coordinates (0.0 .. 1.0 fraction of screen) and dimensions
    val xPct: Float = 0f,
    val yPct: Float = 0f,
    val widthDp: Int = 68,
    val heightDp: Int = 54,
    val shape: ButtonShape = ButtonShape.ROUNDED_RECT,
    val opacity: Float = 1.0f,
) {
    /** Converts this cell's configuration into a runtime [ControlAction] */
    fun toControlAction(): ControlAction? {
        if (type == CellType.EMPTY ||
            type == CellType.TOUCHPAD ||
            type == CellType.JOYSTICK ||
            type == CellType.MACRO
        ) {
            return null
        }
        if (keys.isEmpty() && mouseButtons.isEmpty()) return null
        return ControlAction(keys = keys, mouseButtons = mouseButtons)
    }
}

data class GameProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val schemaVersion: Int = 1,
    val rows: Int = 3,
    val cols: Int = 4,
    val cells: List<CellConfig> = emptyList(),
    val touchpad: TouchpadConfig = TouchpadConfig(),
    val buttonSpacingDp: Int = 8,
    val cornerRadiusDp: Int = 18,
    val opacity: Float = 1.0f,
) {
    /** Returns cell at ([row], [col]), or null if unassigned. */
    fun cellAt(row: Int, col: Int): CellConfig? =
        cells.firstOrNull { it.row == row && it.col == col }

    fun cellById(id: String): CellConfig? =
        cells.firstOrNull { it.id == id }

    /**
     * Replaces the cell with a matching [CellConfig.id] in place.
     *
     * Order is preserved deliberately: the editor renders cells positionally,
     * so moving an updated cell to the end of the list would change its
     * composition slot (and cancel the in-progress drag gesture) on every
     * pointer move. Unknown ids are appended.
     */
    fun withCellUpdated(cell: CellConfig): GameProfile {
        val index = cells.indexOfFirst { it.id == cell.id }
        if (index < 0) return copy(cells = cells + cell)
        return copy(cells = cells.toMutableList().also { it[index] = cell })
    }

    fun withCellRemoved(id: String): GameProfile {
        return copy(cells = cells.filterNot { it.id == id })
    }

    fun withDimensions(newRows: Int, newCols: Int): GameProfile {
        val clampedRows = newRows.coerceIn(2, 6)
        val clampedCols = newCols.coerceIn(2, 8)
        val validCells = cells.filter { it.row < clampedRows && it.col < clampedCols }
        return copy(rows = clampedRows, cols = clampedCols, cells = validCells)
    }

    /**
     * Ensures all cells have valid normalized canvas positions.
     * If legacy cells have xPct == 0 and yPct == 0, auto-distributes them across
     * the screen so they are immediately visible and draggable.
     */
    fun normalizedCells(): List<CellConfig> {
        val needsLayout = cells.isNotEmpty() && cells.all { it.xPct == 0f && it.yPct == 0f }
        if (!needsLayout) return cells
        return cells.map { cell ->
            val colFraction = if (cols > 1) cell.col.toFloat() / (cols - 1) else 0.5f
            val rowFraction = if (rows > 1) cell.row.toFloat() / (rows - 1) else 0.5f
            val x = 0.08f + colFraction * 0.42f
            val y = 0.22f + rowFraction * 0.58f
            cell.copy(xPct = x, yPct = y)
        }
    }
}
