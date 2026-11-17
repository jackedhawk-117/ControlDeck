package com.phonecontroller.app.profile

import com.phonecontroller.app.input.GyroActivationMode
import com.phonecontroller.app.input.GyroCellAction
import com.phonecontroller.app.input.GyroConfig
import com.phonecontroller.app.input.GyroSensorSource
import com.phonecontroller.app.input.Keycodes
import com.phonecontroller.app.input.TouchpadConfig
import com.phonecontroller.app.net.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileJsonTest {

    @Test
    fun `single profile roundtrip preserves all fields`() {
        val original = GameProfile(
            id = "test-profile-123",
            name = "Test FPS Layout",
            description = "Custom testing profile with various actions",
            schemaVersion = 1,
            rows = 4,
            cols = 5,
            cells = listOf(
                CellConfig(
                    id = "c1",
                    row = 0,
                    col = 0,
                    label = "W",
                    type = CellType.KEY,
                    keys = listOf(Keycodes.KEY_W),
                    xPct = 0.16f,
                    yPct = 0.68f,
                    widthDp = 130,
                    heightDp = 130,
                    shape = ButtonShape.CIRCLE,
                    opacity = 0.85f,
                ),
                CellConfig(
                    id = "c2",
                    row = 1,
                    col = 2,
                    label = "SPRINT",
                    type = CellType.KEY_COMBO,
                    keys = listOf(Keycodes.KEY_LEFTSHIFT, Keycodes.KEY_W),
                    xPct = 0.16f,
                    yPct = 0.28f,
                    widthDp = 64,
                    heightDp = 48,
                    shape = ButtonShape.PILL,
                    opacity = 0.90f,
                ),
                CellConfig(
                    id = "c3",
                    row = 2,
                    col = 3,
                    label = "AIM",
                    type = CellType.MOUSE_BUTTON,
                    mouseButtons = listOf(Protocol.MOUSE_RIGHT),
                    isToggle = true,
                    xPct = 0.76f,
                    yPct = 0.28f,
                    widthDp = 62,
                    heightDp = 62,
                    shape = ButtonShape.ROUNDED_RECT,
                    opacity = 0.95f,
                ),
                CellConfig(
                    id = "c4",
                    label = "WASD",
                    type = CellType.JOYSTICK,
                    xPct = 0.20f,
                    yPct = 0.70f,
                    widthDp = 140,
                    heightDp = 140,
                    shape = ButtonShape.CIRCLE,
                ),
            ),
            touchpad = TouchpadConfig(
                sensitivityX = 1.25f,
                sensitivityY = 1.15f,
                acceleration = 0.45f,
                smoothing = 0.20f,
                invertX = true,
                invertY = false,
                widthFraction = 0.48f,
                tapToClick = true,
            ),
            buttonSpacingDp = 10,
            cornerRadiusDp = 14,
            opacity = 0.95f,
        )

        val json = ProfileJson.serialize(original)
        val parsed = ProfileJson.parse(json)

        assertEquals(original.id, parsed.id)
        assertEquals(original.name, parsed.name)
        assertEquals(original.description, parsed.description)
        assertEquals(original.schemaVersion, parsed.schemaVersion)
        assertEquals(original.rows, parsed.rows)
        assertEquals(original.cols, parsed.cols)
        assertEquals(original.buttonSpacingDp, parsed.buttonSpacingDp)
        assertEquals(original.cornerRadiusDp, parsed.cornerRadiusDp)
        assertEquals(original.opacity, parsed.opacity, 0.001f)

        // Touchpad
        assertEquals(original.touchpad.sensitivityX, parsed.touchpad.sensitivityX, 0.001f)
        assertEquals(original.touchpad.sensitivityY, parsed.touchpad.sensitivityY, 0.001f)
        assertEquals(original.touchpad.acceleration, parsed.touchpad.acceleration, 0.001f)
        assertEquals(original.touchpad.smoothing, parsed.touchpad.smoothing, 0.001f)
        assertEquals(original.touchpad.invertX, parsed.touchpad.invertX)
        assertEquals(original.touchpad.invertY, parsed.touchpad.invertY)
        assertEquals(original.touchpad.widthFraction, parsed.touchpad.widthFraction, 0.001f)
        assertEquals(original.touchpad.tapToClick, parsed.touchpad.tapToClick)

        // Cells
        assertEquals(4, parsed.cells.size)
        val c1 = parsed.cellById("c1")
        assertNotNull(c1)
        assertEquals("W", c1!!.label)
        assertEquals(CellType.KEY, c1.type)
        assertEquals(listOf(Keycodes.KEY_W), c1.keys)
        assertFalse(c1.isToggle)
        assertEquals(0.16f, c1.xPct, 0.001f)
        assertEquals(0.68f, c1.yPct, 0.001f)
        assertEquals(130, c1.widthDp)
        assertEquals(130, c1.heightDp)
        assertEquals(ButtonShape.CIRCLE, c1.shape)
        assertEquals(0.85f, c1.opacity, 0.001f)

        val c2 = parsed.cellById("c2")
        assertNotNull(c2)
        assertEquals("SPRINT", c2!!.label)
        assertEquals(CellType.KEY_COMBO, c2.type)
        assertEquals(listOf(Keycodes.KEY_LEFTSHIFT, Keycodes.KEY_W), c2.keys)
        assertEquals(ButtonShape.PILL, c2.shape)

        val c3 = parsed.cellById("c3")
        assertNotNull(c3)
        assertEquals("AIM", c3!!.label)
        assertEquals(CellType.MOUSE_BUTTON, c3.type)
        assertEquals(listOf(Protocol.MOUSE_RIGHT), c3.mouseButtons)
        assertTrue(c3.isToggle)
        assertEquals(ButtonShape.ROUNDED_RECT, c3.shape)

        val c4 = parsed.cellById("c4")
        assertNotNull(c4)
        assertEquals(CellType.JOYSTICK, c4!!.type)
        assertEquals(140, c4.widthDp)
        assertTrue(c4.joystickSprint)
    }

    @Test
    fun `joystick sprint setting roundtrips and defaults on for legacy cells`() {
        val profile = GameProfile(
            id = "joy",
            name = "Joystick",
            cells = listOf(
                CellConfig(
                    id = "j",
                    label = "WASD",
                    type = CellType.JOYSTICK,
                    joystickSprint = false,
                ),
            ),
        )

        val parsed = ProfileJson.parse(ProfileJson.serialize(profile))
        assertFalse(parsed.cellById("j")!!.joystickSprint)

        // Profiles written before the setting existed must keep sprint enabled.
        val legacy = ProfileJson.parse(
            """{"id":"legacy","name":"Legacy","cells":[{"id":"j","label":"WASD","type":"JOYSTICK"}]}""",
        )
        assertTrue(legacy.cellById("j")!!.joystickSprint)
    }

    @Test
    fun `multiple profiles list roundtrip`() {
        val defaults = DefaultProfiles.createAll()
        assertEquals(5, defaults.size)

        val json = ProfileJson.serializeList(defaults)
        val parsed = ProfileJson.parseList(json)

        assertEquals(5, parsed.size)
        assertEquals(defaults.map { it.id }, parsed.map { it.id })
        assertEquals(defaults.map { it.name }, parsed.map { it.name })
    }

    @Test
    fun `gyro settings and cells roundtrip`() {
        val original = GameProfile(
            id = "gyro-profile",
            name = "Gyro Test",
            gyro = GyroConfig(
                enabled = true,
                activationMode = GyroActivationMode.TOGGLE,
                sensorSource = GyroSensorSource.GYRO,
                sensitivityX = 4.5f,
                sensitivityY = 2.25f,
                invertX = true,
                enablePitch = false,
                deadzoneDps = 2.5f,
                smoothing = 0.4f,
                maxPixelsPerTick = 150f,
                adsMultiplier = 0.45f,
                recenterOnRelease = false,
                biasX = 0.01f,
                biasY = -0.02f,
                biasZ = 0.03f,
                regionXPct = 0.2f,
                regionYPct = 0.25f,
                regionWidthPct = 0.5f,
                regionHeightPct = 0.45f,
            ),
            cells = listOf(
                CellConfig(
                    id = "gy-activate",
                    label = "GYRO",
                    type = CellType.GYRO,
                    gyroAction = GyroCellAction.ACTIVATE,
                    xPct = 0.4f,
                    yPct = 0.8f,
                ),
                CellConfig(
                    id = "gy-recenter",
                    label = "CENTER",
                    type = CellType.GYRO,
                    gyroAction = GyroCellAction.RECENTER,
                    xPct = 0.5f,
                    yPct = 0.2f,
                ),
            ),
        )

        val parsed = ProfileJson.parse(ProfileJson.serialize(original))
        assertEquals(2, parsed.cells.size)
        assertEquals(GyroCellAction.ACTIVATE, parsed.cells[0].gyroAction)
        assertEquals(GyroCellAction.RECENTER, parsed.cells[1].gyroAction)

        val gyro = parsed.gyro
        assertTrue(gyro.enabled)
        assertEquals(GyroActivationMode.TOGGLE, gyro.activationMode)
        assertEquals(GyroSensorSource.GYRO, gyro.sensorSource)
        assertEquals(4.5f, gyro.sensitivityX, 0.0001f)
        assertEquals(2.25f, gyro.sensitivityY, 0.0001f)
        assertTrue(gyro.invertX)
        assertFalse(gyro.enablePitch)
        assertEquals(2.5f, gyro.deadzoneDps, 0.0001f)
        assertEquals(0.4f, gyro.smoothing, 0.0001f)
        assertEquals(150f, gyro.maxPixelsPerTick, 0.0001f)
        assertEquals(0.45f, gyro.adsMultiplier, 0.0001f)
        assertFalse(gyro.recenterOnRelease)
        assertEquals(0.01f, gyro.biasX, 0.0001f)
        assertEquals(0.2f, gyro.regionXPct, 0.0001f)
        assertEquals(0.45f, gyro.regionHeightPct, 0.0001f)
    }

    @Test
    fun `legacy profile without gyro decodes a disabled default`() {
        val parsed = ProfileJson.parse(
            """{"id":"legacy-gyro","name":"Legacy","cells":[{"id":"gy","label":"GYRO","type":"GYRO"}]}""",
        )
        assertFalse(parsed.gyro.enabled)
        assertEquals(GyroActivationMode.HOLD, parsed.gyro.activationMode)
        assertEquals(GyroSensorSource.FUSED, parsed.gyro.sensorSource)
        assertEquals(3.0f, parsed.gyro.sensitivityX, 0.0001f)
        assertEquals(GyroCellAction.ACTIVATE, parsed.cells.single().gyroAction)
    }

    @Test
    fun `hostile gyro import is clamped`() {
        val json = """
            {
              "id": "hostile-gyro",
              "name": "Hostile Gyro",
              "gyro": {
                "enabled": true,
                "activationMode": "TELEPORT",
                "sensorSource": "MAGIC",
                "sensitivityX": 999,
                "sensitivityY": -5,
                "deadzoneDps": -2,
                "smoothing": 5,
                "maxPixelsPerTick": 0,
                "adsMultiplier": 9,
                "biasX": 50,
                "biasY": -50,
                "regionXPct": 0.95,
                "regionYPct": -1,
                "regionWidthPct": 5,
                "regionHeightPct": 0.001
              },
              "cells": [
                {
                  "id": "gy",
                  "label": "GYRO",
                  "type": "GYRO",
                  "gyroAction": "NOPE"
                }
              ]
            }
        """.trimIndent()

        val parsed = ProfileJson.parse(json)
        val gyro = parsed.gyro
        assertEquals(GyroActivationMode.HOLD, gyro.activationMode)
        assertEquals(GyroSensorSource.FUSED, gyro.sensorSource)
        assertEquals(GyroConfig.MAX_SENSITIVITY, gyro.sensitivityX, 0.0001f)
        assertEquals(GyroConfig.MIN_SENSITIVITY, gyro.sensitivityY, 0.0001f)
        assertEquals(0f, gyro.deadzoneDps, 0.0001f)
        assertEquals(0.9f, gyro.smoothing, 0.0001f)
        assertEquals(GyroConfig.MIN_PIXELS_PER_TICK, gyro.maxPixelsPerTick, 0.0001f)
        assertEquals(GyroConfig.MAX_ADS_MULTIPLIER, gyro.adsMultiplier, 0.0001f)
        assertEquals(GyroConfig.MAX_BIAS, gyro.biasX, 0.0001f)
        assertEquals(-GyroConfig.MAX_BIAS, gyro.biasY, 0.0001f)
        assertEquals(0.90f, gyro.regionXPct, 0.0001f)
        assertEquals(0f, gyro.regionYPct, 0.0001f)
        assertTrue(gyro.regionXPct + gyro.regionWidthPct <= 1.0001f)
        assertTrue(gyro.regionYPct + gyro.regionHeightPct <= 1.0001f)
        assertEquals(GyroCellAction.ACTIVATE, parsed.cells.single().gyroAction)
    }

    @Test
    fun `missing optional fields fallback to safe defaults`() {
        val minimalJson = """
            {
                "id": "minimal",
                "name": "Minimalist"
            }
        """.trimIndent()

        val parsed = ProfileJson.parse(minimalJson)
        assertEquals("minimal", parsed.id)
        assertEquals("Minimalist", parsed.name)
        assertEquals(3, parsed.rows)
        assertEquals(4, parsed.cols)
        assertTrue(parsed.cells.isEmpty())
        assertNotNull(parsed.touchpad)
        assertEquals(0.80f, parsed.touchpad.sensitivityX, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed json throws IllegalArgumentException`() {
        ProfileJson.parse("{ invalid json content }")
    }

    @Test
    fun `cell to control action handles key and combo and mouse`() {
        val keyCell = CellConfig(row = 0, col = 0, label = "W", type = CellType.KEY, keys = listOf(Keycodes.KEY_W))
        val keyAction = keyCell.toControlAction()
        assertNotNull(keyAction)
        assertEquals(listOf(Keycodes.KEY_W), keyAction!!.keys)

        val emptyCell = CellConfig(row = 0, col = 1, label = "", type = CellType.EMPTY)
        assertNull(emptyCell.toControlAction())

        val joystickCell = CellConfig(label = "WASD", type = CellType.JOYSTICK)
        assertNull(joystickCell.toControlAction())
    }

    @Test
    fun `trailing data after a profile is rejected`() {
        val json = ProfileJson.serialize(
            GameProfile(id = "a", name = "A")
        ) + " garbage"
        try {
            ProfileJson.parse(json)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Trailing data"))
        }
    }

    @Test
    fun `imported cells are clamped and deduplicated`() {
        val json = """
            [
              {
                "id": "dupe-profile",
                "name": "  Hostile   Import  ",
                "description": "line1\nline2",
                "rows": 99,
                "cols": -4,
                "opacity": 7.0,
                "buttonSpacingDp": 999,
                "cornerRadiusDp": -3,
                "touchpad": {
                  "sensitivityX": 1e999,
                  "sensitivityY": -2,
                  "acceleration": 1e999,
                  "smoothing": -1,
                  "widthFraction": 9
                },
                "cells": [
                  {
                    "id": "same",
                    "label": "A\nB",
                    "type": "KEY",
                    "keys": [30, 30, 0, 9999, -3],
                    "mouseButtons": [1, 1, 9],
                    "xPct": -2,
                    "yPct": 3,
                    "widthDp": 100000,
                    "heightDp": -5,
                    "opacity": 5,
                    "customColor": -1
                  },
                  {
                    "id": "same",
                    "label": "Second",
                    "type": "NOT_A_TYPE",
                    "xPct": 1e999,
                    "yPct": 1e999
                  },
                  {
                    "label": "BlankId",
                    "type": "JOYSTICK"
                  }
                ]
              },
              {
                "id": "dupe-profile",
                "name": "Same id"
              }
            ]
        """.trimIndent()

        val parsed = ProfileJson.parseList(json)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].id != parsed[1].id)
        assertEquals("Hostile Import", parsed[0].name)
        assertEquals("line1 line2", parsed[0].description)
        assertEquals(6, parsed[0].rows)
        assertEquals(2, parsed[0].cols)
        assertEquals(1f, parsed[0].opacity, 0.001f)
        assertEquals(48, parsed[0].buttonSpacingDp)
        assertEquals(0, parsed[0].cornerRadiusDp)

        val tp = parsed[0].touchpad
        assertEquals(0.80f, tp.sensitivityX, 0.001f)
        assertEquals(TouchpadConfig.MIN_SENSITIVITY, tp.sensitivityY, 0.001f)
        assertEquals(0.15f, tp.acceleration, 0.001f)
        assertEquals(0f, tp.smoothing, 0.001f)
        assertEquals(TouchpadConfig.MAX_WIDTH, tp.widthFraction, 0.001f)

        assertEquals(3, parsed[0].cells.size)
        val first = parsed[0].cells[0]
        assertEquals("A B", first.label)
        assertEquals(listOf(30), first.keys)
        assertEquals(listOf(1), first.mouseButtons)
        assertEquals(0f, first.xPct, 0.001f)
        assertEquals(1f, first.yPct, 0.001f)
        assertEquals(320, first.widthDp)
        assertEquals(24, first.heightDp)
        assertEquals(1f, first.opacity, 0.001f)
        assertNull(first.customColor)

        val ids = parsed[0].cells.map { it.id }.toSet()
        assertEquals(3, ids.size)
        assertTrue(ids.none { it.isBlank() })
        assertEquals(CellType.KEY, parsed[0].cells[1].type)
        assertEquals(0f, parsed[0].cells[1].xPct, 0.001f)
    }

    @Test
    fun `profile withDimensions trims out of bounds cells`() {
        val profile = GameProfile(
            name = "Test",
            rows = 4,
            cols = 4,
            cells = listOf(
                CellConfig(row = 0, col = 0, label = "Keep", type = CellType.KEY, keys = listOf(1)),
                CellConfig(row = 3, col = 3, label = "Drop", type = CellType.KEY, keys = listOf(2)),
            ),
        )

        val shrunk = profile.withDimensions(2, 2)
        assertEquals(2, shrunk.rows)
        assertEquals(2, shrunk.cols)
        assertEquals(1, shrunk.cells.size)
        assertEquals("Keep", shrunk.cells[0].label)
    }

    @Test
    fun `normalizedCells distributes legacy zero-coordinate cells`() {
        val legacy = GameProfile(
            name = "Legacy",
            rows = 2,
            cols = 2,
            cells = listOf(
                CellConfig(row = 0, col = 0, label = "TopLeft", type = CellType.KEY, keys = listOf(1)),
                CellConfig(row = 1, col = 1, label = "BottomRight", type = CellType.KEY, keys = listOf(2)),
            ),
        )

        val normalized = legacy.normalizedCells()
        assertEquals(2, normalized.size)
        assertTrue(normalized[0].xPct >= 0.05f)
        assertTrue(normalized[0].yPct >= 0.15f)
        assertTrue(normalized[1].xPct > normalized[0].xPct)
        assertTrue(normalized[1].yPct > normalized[0].yPct)
    }

    @Test
    fun `macro cell roundtrip preserves steps and repeat count`() {
        val original = GameProfile(
            id = "macro-profile",
            name = "Macro Test",
            cells = listOf(
                CellConfig(
                    id = "macro-cell",
                    label = "SHIFT-CLICK",
                    type = CellType.MACRO,
                    macro = MacroConfig(
                        steps = listOf(
                            MacroStep(
                                MacroStepType.HOLD,
                                listOf(Keycodes.KEY_LEFTSHIFT),
                                delayMs = 20,
                            ),
                            MacroStep(
                                MacroStepType.TAP,
                                listOf(Keycodes.KEY_W),
                                holdMs = 80,
                                delayMs = 30,
                            ),
                            MacroStep(
                                MacroStepType.RELEASE,
                                listOf(Keycodes.KEY_LEFTSHIFT),
                                delayMs = 10,
                            ),
                            MacroStep(MacroStepType.WAIT, delayMs = 250),
                        ),
                        repeatCount = 2,
                    ),
                    xPct = 0.5f,
                    yPct = 0.5f,
                ),
            ),
        )

        val parsed = ProfileJson.parse(ProfileJson.serialize(original))
        val cell = parsed.cells.single()
        assertEquals(CellType.MACRO, cell.type)
        assertEquals(2, cell.macro.repeatCount)
        assertEquals(4, cell.macro.steps.size)
        assertEquals(MacroStepType.HOLD, cell.macro.steps[0].type)
        assertEquals(listOf(Keycodes.KEY_LEFTSHIFT), cell.macro.steps[0].keys)
        assertEquals(MacroStepType.TAP, cell.macro.steps[1].type)
        assertEquals(80, cell.macro.steps[1].holdMs)
        assertEquals(MacroStepType.RELEASE, cell.macro.steps[2].type)
        assertEquals(MacroStepType.WAIT, cell.macro.steps[3].type)
        assertTrue(cell.macro.steps[3].keys.isEmpty())
    }

    @Test
    fun `legacy macro cell without macro object decodes empty`() {
        val json = """
            {
              "name": "Legacy Macro",
              "cells": [
                {
                  "id": "m1",
                  "label": "MACRO",
                  "type": "MACRO",
                  "keys": [],
                  "mouseButtons": [],
                  "xPct": 0.4,
                  "yPct": 0.4
                }
              ]
            }
        """.trimIndent()

        val parsed = ProfileJson.parse(json)
        val cell = parsed.cells.single()
        assertEquals(CellType.MACRO, cell.type)
        assertEquals(1, cell.macro.repeatCount)
        assertTrue(cell.macro.steps.isEmpty())
    }

    @Test
    fun `hostile macro import is clamped`() {
        val steps = (0 until 40).joinToString(",") {
            """{"type":"TAP","keys":[9999,${Keycodes.KEY_W}],"holdMs":99999,"delayMs":-5}"""
        }
        val json = """
            {
              "name": "Hostile Macro",
              "cells": [
                {
                  "id": "m1",
                  "label": "MACRO",
                  "type": "MACRO",
                  "keys": [],
                  "mouseButtons": [],
                  "macro": {
                    "repeatCount": 999,
                    "steps": [$steps]
                  }
                }
              ]
            }
        """.trimIndent()

        val macro = ProfileJson.parse(json).cells.single().macro
        assertTrue(macro.steps.size <= MacroLimits.MAX_STEPS)
        assertEquals(1, macro.steps.first().keys.size)
        assertEquals(Keycodes.KEY_W, macro.steps.first().keys.first())
        assertEquals(MacroLimits.MAX_HOLD_MS, macro.steps.first().holdMs)
        assertEquals(0, macro.steps.first().delayMs)
        assertTrue(macro.repeatCount <= MacroLimits.MAX_REPEAT)
        assertTrue(macro.estimatedDurationMs <= MacroLimits.MAX_DURATION_MS)
    }
}
