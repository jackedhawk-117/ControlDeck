//! Canonical evdev key code constants (`input-event-codes.h`) used across
//! phone, receiver, and simulator. The Windows backend maps these to scan
//! codes; the Linux backend uses them verbatim.

#![allow(dead_code)]

// Modifiers
pub const KEY_ESC: u16 = 1;
pub const KEY_LEFTCTRL: u16 = 29;
pub const KEY_LEFTSHIFT: u16 = 42;
pub const KEY_LEFTALT: u16 = 56;
pub const KEY_LEFTMETA: u16 = 125;
pub const KEY_RIGHTCTRL: u16 = 97;
pub const KEY_RIGHTSHIFT: u16 = 54;
pub const KEY_RIGHTALT: u16 = 100;
pub const KEY_RIGHTMETA: u16 = 126;

// Control keys
pub const KEY_TAB: u16 = 15;
pub const KEY_BACKSPACE: u16 = 14;
pub const KEY_ENTER: u16 = 28;
pub const KEY_SPACE: u16 = 57;
pub const KEY_CAPSLOCK: u16 = 58;
pub const KEY_GRAVE: u16 = 41;
pub const KEY_MINUS: u16 = 12;
pub const KEY_EQUAL: u16 = 13;
pub const KEY_COMMA: u16 = 51;
pub const KEY_DOT: u16 = 52;
pub const KEY_SLASH: u16 = 53;
pub const KEY_SEMICOLON: u16 = 39;
pub const KEY_APOSTROPHE: u16 = 40;
pub const KEY_LEFTBRACE: u16 = 26;
pub const KEY_RIGHTBRACE: u16 = 27;
pub const KEY_BACKSLASH: u16 = 43;

// Digits
pub const KEY_1: u16 = 2;
pub const KEY_2: u16 = 3;
pub const KEY_3: u16 = 4;
pub const KEY_4: u16 = 5;
pub const KEY_5: u16 = 6;
pub const KEY_6: u16 = 7;
pub const KEY_7: u16 = 8;
pub const KEY_8: u16 = 9;
pub const KEY_9: u16 = 10;
pub const KEY_0: u16 = 11;

// Letters (evdev qwerty mapping)
pub const KEY_Q: u16 = 16;
pub const KEY_W: u16 = 17;
pub const KEY_E: u16 = 18;
pub const KEY_R: u16 = 19;
pub const KEY_T: u16 = 20;
pub const KEY_Y: u16 = 21;
pub const KEY_U: u16 = 22;
pub const KEY_I: u16 = 23;
pub const KEY_O: u16 = 24;
pub const KEY_P: u16 = 25;
pub const KEY_A: u16 = 30;
pub const KEY_S: u16 = 31;
pub const KEY_D: u16 = 32;
pub const KEY_F: u16 = 33;
pub const KEY_G: u16 = 34;
pub const KEY_H: u16 = 35;
pub const KEY_J: u16 = 36;
pub const KEY_K: u16 = 37;
pub const KEY_L: u16 = 38;
pub const KEY_Z: u16 = 44;
pub const KEY_X: u16 = 45;
pub const KEY_C: u16 = 46;
pub const KEY_V: u16 = 47;
pub const KEY_B: u16 = 48;
pub const KEY_N: u16 = 49;
pub const KEY_M: u16 = 50;

// Function keys
pub const KEY_F1: u16 = 59;
pub const KEY_F2: u16 = 60;
pub const KEY_F3: u16 = 61;
pub const KEY_F4: u16 = 62;
pub const KEY_F5: u16 = 63;
pub const KEY_F6: u16 = 64;
pub const KEY_F7: u16 = 65;
pub const KEY_F8: u16 = 66;
pub const KEY_F9: u16 = 67;
pub const KEY_F10: u16 = 68;
pub const KEY_F11: u16 = 87;
pub const KEY_F12: u16 = 88;

// Navigation
pub const KEY_UP: u16 = 103;
pub const KEY_DOWN: u16 = 108;
pub const KEY_LEFT: u16 = 105;
pub const KEY_RIGHT: u16 = 106;
pub const KEY_HOME: u16 = 102;
pub const KEY_END: u16 = 107;
pub const KEY_PAGEUP: u16 = 104;
pub const KEY_PAGEDOWN: u16 = 109;
pub const KEY_INSERT: u16 = 110;
pub const KEY_DELETE: u16 = 111;

// evdev mouse button codes (Linux backend uses these directly)
pub const BTN_LEFT: u16 = 0x110;
pub const BTN_RIGHT: u16 = 0x111;
pub const BTN_MIDDLE: u16 = 0x112;
pub const BTN_SIDE: u16 = 0x113;
pub const BTN_EXTRA: u16 = 0x114;

/// Highest evdev key code the receiver will accept on the wire.
pub const KEY_MAX: u16 = 767;

/// Human-readable name for common codes (logging + tests).
pub fn key_name(code: u16) -> &'static str {
    match code {
        KEY_ESC => "ESC",
        KEY_LEFTCTRL => "LCTRL",
        KEY_LEFTSHIFT => "LSHIFT",
        KEY_LEFTALT => "LALT",
        KEY_LEFTMETA => "LMETA",
        KEY_RIGHTCTRL => "RCTRL",
        KEY_RIGHTSHIFT => "RSHIFT",
        KEY_RIGHTALT => "RALT",
        KEY_RIGHTMETA => "RMETA",
        KEY_TAB => "TAB",
        KEY_BACKSPACE => "BKSP",
        KEY_ENTER => "ENTER",
        KEY_SPACE => "SPACE",
        KEY_CAPSLOCK => "CAPS",
        KEY_GRAVE => "GRAVE",
        KEY_MINUS => "MINUS",
        KEY_EQUAL => "EQUAL",
        KEY_COMMA => "COMMA",
        KEY_DOT => "DOT",
        KEY_SLASH => "SLASH",
        KEY_SEMICOLON => "SEMI",
        KEY_APOSTROPHE => "QUOTE",
        KEY_LEFTBRACE => "LBRACE",
        KEY_RIGHTBRACE => "RBRACE",
        KEY_BACKSLASH => "BKSLASH",
        KEY_1 => "1",
        KEY_2 => "2",
        KEY_3 => "3",
        KEY_4 => "4",
        KEY_5 => "5",
        KEY_6 => "6",
        KEY_7 => "7",
        KEY_8 => "8",
        KEY_9 => "9",
        KEY_0 => "0",
        KEY_Q => "Q",
        KEY_W => "W",
        KEY_E => "E",
        KEY_R => "R",
        KEY_T => "T",
        KEY_Y => "Y",
        KEY_U => "U",
        KEY_I => "I",
        KEY_O => "O",
        KEY_P => "P",
        KEY_A => "A",
        KEY_S => "S",
        KEY_D => "D",
        KEY_F => "F",
        KEY_G => "G",
        KEY_H => "H",
        KEY_J => "J",
        KEY_K => "K",
        KEY_L => "L",
        KEY_Z => "Z",
        KEY_X => "X",
        KEY_C => "C",
        KEY_V => "V",
        KEY_B => "B",
        KEY_N => "N",
        KEY_M => "M",
        KEY_F1 => "F1",
        KEY_F2 => "F2",
        KEY_F3 => "F3",
        KEY_F4 => "F4",
        KEY_F5 => "F5",
        KEY_F6 => "F6",
        KEY_F7 => "F7",
        KEY_F8 => "F8",
        KEY_F9 => "F9",
        KEY_F10 => "F10",
        KEY_F11 => "F11",
        KEY_F12 => "F12",
        KEY_UP => "UP",
        KEY_DOWN => "DOWN",
        KEY_LEFT => "LEFT",
        KEY_RIGHT => "RIGHT",
        KEY_HOME => "HOME",
        KEY_END => "END",
        KEY_PAGEUP => "PGUP",
        KEY_PAGEDOWN => "PGDN",
        KEY_INSERT => "INS",
        KEY_DELETE => "DEL",
        BTN_LEFT => "MB1",
        BTN_RIGHT => "MB2",
        BTN_MIDDLE => "MB3",
        BTN_SIDE => "MB4",
        BTN_EXTRA => "MB5",
        _ => "KEY?",
    }
}
