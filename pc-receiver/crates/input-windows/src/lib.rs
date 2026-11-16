//! Windows input backend: `SendInput` with hardware scan codes, declared via
//! a minimal FFI binding (no `windows` crate dependency — see ARCHITECTURE.md
//! rule D8 and the dependency rules).
//!
//! Compiled only on Windows hosts (`cargo build` there or with a
//! `x86_64-pc-windows-*` target). Scan-code mode (`KEYEVENTF_SCANCODE`) is
//! used because games (raw input / DirectInput consumers) generally ignore
//! virtual-key-only injection.
//!
//! Known limitations (docs/windows-setup.md):
//! - cannot inject into elevated processes (UIPI) unless the receiver runs
//!   elevated as well — by design of Windows, not a bug here;
//! - some anti-cheat systems flag synthetic input; bypassing them is out of
//!   scope by charter.

use input_core::{BackendError, Command, InputBackend, MouseButton};
use std::collections::HashSet;

// ---------------------------------------------------------------------------
// user32 FFI (winuser.h)
// ---------------------------------------------------------------------------

const INPUT_MOUSE: u32 = 0;
const INPUT_KEYBOARD: u32 = 1;

const KEYEVENTF_EXTENDEDKEY: u32 = 0x0001;
const KEYEVENTF_KEYUP: u32 = 0x0002;
const KEYEVENTF_SCANCODE: u32 = 0x0008;

const MOUSEEVENTF_MOVE: u32 = 0x0001;
const MOUSEEVENTF_LEFTDOWN: u32 = 0x0002;
const MOUSEEVENTF_LEFTUP: u32 = 0x0004;
const MOUSEEVENTF_RIGHTDOWN: u32 = 0x0008;
const MOUSEEVENTF_RIGHTUP: u32 = 0x0010;
const MOUSEEVENTF_MIDDLEDOWN: u32 = 0x0020;
const MOUSEEVENTF_MIDDLEUP: u32 = 0x0040;
const MOUSEEVENTF_XDOWN: u32 = 0x0080;
const MOUSEEVENTF_XUP: u32 = 0x0100;
const MOUSEEVENTF_WHEEL: u32 = 0x0800;
const MOUSEEVENTF_HWHEEL: u32 = 0x1000;

const XBUTTON1: u32 = 0x0001;
const XBUTTON2: u32 = 0x0002;
const WHEEL_DELTA: u32 = 120;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct KeybdInput {
    pub w_vk: u16,
    pub w_scan: u16,
    pub dw_flags: u32,
    pub time: u32,
    pub dw_extra_info: usize,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct MouseInput {
    pub dx: i32,
    pub dy: i32,
    pub mouse_data: u32,
    pub dw_flags: u32,
    pub time: u32,
    pub dw_extra_info: usize,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub union InputUnion {
    pub kbd: KeybdInput,
    pub mouse: MouseInput,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct Input {
    pub input_type: u32,
    pub union: InputUnion,
}

const _: () = assert!(std::mem::size_of::<MouseInput>() == 32);
const _: () = assert!(std::mem::size_of::<Input>() == 40);

extern "system" {
    #[link_name = "SendInput"]
    fn send_input(c_inputs: u32, p_inputs: *const Input, cb_size: i32) -> u32;
}

// ---------------------------------------------------------------------------
// evdev → Windows scan code table (set 1)
// ---------------------------------------------------------------------------

/// Map an evdev key code to `(scan_code, extended)`.
///
/// evdev main-block codes equal AT set-1 scan codes by design; the table
/// lists only keys whose Windows scancode differs (navigation cluster,
/// right-hand modifiers, meta keys). Verified against winuser.h scan set 1
/// and docs/windows-setup.md.
pub fn evdev_to_scan(code: u16) -> Option<(u16, bool)> {
    Some(match code {
        // main block: ESC, digits, punctuation, Q..P, brackets, ENTER,
        // modifiers, A..Z — all with matching set-1 codes
        1..=58 => (code, false),
        // F1..F10 (59..68) and F11..F12 (87..88) also match
        59..=68 | 87..=88 => (code, false),
        // numeric keypad: KP7..KPDOT (71..83) also match set-1 codes
        71..=83 => (code, false),
        // navigation cluster — extended
        102 => (0x47, true), // HOME
        103 => (0x48, true), // UP
        104 => (0x49, true), // PAGEUP
        105 => (0x4B, true), // LEFT
        106 => (0x4D, true), // RIGHT
        107 => (0x4F, true), // END
        108 => (0x50, true), // DOWN
        109 => (0x51, true), // PAGEDOWN
        110 => (0x52, true), // INSERT
        111 => (0x53, true), // DELETE
        // keypad extras — extended
        96 => (0x1C, true), // KPENTER (E0-1C)
        98 => (0x35, true), // KPSLASH (E0-35)
        // right-hand modifiers and meta keys — extended
        97 => (0x1D, true),  // RIGHTCTRL
        100 => (0x38, true), // RIGHTALT
        125 => (0x5B, true), // LEFTMETA
        126 => (0x5C, true), // RIGHTMETA
        _ => return None,
    })
}

// ---------------------------------------------------------------------------
// Backend
// ---------------------------------------------------------------------------

pub struct WindowsSendInputBackend {
    held_keys: HashSet<u16>,
    held_mouse: HashSet<MouseButton>,
}

impl WindowsSendInputBackend {
    pub fn create() -> Result<Self, BackendError> {
        Ok(Self {
            held_keys: HashSet::new(),
            held_mouse: HashSet::new(),
        })
    }

    fn send(&self, input: Input) -> Result<(), BackendError> {
        // SAFETY: a single well-formed INPUT of the documented size.
        let n = unsafe { send_input(1, &input, std::mem::size_of::<Input>() as i32) };
        if n != 1 {
            return Err(BackendError::Io(
                "SendInput returned 0 (input blocked by UIPI or security software)".into(),
            ));
        }
        Ok(())
    }

    fn key_input(scan: u16, extended: bool, up: bool) -> Input {
        let mut flags = KEYEVENTF_SCANCODE;
        if extended {
            flags |= KEYEVENTF_EXTENDEDKEY;
        }
        if up {
            flags |= KEYEVENTF_KEYUP;
        }
        Input {
            input_type: INPUT_KEYBOARD,
            union: InputUnion {
                kbd: KeybdInput {
                    w_vk: 0,
                    w_scan: scan,
                    dw_flags: flags,
                    time: 0,
                    dw_extra_info: 0,
                },
            },
        }
    }

    fn mouse_input(dx: i32, dy: i32, mouse_data: u32, flags: u32) -> Input {
        Input {
            input_type: INPUT_MOUSE,
            union: InputUnion {
                mouse: MouseInput {
                    dx,
                    dy,
                    mouse_data,
                    dw_flags: flags,
                    time: 0,
                    dw_extra_info: 0,
                },
            },
        }
    }

    fn button_down(b: MouseButton) -> Input {
        match b {
            MouseButton::Left => Self::mouse_input(0, 0, 0, MOUSEEVENTF_LEFTDOWN),
            MouseButton::Right => Self::mouse_input(0, 0, 0, MOUSEEVENTF_RIGHTDOWN),
            MouseButton::Middle => Self::mouse_input(0, 0, 0, MOUSEEVENTF_MIDDLEDOWN),
            MouseButton::Back => {
                Self::mouse_input(0, 0, XBUTTON1 << 16, MOUSEEVENTF_XDOWN)
            }
            MouseButton::Forward => {
                Self::mouse_input(0, 0, XBUTTON2 << 16, MOUSEEVENTF_XDOWN)
            }
        }
    }

    fn button_up(b: MouseButton) -> Input {
        match b {
            MouseButton::Left => Self::mouse_input(0, 0, 0, MOUSEEVENTF_LEFTUP),
            MouseButton::Right => Self::mouse_input(0, 0, 0, MOUSEEVENTF_RIGHTUP),
            MouseButton::Middle => Self::mouse_input(0, 0, 0, MOUSEEVENTF_MIDDLEUP),
            MouseButton::Back => Self::mouse_input(0, 0, XBUTTON1 << 16, MOUSEEVENTF_XUP),
            MouseButton::Forward => Self::mouse_input(0, 0, XBUTTON2 << 16, MOUSEEVENTF_XUP),
        }
    }
}

impl InputBackend for WindowsSendInputBackend {
    fn apply(&mut self, cmd: Command) -> Result<(), BackendError> {
        match cmd {
            Command::KeyDown(code) => {
                if !self.held_keys.insert(code) {
                    return Ok(()); // already held
                }
                match evdev_to_scan(code) {
                    Some((scan, ext)) => self.send(Self::key_input(scan, ext, false)),
                    None => {
                        self.held_keys.remove(&code);
                        Err(BackendError::Unsupported("key has no Windows scan code"))
                    }
                }
            }
            Command::KeyUp(code) => {
                if !self.held_keys.remove(&code) {
                    return Ok(()); // not held
                }
                match evdev_to_scan(code) {
                    Some((scan, ext)) => self.send(Self::key_input(scan, ext, true)),
                    None => Err(BackendError::Unsupported("key has no Windows scan code")),
                }
            }
            Command::MouseDown(b) => {
                if !self.held_mouse.insert(b) {
                    return Ok(());
                }
                self.send(Self::button_down(b))
            }
            Command::MouseUp(b) => {
                if !self.held_mouse.remove(&b) {
                    return Ok(());
                }
                self.send(Self::button_up(b))
            }
            Command::MouseRel { dx, dy } => {
                self.send(Self::mouse_input(dx, dy, 0, MOUSEEVENTF_MOVE))
            }
            Command::Scroll { horizontal, ticks } => {
                let data = (ticks as i64 * WHEEL_DELTA as i64) as u32;
                self.send(Self::mouse_input(
                    0,
                    0,
                    data,
                    if horizontal { MOUSEEVENTF_HWHEEL } else { MOUSEEVENTF_WHEEL },
                ))
            }
        }
    }

    fn release_all(&mut self) {
        for code in std::mem::take(&mut self.held_keys) {
            if let Some((scan, ext)) = evdev_to_scan(code) {
                let _ = self.send(Self::key_input(scan, ext, true));
            }
        }
        for b in std::mem::take(&mut self.held_mouse) {
            let _ = self.send(Self::button_up(b));
        }
    }

    fn name(&self) -> &'static str {
        "windows-sendinput"
    }

    fn held_summary(&self) -> Vec<String> {
        vec![
            format!("{} keys held", self.held_keys.len()),
            format!("{} mouse buttons held", self.held_mouse.len()),
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn main_block_codes_map_directly() {
        // evdev == set-1 for the main block
        assert_eq!(evdev_to_scan(30), Some((30, false))); // A
        assert_eq!(evdev_to_scan(17), Some((17, false))); // W
        assert_eq!(evdev_to_scan(57), Some((57, false))); // SPACE
        assert_eq!(evdev_to_scan(59), Some((59, false))); // F1
        assert_eq!(evdev_to_scan(88), Some((88, false))); // F12
        assert_eq!(evdev_to_scan(42), Some((42, false))); // LSHIFT
    }

    #[test]
    fn extended_keys_are_marked() {
        assert_eq!(evdev_to_scan(103), Some((0x48, true))); // UP
        assert_eq!(evdev_to_scan(97), Some((0x1D, true))); // RCTRL
        assert_eq!(evdev_to_scan(125), Some((0x5B, true))); // LMETA
        assert!(!evdev_to_scan(1).unwrap().1); // ESC not extended
    }

    #[test]
    fn unmapped_keys_reported() {
        assert!(evdev_to_scan(767).is_none());
    }

    #[test]
    fn struct_layout_matches_winuser() {
        assert_eq!(std::mem::size_of::<Input>(), 40);
        assert_eq!(std::mem::size_of::<MouseInput>(), 32);
        assert_eq!(std::mem::size_of::<KeybdInput>(), 24);
    }
}
