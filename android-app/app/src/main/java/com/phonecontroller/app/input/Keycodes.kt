package com.phonecontroller.app.input

/**
 * Comprehensive canonical evdev key code definitions (input-event-codes.h)
 * covering the full standard PC keyboard, navigation, numeric keypad,
 * function keys, and media controls.
 */
object Keycodes {
    // --- Modifiers ---
    const val KEY_ESC = 1
    const val KEY_LEFTCTRL = 29
    const val KEY_LEFTSHIFT = 42
    const val KEY_LEFTALT = 56
    const val KEY_LEFTMETA = 125 // Windows / Super key
    const val KEY_RIGHTCTRL = 97
    const val KEY_RIGHTSHIFT = 54
    const val KEY_RIGHTALT = 100
    const val KEY_RIGHTMETA = 126
    const val KEY_CAPSLOCK = 58
    const val KEY_TAB = 15

    // --- Editing & Whitespace ---
    const val KEY_BACKSPACE = 14
    const val KEY_ENTER = 28
    const val KEY_SPACE = 57
    const val KEY_INSERT = 110
    const val KEY_DELETE = 111

    // --- Navigation ---
    const val KEY_UP = 103
    const val KEY_DOWN = 108
    const val KEY_LEFT = 105
    const val KEY_RIGHT = 106
    const val KEY_HOME = 102
    const val KEY_END = 107
    const val KEY_PAGEUP = 104
    const val KEY_PAGEDOWN = 109

    // --- Digits (Main Row) ---
    const val KEY_1 = 2
    const val KEY_2 = 3
    const val KEY_3 = 4
    const val KEY_4 = 5
    const val KEY_5 = 6
    const val KEY_6 = 7
    const val KEY_7 = 8
    const val KEY_8 = 9
    const val KEY_9 = 10
    const val KEY_0 = 11

    // --- Punctuation & Symbols ---
    const val KEY_GRAVE = 41       // ` and ~
    const val KEY_MINUS = 12       // - and _
    const val KEY_EQUAL = 13       // = and +
    const val KEY_LEFTBRACE = 26   // [ and {
    const val KEY_RIGHTBRACE = 27  // ] and }
    const val KEY_BACKSLASH = 43   // \ and |
    const val KEY_SEMICOLON = 39   // ; and :
    const val KEY_APOSTROPHE = 40  // ' and "
    const val KEY_COMMA = 51       // , and <
    const val KEY_DOT = 52         // . and >
    const val KEY_SLASH = 53       // / and ?

    // --- Letters A–Z (evdev qwerty mapping) ---
    const val KEY_Q = 16
    const val KEY_W = 17
    const val KEY_E = 18
    const val KEY_R = 19
    const val KEY_T = 20
    const val KEY_Y = 21
    const val KEY_U = 22
    const val KEY_I = 23
    const val KEY_O = 24
    const val KEY_P = 25
    const val KEY_A = 30
    const val KEY_S = 31
    const val KEY_D = 32
    const val KEY_F = 33
    const val KEY_G = 34
    const val KEY_H = 35
    const val KEY_J = 36
    const val KEY_K = 37
    const val KEY_L = 38
    const val KEY_Z = 44
    const val KEY_X = 45
    const val KEY_C = 46
    const val KEY_V = 47
    const val KEY_B = 48
    const val KEY_N = 49
    const val KEY_M = 50

    // --- Numeric Keypad ---
    const val KEY_NUMLOCK = 69
    const val KEY_KPSLASH = 98     // Numpad /
    const val KEY_KPASTERISK = 55  // Numpad *
    const val KEY_KPMINUS = 74     // Numpad -
    const val KEY_KPPLUS = 78      // Numpad +
    const val KEY_KPENTER = 96     // Numpad Enter
    const val KEY_KPDOT = 83       // Numpad .
    const val KEY_KP7 = 71
    const val KEY_KP8 = 72
    const val KEY_KP9 = 73
    const val KEY_KP4 = 75
    const val KEY_KP5 = 76
    const val KEY_KP6 = 77
    const val KEY_KP1 = 79
    const val KEY_KP2 = 80
    const val KEY_KP3 = 81
    const val KEY_KP0 = 82

    // --- Function Keys ---
    const val KEY_F1 = 59
    const val KEY_F2 = 60
    const val KEY_F3 = 61
    const val KEY_F4 = 62
    const val KEY_F5 = 63
    const val KEY_F6 = 64
    const val KEY_F7 = 65
    const val KEY_F8 = 66
    const val KEY_F9 = 67
    const val KEY_F10 = 68
    const val KEY_F11 = 87
    const val KEY_F12 = 88

    // --- System & Media ---
    const val KEY_SYSRQ = 99       // Print Screen
    const val KEY_SCROLLLOCK = 70
    const val KEY_PAUSE = 119
    const val KEY_MUTE = 113
    const val KEY_VOLUMEDOWN = 114
    const val KEY_VOLUMEUP = 115
    const val KEY_PLAYPAUSE = 164
    const val KEY_NEXTSONG = 163
    const val KEY_PREVIOUSSONG = 165
    const val KEY_STOPCD = 166

    data class KeyInfo(
        val code: Int,
        val label: String,
        val description: String,
        val category: String,
    )

    val ALL_KEYS: List<KeyInfo> = listOf(
        // Movement
        KeyInfo(KEY_W, "W", "Move Forward / Up", "Movement"),
        KeyInfo(KEY_A, "A", "Move Left", "Movement"),
        KeyInfo(KEY_S, "S", "Move Backward / Down", "Movement"),
        KeyInfo(KEY_D, "D", "Move Right", "Movement"),
        KeyInfo(KEY_UP, "↑", "Arrow Up", "Movement"),
        KeyInfo(KEY_DOWN, "↓", "Arrow Down", "Movement"),
        KeyInfo(KEY_LEFT, "←", "Arrow Left", "Movement"),
        KeyInfo(KEY_RIGHT, "→", "Arrow Right", "Movement"),

        // Action Keys
        KeyInfo(KEY_SPACE, "SPACE", "Jump / Spacebar", "Actions"),
        KeyInfo(KEY_E, "E", "Interact / Use", "Actions"),
        KeyInfo(KEY_R, "R", "Reload", "Actions"),
        KeyInfo(KEY_F, "F", "Melee / Flashlight", "Actions"),
        KeyInfo(KEY_Q, "Q", "Ability / Lean Left", "Actions"),
        KeyInfo(KEY_C, "C", "Crouch / Prone", "Actions"),
        KeyInfo(KEY_V, "V", "Melee / Voice", "Actions"),
        KeyInfo(KEY_X, "X", "Prone / Stance", "Actions"),
        KeyInfo(KEY_Z, "Z", "Prone / Ping", "Actions"),
        KeyInfo(KEY_G, "G", "Grenade", "Actions"),
        KeyInfo(KEY_T, "T", "Chat / Flashlight", "Actions"),
        KeyInfo(KEY_B, "B", "Buy / Fire Mode", "Actions"),
        KeyInfo(KEY_M, "M", "Map", "Actions"),
        KeyInfo(KEY_TAB, "TAB", "Tab / Scoreboard", "Actions"),
        KeyInfo(KEY_ESC, "ESC", "Escape / Pause Menu", "Actions"),
        KeyInfo(KEY_ENTER, "ENTER", "Enter / Return", "Actions"),
        KeyInfo(KEY_BACKSPACE, "BKSP", "Backspace", "Actions"),

        // Modifiers
        KeyInfo(KEY_LEFTSHIFT, "L-SHIFT", "Left Shift (Sprint)", "Modifiers"),
        KeyInfo(KEY_LEFTCTRL, "L-CTRL", "Left Control (Crouch)", "Modifiers"),
        KeyInfo(KEY_LEFTALT, "L-ALT", "Left Alt", "Modifiers"),
        KeyInfo(KEY_LEFTMETA, "L-WIN", "Left Windows / Super", "Modifiers"),
        KeyInfo(KEY_RIGHTSHIFT, "R-SHIFT", "Right Shift", "Modifiers"),
        KeyInfo(KEY_RIGHTCTRL, "R-CTRL", "Right Control", "Modifiers"),
        KeyInfo(KEY_RIGHTALT, "R-ALT", "Right Alt", "Modifiers"),
        KeyInfo(KEY_RIGHTMETA, "R-WIN", "Right Windows / Super", "Modifiers"),
        KeyInfo(KEY_CAPSLOCK, "CAPS", "Caps Lock", "Modifiers"),

        // Letters A-Z
        KeyInfo(KEY_A, "A", "Letter A", "Letters"),
        KeyInfo(KEY_B, "B", "Letter B", "Letters"),
        KeyInfo(KEY_C, "C", "Letter C", "Letters"),
        KeyInfo(KEY_D, "D", "Letter D", "Letters"),
        KeyInfo(KEY_E, "E", "Letter E", "Letters"),
        KeyInfo(KEY_F, "F", "Letter F", "Letters"),
        KeyInfo(KEY_G, "G", "Letter G", "Letters"),
        KeyInfo(KEY_H, "H", "Letter H", "Letters"),
        KeyInfo(KEY_I, "I", "Letter I", "Letters"),
        KeyInfo(KEY_J, "J", "Letter J", "Letters"),
        KeyInfo(KEY_K, "K", "Letter K", "Letters"),
        KeyInfo(KEY_L, "L", "Letter L", "Letters"),
        KeyInfo(KEY_M, "M", "Letter M", "Letters"),
        KeyInfo(KEY_N, "N", "Letter N", "Letters"),
        KeyInfo(KEY_O, "O", "Letter O", "Letters"),
        KeyInfo(KEY_P, "P", "Letter P", "Letters"),
        KeyInfo(KEY_Q, "Q", "Letter Q", "Letters"),
        KeyInfo(KEY_R, "R", "Letter R", "Letters"),
        KeyInfo(KEY_S, "S", "Letter S", "Letters"),
        KeyInfo(KEY_T, "T", "Letter T", "Letters"),
        KeyInfo(KEY_U, "U", "Letter U", "Letters"),
        KeyInfo(KEY_V, "V", "Letter V", "Letters"),
        KeyInfo(KEY_W, "W", "Letter W", "Letters"),
        KeyInfo(KEY_X, "X", "Letter X", "Letters"),
        KeyInfo(KEY_Y, "Y", "Letter Y", "Letters"),
        KeyInfo(KEY_Z, "Z", "Letter Z", "Letters"),

        // Digits
        KeyInfo(KEY_1, "1", "Digit 1", "Numbers"),
        KeyInfo(KEY_2, "2", "Digit 2", "Numbers"),
        KeyInfo(KEY_3, "3", "Digit 3", "Numbers"),
        KeyInfo(KEY_4, "4", "Digit 4", "Numbers"),
        KeyInfo(KEY_5, "5", "Digit 5", "Numbers"),
        KeyInfo(KEY_6, "6", "Digit 6", "Numbers"),
        KeyInfo(KEY_7, "7", "Digit 7", "Numbers"),
        KeyInfo(KEY_8, "8", "Digit 8", "Numbers"),
        KeyInfo(KEY_9, "9", "Digit 9", "Numbers"),
        KeyInfo(KEY_0, "0", "Digit 0", "Numbers"),

        // Numeric Keypad
        KeyInfo(KEY_NUMLOCK, "NUM-LK", "Num Lock", "Numpad"),
        KeyInfo(KEY_KP0, "NUM 0", "Numpad 0", "Numpad"),
        KeyInfo(KEY_KP1, "NUM 1", "Numpad 1", "Numpad"),
        KeyInfo(KEY_KP2, "NUM 2", "Numpad 2", "Numpad"),
        KeyInfo(KEY_KP3, "NUM 3", "Numpad 3", "Numpad"),
        KeyInfo(KEY_KP4, "NUM 4", "Numpad 4", "Numpad"),
        KeyInfo(KEY_KP5, "NUM 5", "Numpad 5", "Numpad"),
        KeyInfo(KEY_KP6, "NUM 6", "Numpad 6", "Numpad"),
        KeyInfo(KEY_KP7, "NUM 7", "Numpad 7", "Numpad"),
        KeyInfo(KEY_KP8, "NUM 8", "Numpad 8", "Numpad"),
        KeyInfo(KEY_KP9, "NUM 9", "Numpad 9", "Numpad"),
        KeyInfo(KEY_KPDOT, "NUM .", "Numpad Decimal", "Numpad"),
        KeyInfo(KEY_KPPLUS, "NUM +", "Numpad Plus", "Numpad"),
        KeyInfo(KEY_KPMINUS, "NUM -", "Numpad Minus", "Numpad"),
        KeyInfo(KEY_KPASTERISK, "NUM *", "Numpad Multiply", "Numpad"),
        KeyInfo(KEY_KPSLASH, "NUM /", "Numpad Divide", "Numpad"),
        KeyInfo(KEY_KPENTER, "NUM-ENT", "Numpad Enter", "Numpad"),

        // Navigation & Editing
        KeyInfo(KEY_INSERT, "INS", "Insert", "Navigation"),
        KeyInfo(KEY_DELETE, "DEL", "Delete", "Navigation"),
        KeyInfo(KEY_HOME, "HOME", "Home", "Navigation"),
        KeyInfo(KEY_END, "END", "End", "Navigation"),
        KeyInfo(KEY_PAGEUP, "PGUP", "Page Up", "Navigation"),
        KeyInfo(KEY_PAGEDOWN, "PGDN", "Page Down", "Navigation"),

        // Symbols
        KeyInfo(KEY_GRAVE, "` / ~", "Tilde / Backtick", "Symbols"),
        KeyInfo(KEY_MINUS, "- / _", "Minus / Underscore", "Symbols"),
        KeyInfo(KEY_EQUAL, "= / +", "Equal / Plus", "Symbols"),
        KeyInfo(KEY_LEFTBRACE, "[ / {", "Left Bracket", "Symbols"),
        KeyInfo(KEY_RIGHTBRACE, "] / }", "Right Bracket", "Symbols"),
        KeyInfo(KEY_BACKSLASH, "\\ / |", "Backslash", "Symbols"),
        KeyInfo(KEY_SEMICOLON, "; / :", "Semicolon", "Symbols"),
        KeyInfo(KEY_APOSTROPHE, "' / \"", "Apostrophe / Quote", "Symbols"),
        KeyInfo(KEY_COMMA, ", / <", "Comma / Less Than", "Symbols"),
        KeyInfo(KEY_DOT, ". / >", "Period / Greater Than", "Symbols"),
        KeyInfo(KEY_SLASH, "/ / ?", "Slash / Question Mark", "Symbols"),

        // Function Keys
        KeyInfo(KEY_F1, "F1", "Function 1", "Function"),
        KeyInfo(KEY_F2, "F2", "Function 2", "Function"),
        KeyInfo(KEY_F3, "F3", "Function 3", "Function"),
        KeyInfo(KEY_F4, "F4", "Function 4", "Function"),
        KeyInfo(KEY_F5, "F5", "Function 5 (Quick Save)", "Function"),
        KeyInfo(KEY_F6, "F6", "Function 6", "Function"),
        KeyInfo(KEY_F7, "F7", "Function 7", "Function"),
        KeyInfo(KEY_F8, "F8", "Function 8", "Function"),
        KeyInfo(KEY_F9, "F9", "Function 9 (Quick Load)", "Function"),
        KeyInfo(KEY_F10, "F10", "Function 10", "Function"),
        KeyInfo(KEY_F11, "F11", "Function 11 (Fullscreen)", "Function"),
        KeyInfo(KEY_F12, "F12", "Function 12 (Screenshot)", "Function"),

        // System & Media
        KeyInfo(KEY_SYSRQ, "PRTSC", "Print Screen / SysRq", "System & Media"),
        KeyInfo(KEY_SCROLLLOCK, "SCR-LK", "Scroll Lock", "System & Media"),
        KeyInfo(KEY_PAUSE, "PAUSE", "Pause / Break", "System & Media"),
        KeyInfo(KEY_MUTE, "MUTE", "Audio Mute", "System & Media"),
        KeyInfo(KEY_VOLUMEDOWN, "VOL -", "Volume Down", "System & Media"),
        KeyInfo(KEY_VOLUMEUP, "VOL +", "Volume Up", "System & Media"),
        KeyInfo(KEY_PLAYPAUSE, "PLAY", "Play / Pause Media", "System & Media"),
        KeyInfo(KEY_NEXTSONG, "NEXT", "Next Track", "System & Media"),
        KeyInfo(KEY_PREVIOUSSONG, "PREV", "Previous Track", "System & Media"),
    )

    private val byCode: Map<Int, KeyInfo> = ALL_KEYS.associateBy { it.code }

    fun find(code: Int): KeyInfo? = byCode[code]

    fun labelFor(code: Int): String = byCode[code]?.label ?: "KEY_$code"
}
