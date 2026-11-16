package com.phonecontroller.app.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileModelTest {

    private fun cell(id: String, x: Float = 0.5f) = CellConfig(
        id = id,
        label = id,
        type = CellType.KEY,
        xPct = x,
        yPct = 0.5f,
    )

    @Test
    fun `withCellUpdated updates in place without reordering`() {
        val profile = GameProfile(
            id = "p",
            name = "P",
            cells = listOf(cell("a"), cell("b"), cell("c")),
        )

        val updated = profile.withCellUpdated(cell("b", x = 0.9f))

        assertEquals(listOf("a", "b", "c"), updated.cells.map { it.id })
        assertEquals(0.9f, updated.cells[1].xPct, 0.0001f)
    }

    @Test
    fun `repeated drag updates keep composition order stable`() {
        var profile = GameProfile(
            id = "p",
            name = "P",
            cells = listOf(cell("a"), cell("b"), cell("c")),
        )

        // Simulate one onDrag event per update: the dragged cell must stay in
        // its original slot or the editor's pointer coroutine is disposed.
        repeat(20) { step ->
            val current = profile.cellById("a")!!
            profile = profile.withCellUpdated(current.copy(xPct = 0.5f - step * 0.01f))
        }

        assertEquals(listOf("a", "b", "c"), profile.cells.map { it.id })
        assertEquals(0.31f, profile.cells[0].xPct, 0.0001f)
    }

    @Test
    fun `withCellUpdated appends unknown ids`() {
        val profile = GameProfile(
            id = "p",
            name = "P",
            cells = listOf(cell("a")),
        )

        val updated = profile.withCellUpdated(cell("new"))

        assertEquals(listOf("a", "new"), updated.cells.map { it.id })
    }
}
