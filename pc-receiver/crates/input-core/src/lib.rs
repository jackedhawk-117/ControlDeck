//! Platform-free input state machine and the `InputBackend` abstraction
//! (Engineering Rule 5: platform-specific code stays behind this trait).

pub mod state;

pub use state::InputState;
use pc_protocol::{keycodes, op};

/// Platform-independent command for a concrete backend.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Command {
    KeyDown(u16),
    KeyUp(u16),
    MouseDown(MouseButton),
    MouseUp(MouseButton),
    /// Integer device-pixel movement; fractional parts are resolved by
    /// `InputState` before this command is emitted.
    MouseRel { dx: i32, dy: i32 },
    /// Wheel ticks.
    Scroll { horizontal: bool, ticks: i32 },
}

/// Protocol mouse button ids 1..5 (docs/PROTOCOL.md §3).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MouseButton {
    Left,
    Right,
    Middle,
    Back,
    Forward,
}

impl MouseButton {
    pub fn from_protocol_id(id: u16) -> Option<Self> {
        match id {
            op::MOUSE_LEFT => Some(Self::Left),
            op::MOUSE_RIGHT => Some(Self::Right),
            op::MOUSE_MIDDLE => Some(Self::Middle),
            op::MOUSE_BACK => Some(Self::Back),
            op::MOUSE_FORWARD => Some(Self::Forward),
            _ => None,
        }
    }

    pub fn protocol_id(self) -> u16 {
        match self {
            Self::Left => op::MOUSE_LEFT,
            Self::Right => op::MOUSE_RIGHT,
            Self::Middle => op::MOUSE_MIDDLE,
            Self::Back => op::MOUSE_BACK,
            Self::Forward => op::MOUSE_FORWARD,
        }
    }

    /// Bit position in INPUT_SNAPSHOT `mouse_bits` (bit0..4 = buttons 1..5).
    pub fn bit(self) -> u8 {
        1 << (self.protocol_id() - 1)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum BackendError {
    #[error("backend write failed: {0}")]
    Io(String),
    #[error("backend unavailable: {0}")]
    Unavailable(String),
    #[error("unsupported command: {0}")]
    Unsupported(&'static str),
}

/// Common interface every platform backend implements.
///
/// Contract:
/// - `apply` must be cheap and non-blocking beyond the OS call itself.
/// - After `release_all` returns, **no** input previously applied by this
///   backend may remain held by the OS (Engineering Rule 11).
/// - Backends must tolerate commands for keys they did not register
///   (ignore + report), never panic.
pub trait InputBackend: Send {
    fn apply(&mut self, cmd: Command) -> Result<(), BackendError>;
    /// Release every key/button this backend is holding.
    fn release_all(&mut self);
    /// Human-readable name for logs/diagnostics.
    fn name(&self) -> &'static str;
    /// Optional: names of currently held inputs (diagnostics).
    fn held_summary(&self) -> Vec<String> {
        Vec::new()
    }
}

/// Map a protocol mouse button id to its evdev button code (Linux backend).
pub fn mouse_button_to_evdev(id: u16) -> Option<u16> {
    match MouseButton::from_protocol_id(id) {
        Some(MouseButton::Left) => Some(keycodes::BTN_LEFT),
        Some(MouseButton::Right) => Some(keycodes::BTN_RIGHT),
        Some(MouseButton::Middle) => Some(keycodes::BTN_MIDDLE),
        Some(MouseButton::Back) => Some(keycodes::BTN_SIDE),
        Some(MouseButton::Forward) => Some(keycodes::BTN_EXTRA),
        None => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mouse_button_ids_roundtrip() {
        for id in 1..=5u16 {
            let b = MouseButton::from_protocol_id(id).unwrap();
            assert_eq!(b.protocol_id(), id);
        }
        assert!(MouseButton::from_protocol_id(6).is_none());
        assert!(MouseButton::from_protocol_id(0).is_none());
    }

    #[test]
    fn mouse_bits_are_ascending() {
        assert_eq!(MouseButton::Left.bit(), 0b0000_0001);
        assert_eq!(MouseButton::Right.bit(), 0b0000_0010);
        assert_eq!(MouseButton::Middle.bit(), 0b0000_0100);
        assert_eq!(MouseButton::Back.bit(), 0b0000_1000);
        assert_eq!(MouseButton::Forward.bit(), 0b0001_0000);
    }

    #[test]
    fn mouse_button_evdev_mapping() {
        assert_eq!(mouse_button_to_evdev(1), Some(keycodes::BTN_LEFT));
        assert_eq!(mouse_button_to_evdev(5), Some(keycodes::BTN_EXTRA));
        assert_eq!(mouse_button_to_evdev(9), None);
    }
}
