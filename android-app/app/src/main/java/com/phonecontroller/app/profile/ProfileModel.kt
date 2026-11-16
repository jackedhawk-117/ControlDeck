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
    EMPTY,
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
        if (type == CellType.EMPTY || type == CellType.TOUCHPAD || type == CellType.JOYSTICK) return null
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
