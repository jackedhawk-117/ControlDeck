package com.phonecontroller.app.profile

import com.phonecontroller.app.input.Keycodes
import com.phonecontroller.app.input.TouchpadConfig
import com.phonecontroller.app.net.Protocol

/**
 * Factory for built-in, unconstrained canvas gaming profiles (spec §3, §5, §7).
 *
 * Each preset positions buttons and analog controls in ergonomic mobile-gaming
 * thumb zones:
 * - Left thumb zone: virtual analog joystick (WASD) and stance/sprint buttons.
 * - Right thumb zone: camera swipe area, primary trigger (LMB), aim (RMB), jump, reload.
 */
object DefaultProfiles {

    const val FPS_PROFILE_ID = "profile_fps"
    const val THIRD_PERSON_PROFILE_ID = "profile_third_person"
    const val RACING_PROFILE_ID = "profile_racing"
    const val STRATEGY_PROFILE_ID = "profile_strategy"
    const val GENERAL_PROFILE_ID = "profile_general"

    fun createAll(): List<GameProfile> = listOf(
        fpsProfile(),
        thirdPersonProfile(),
        racingProfile(),
        strategyProfile(),
        generalProfile(),
    )

    fun fpsProfile(): GameProfile {
        val cells = listOf(
            // Left Thumb Zone: Virtual Joystick + Movement
            CellConfig(
                id = "fps_joystick",
                label = "WASD",
                type = CellType.JOYSTICK,
                xPct = 0.16f,
                yPct = 0.68f,
                widthDp = 130,
                heightDp = 130,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "fps_sprint",
                label = "SPRINT",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_LEFTSHIFT),
                xPct = 0.16f,
                yPct = 0.28f,
                widthDp = 64,
                heightDp = 48,
                shape = ButtonShape.PILL,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "fps_crouch",
                label = "CROUCH",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_C),
                xPct = 0.28f,
                yPct = 0.82f,
                widthDp = 56,
                heightDp = 56,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "fps_prone",
                label = "PRONE",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_Z),
                xPct = 0.08f,
                yPct = 0.88f,
                widthDp = 50,
                heightDp = 50,
                shape = ButtonShape.CIRCLE,
                opacity = 0.80f,
            ),
            CellConfig(
                id = "fps_reload",
                label = "R",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_R),
                xPct = 0.30f,
                yPct = 0.48f,
                widthDp = 56,
                heightDp = 56,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),

            // Right Thumb Zone: Actions & Combat
            CellConfig(
                id = "fps_jump",
                label = "JUMP",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_SPACE),
                xPct = 0.90f,
                yPct = 0.72f,
                widthDp = 70,
                heightDp = 70,
                shape = ButtonShape.CIRCLE,
                opacity = 0.90f,
            ),
            CellConfig(
                id = "fps_fire",
                label = "FIRE",
                type = CellType.MOUSE_BUTTON,
                mouseButtons = listOf(Protocol.MOUSE_LEFT),
                xPct = 0.88f,
                yPct = 0.42f,
                widthDp = 72,
                heightDp = 72,
                shape = ButtonShape.CIRCLE,
                opacity = 0.90f,
            ),
            CellConfig(
                id = "fps_aim",
                label = "AIM",
                type = CellType.MOUSE_BUTTON,
                mouseButtons = listOf(Protocol.MOUSE_RIGHT),
                isToggle = true,
                xPct = 0.76f,
                yPct = 0.28f,
                widthDp = 62,
                heightDp = 62,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "fps_use",
                label = "E",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_E),
                xPct = 0.76f,
                yPct = 0.58f,
                widthDp = 56,
                heightDp = 56,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "fps_melee",
                label = "MELEE",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_F),
                xPct = 0.82f,
                yPct = 0.86f,
                widthDp = 54,
                heightDp = 54,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "fps_mmb",
                label = "MMB",
                type = CellType.MOUSE_BUTTON,
                mouseButtons = listOf(Protocol.MOUSE_MIDDLE),
                xPct = 0.65f,
                yPct = 0.28f,
                widthDp = 54,
                heightDp = 54,
                shape = ButtonShape.CIRCLE,
                opacity = 0.75f,
            ),
        )
        return GameProfile(
            id = FPS_PROFILE_ID,
            name = "First-Person Shooter",
            description = "Unconstrained canvas layout with analog WASD joystick, jump, sprint, and aim/fire triggers.",
            rows = 3,
            cols = 4,
            cells = cells,
            touchpad = TouchpadConfig(sensitivityX = 0.85f, sensitivityY = 0.85f, acceleration = 0.20f),
        )
    }

    fun thirdPersonProfile(): GameProfile {
        val cells = listOf(
            CellConfig(
                id = "tp_joystick",
                label = "WASD",
                type = CellType.JOYSTICK,
                xPct = 0.16f,
                yPct = 0.68f,
                widthDp = 130,
                heightDp = 130,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "tp_lockon",
                label = "LOCK-ON",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_Q),
                xPct = 0.16f,
                yPct = 0.28f,
                widthDp = 64,
                heightDp = 48,
                shape = ButtonShape.PILL,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "tp_heal",
                label = "HEAL",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_1),
                xPct = 0.28f,
                yPct = 0.82f,
                widthDp = 56,
                heightDp = 56,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "tp_invent",
                label = "TAB",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_TAB),
                xPct = 0.08f,
                yPct = 0.30f,
                widthDp = 56,
                heightDp = 48,
                shape = ButtonShape.PILL,
                opacity = 0.80f,
            ),
            CellConfig(
                id = "tp_dodge",
                label = "DODGE",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_SPACE),
                xPct = 0.90f,
                yPct = 0.72f,
                widthDp = 70,
                heightDp = 70,
                shape = ButtonShape.CIRCLE,
                opacity = 0.90f,
            ),
            CellConfig(
                id = "tp_attack",
                label = "ATTACK",
                type = CellType.MOUSE_BUTTON,
                mouseButtons = listOf(Protocol.MOUSE_LEFT),
                xPct = 0.88f,
                yPct = 0.42f,
                widthDp = 72,
                heightDp = 72,
                shape = ButtonShape.CIRCLE,
                opacity = 0.90f,
            ),
            CellConfig(
                id = "tp_heavy",
                label = "HEAVY",
                type = CellType.MOUSE_BUTTON,
                mouseButtons = listOf(Protocol.MOUSE_RIGHT),
                xPct = 0.76f,
                yPct = 0.28f,
                widthDp = 62,
                heightDp = 62,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "tp_use",
                label = "USE",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_E),
                xPct = 0.76f,
                yPct = 0.58f,
                widthDp = 56,
                heightDp = 56,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(
                id = "tp_crouch",
                label = "CROUCH",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_C),
                xPct = 0.82f,
                yPct = 0.86f,
                widthDp = 54,
                heightDp = 54,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
        )
        return GameProfile(
            id = THIRD_PERSON_PROFILE_ID,
            name = "Third-Person Action",
            description = "Combat and exploration layout with dodge roll, lock-on, and quick items.",
            rows = 3,
            cols = 4,
            cells = cells,
            touchpad = TouchpadConfig(sensitivityX = 0.90f, sensitivityY = 0.90f, smoothing = 0.40f),
        )
    }

    fun racingProfile(): GameProfile {
        val cells = listOf(
            CellConfig(
                id = "race_left",
                label = "LEFT",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_A),
                xPct = 0.14f,
                yPct = 0.65f,
                widthDp = 80,
                heightDp = 80,
                shape = ButtonShape.CIRCLE,
            ),
            CellConfig(
                id = "race_right",
                label = "RIGHT",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_D),
                xPct = 0.28f,
                yPct = 0.65f,
                widthDp = 80,
                heightDp = 80,
                shape = ButtonShape.CIRCLE,
            ),
            CellConfig(
                id = "race_rear",
                label = "REAR",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_B),
                xPct = 0.14f,
                yPct = 0.35f,
                widthDp = 65,
                heightDp = 50,
                shape = ButtonShape.PILL,
            ),
            CellConfig(
                id = "race_reset",
                label = "RESET",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_R),
                xPct = 0.28f,
                yPct = 0.35f,
                widthDp = 65,
                heightDp = 50,
                shape = ButtonShape.PILL,
            ),
            CellConfig(
                id = "race_gas",
                label = "GAS",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_W),
                xPct = 0.88f,
                yPct = 0.45f,
                widthDp = 80,
                heightDp = 85,
                shape = ButtonShape.ROUNDED_RECT,
            ),
            CellConfig(
                id = "race_brake",
                label = "BRAKE",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_S),
                xPct = 0.88f,
                yPct = 0.76f,
                widthDp = 80,
                heightDp = 75,
                shape = ButtonShape.ROUNDED_RECT,
            ),
            CellConfig(
                id = "race_handbrake",
                label = "HAND\nBRAKE",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_SPACE),
                xPct = 0.74f,
                yPct = 0.78f,
                widthDp = 70,
                heightDp = 60,
                shape = ButtonShape.PILL,
            ),
            CellConfig(
                id = "race_nitro",
                label = "NITRO",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_LEFTSHIFT),
                xPct = 0.74f,
                yPct = 0.52f,
                widthDp = 70,
                heightDp = 55,
                shape = ButtonShape.PILL,
            ),
        )
        return GameProfile(
            id = RACING_PROFILE_ID,
            name = "Driving & Racing",
            description = "Dedicated layout for racing titles with throttle, steering, nitro, and handbrake.",
            rows = 3,
            cols = 4,
            cells = cells,
            touchpad = TouchpadConfig(sensitivityX = 0.70f, sensitivityY = 0.70f, widthFraction = 0.45f),
        )
    }

    fun strategyProfile(): GameProfile {
        val cells = listOf(
            CellConfig(id = "strat_g1", label = "GRP 1", type = CellType.KEY, keys = listOf(Keycodes.KEY_1), xPct = 0.12f, yPct = 0.22f, widthDp = 60, heightDp = 46, shape = ButtonShape.PILL),
            CellConfig(id = "strat_g2", label = "GRP 2", type = CellType.KEY, keys = listOf(Keycodes.KEY_2), xPct = 0.24f, yPct = 0.22f, widthDp = 60, heightDp = 46, shape = ButtonShape.PILL),
            CellConfig(id = "strat_g3", label = "GRP 3", type = CellType.KEY, keys = listOf(Keycodes.KEY_3), xPct = 0.36f, yPct = 0.22f, widthDp = 60, heightDp = 46, shape = ButtonShape.PILL),
            CellConfig(id = "strat_g4", label = "GRP 4", type = CellType.KEY, keys = listOf(Keycodes.KEY_4), xPct = 0.48f, yPct = 0.22f, widthDp = 60, heightDp = 46, shape = ButtonShape.PILL),
            CellConfig(id = "strat_attack", label = "ATTACK", type = CellType.KEY, keys = listOf(Keycodes.KEY_A), xPct = 0.15f, yPct = 0.55f, widthDp = 68, heightDp = 68, shape = ButtonShape.CIRCLE),
            CellConfig(id = "strat_stop", label = "STOP", type = CellType.KEY, keys = listOf(Keycodes.KEY_S), xPct = 0.28f, yPct = 0.55f, widthDp = 68, heightDp = 68, shape = ButtonShape.CIRCLE),
            CellConfig(id = "strat_build", label = "BUILD", type = CellType.KEY, keys = listOf(Keycodes.KEY_B), xPct = 0.15f, yPct = 0.78f, widthDp = 68, heightDp = 68, shape = ButtonShape.CIRCLE),
            CellConfig(id = "strat_queue", label = "QUEUE", type = CellType.KEY, keys = listOf(Keycodes.KEY_LEFTSHIFT), xPct = 0.28f, yPct = 0.78f, widthDp = 68, heightDp = 68, shape = ButtonShape.CIRCLE),
            CellConfig(id = "strat_select", label = "SELECT", type = CellType.MOUSE_BUTTON, mouseButtons = listOf(Protocol.MOUSE_LEFT), xPct = 0.88f, yPct = 0.50f, widthDp = 75, heightDp = 75, shape = ButtonShape.CIRCLE),
            CellConfig(id = "strat_command", label = "COMMAND", type = CellType.MOUSE_BUTTON, mouseButtons = listOf(Protocol.MOUSE_RIGHT), xPct = 0.76f, yPct = 0.75f, widthDp = 75, heightDp = 75, shape = ButtonShape.CIRCLE),
        )
        return GameProfile(
            id = STRATEGY_PROFILE_ID,
            name = "Strategy / RTS",
            description = "Commands, unit groups, and queueing for RTS and grand strategy games.",
            rows = 3,
            cols = 4,
            cells = cells,
            touchpad = TouchpadConfig(sensitivityX = 1.0f, sensitivityY = 1.0f, acceleration = 0.30f),
        )
    }

    fun generalProfile(): GameProfile {
        val cells = listOf(
            CellConfig(
                id = "gen_joystick",
                label = "WASD",
                type = CellType.JOYSTICK,
                xPct = 0.16f,
                yPct = 0.68f,
                widthDp = 130,
                heightDp = 130,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            ),
            CellConfig(id = "gen_shift", label = "SHIFT", type = CellType.KEY, keys = listOf(Keycodes.KEY_LEFTSHIFT), xPct = 0.16f, yPct = 0.28f, widthDp = 64, heightDp = 48, shape = ButtonShape.PILL),
            CellConfig(id = "gen_ctrl", label = "CTRL", type = CellType.KEY, keys = listOf(Keycodes.KEY_LEFTCTRL), xPct = 0.28f, yPct = 0.82f, widthDp = 56, heightDp = 56, shape = ButtonShape.CIRCLE),
            CellConfig(id = "gen_space", label = "SPACE", type = CellType.KEY, keys = listOf(Keycodes.KEY_SPACE), xPct = 0.90f, yPct = 0.72f, widthDp = 70, heightDp = 70, shape = ButtonShape.CIRCLE),
            CellConfig(id = "gen_lmb", label = "LMB", type = CellType.MOUSE_BUTTON, mouseButtons = listOf(Protocol.MOUSE_LEFT), xPct = 0.88f, yPct = 0.42f, widthDp = 70, heightDp = 70, shape = ButtonShape.CIRCLE),
            CellConfig(id = "gen_rmb", label = "RMB", type = CellType.MOUSE_BUTTON, mouseButtons = listOf(Protocol.MOUSE_RIGHT), xPct = 0.76f, yPct = 0.28f, widthDp = 62, heightDp = 62, shape = ButtonShape.CIRCLE),
            CellConfig(id = "gen_e", label = "E", type = CellType.KEY, keys = listOf(Keycodes.KEY_E), xPct = 0.76f, yPct = 0.58f, widthDp = 56, heightDp = 56, shape = ButtonShape.CIRCLE),
            CellConfig(id = "gen_tab", label = "TAB", type = CellType.KEY, keys = listOf(Keycodes.KEY_TAB), xPct = 0.08f, yPct = 0.30f, widthDp = 56, heightDp = 48, shape = ButtonShape.PILL),
            CellConfig(id = "gen_esc", label = "ESC", type = CellType.KEY, keys = listOf(Keycodes.KEY_ESC), xPct = 0.08f, yPct = 0.14f, widthDp = 50, heightDp = 38, shape = ButtonShape.ROUNDED_RECT),
        )
        return GameProfile(
            id = GENERAL_PROFILE_ID,
            name = "General WASD + Mouse",
            description = "Versatile baseline keyboard and mouse configuration for all PC titles.",
            rows = 3,
            cols = 4,
            cells = cells,
        )
    }
}
