//! Input state machine: consumes protocol delta events / snapshots and
//! produces deduplicated, idempotent backend commands.
//!
//! Guarantees:
//! - one KEY_DOWN per code until a KEY_UP arrives (dedupe),
//! - releasing a key that is not held emits nothing,
//! - Q16.16 sub-pixel mouse movement accumulates across events and only
//!   integer pixels are forwarded to the backend,
//! - `release_all` emits an up-event for *everything currently held*, in a
//!   deterministic order,
//! - snapshots reconcile the receiver's view against the phone's view.

use crate::{Command, MouseButton};
use pc_protocol::{keycodes, op, DeltaEvent, InputSnapshot};
use std::collections::BTreeSet;

/// Q16.16 fixed point: one device pixel.
const Q16: i64 = 65536;

#[derive(Debug)]
pub struct InputState {
    held_keys: BTreeSet<u16>,
    held_mouse: u8,
    rem_x_q16: i64,
    rem_y_q16: i64,
    /// Scratch output; the borrow of `pending` is handed out per call.
    pending: Vec<Command>,
    /// Dropped/ignored invalid events counter (diagnostics).
    ignored_invalid: u64,
}

impl Default for InputState {
    fn default() -> Self {
        Self::new()
    }
}

impl InputState {
    pub fn new() -> Self {
        Self {
            held_keys: BTreeSet::new(),
            held_mouse: 0,
            rem_x_q16: 0,
            rem_y_q16: 0,
            pending: Vec::with_capacity(8),
            ignored_invalid: 0,
        }
    }

    /// Keys currently held, ascending (diagnostics + snapshot baseline).
    pub fn held_keys(&self) -> &BTreeSet<u16> {
        &self.held_keys
    }

    /// Mouse buttons currently held as a bits field (bit0..4 = 1..5).
    pub fn held_mouse_bits(&self) -> u8 {
        self.held_mouse
    }

    pub fn is_empty(&self) -> bool {
        self.held_keys.is_empty() && self.held_mouse == 0
    }

    /// Count of invalid events ignored since construction.
    pub fn ignored_invalid_events(&self) -> u64 {
        self.ignored_invalid
    }

    /// Apply one INPUT_DELTA event. Returns the commands to hand to the
    /// backend (possibly empty). The returned slice is valid until the next
    /// call on `self`.
    pub fn apply_event(&mut self, e: &DeltaEvent) -> &[Command] {
        self.pending.clear();
        match e.op {
            op::KEY_DOWN => {
                if e.code <= keycodes::KEY_MAX && self.held_keys.insert(e.code) {
                    self.pending.push(Command::KeyDown(e.code));
                } else {
                    self.note_invalid(e);
                }
            }
            op::KEY_UP => {
                if e.code <= keycodes::KEY_MAX && self.held_keys.remove(&e.code) {
                    self.pending.push(Command::KeyUp(e.code));
                } else {
                    self.note_invalid(e);
                }
            }
            op::MOUSE_DOWN => {
                if let Some(b) = MouseButton::from_protocol_id(e.code) {
                    let bit = b.bit();
                    if self.held_mouse & bit == 0 {
                        self.held_mouse |= bit;
                        self.pending.push(Command::MouseDown(b));
                    } else {
                        self.note_invalid(e);
                    }
                } else {
                    self.note_invalid(e);
                }
            }
            op::MOUSE_UP => {
                if let Some(b) = MouseButton::from_protocol_id(e.code) {
                    let bit = b.bit();
                    if self.held_mouse & bit != 0 {
                        self.held_mouse &= !bit;
                        self.pending.push(Command::MouseUp(b));
                    } else {
                        self.note_invalid(e);
                    }
                } else {
                    self.note_invalid(e);
                }
            }
            op::MOUSE_REL => {
                // accumulate Q16.16, emit integer pixels, keep remainder
                let mut total_x = self.rem_x_q16 + e.a as i64;
                let mut total_y = self.rem_y_q16 + e.b as i64;
                let dx = total_x / Q16;
                let dy = total_y / Q16;
                total_x -= dx * Q16;
                total_y -= dy * Q16;
                self.rem_x_q16 = total_x;
                self.rem_y_q16 = total_y;
                if dx != 0 || dy != 0 {
                    self.pending.push(Command::MouseRel { dx: dx as i32, dy: dy as i32 });
                }
            }
            op::SCROLL => match e.code {
                0 => {
                    if e.a != 0 {
                        self.pending.push(Command::Scroll { horizontal: false, ticks: e.a });
                    }
                }
                1 => {
                    if e.a != 0 {
                        self.pending.push(Command::Scroll { horizontal: true, ticks: e.a });
                    }
                }
                _ => self.note_invalid(e),
            },
            other => {
                debug_assert!(false, "validated by protocol decode");
                self.ignored_invalid += 1;
                let _ = other;
            }
        }
        &self.pending
    }

    /// Apply an INPUT_SNAPSHOT: diff the phone's view of held state against
    /// ours and emit commands that make us agree. Movement/scroller state is
    /// not part of snapshots.
    pub fn apply_snapshot(&mut self, snap: &InputSnapshot) -> &[Command] {
        self.pending.clear();

        let phone_keys: &dyn Fn(u16) -> bool = &|code| snap.keys.contains(&code);
        // release keys the phone no longer holds
        let ours: Vec<u16> = self.held_keys.iter().copied().collect();
        for code in ours {
            if !phone_keys(code) {
                self.held_keys.remove(&code);
                self.pending.push(Command::KeyUp(code));
            }
        }
        // press keys the phone holds but we do not
        for &code in &snap.keys {
            if code <= keycodes::KEY_MAX && self.held_keys.insert(code) {
                self.pending.push(Command::KeyDown(code));
            }
        }

        // mouse buttons
        for b in [
            MouseButton::Left,
            MouseButton::Right,
            MouseButton::Middle,
            MouseButton::Back,
            MouseButton::Forward,
        ] {
            let bit = b.bit();
            let phone_held = snap.mouse_bits & bit != 0;
            let ours_held = self.held_mouse & bit != 0;
            if phone_held && !ours_held {
                self.held_mouse |= bit;
                self.pending.push(Command::MouseDown(b));
            } else if !phone_held && ours_held {
                self.held_mouse &= !bit;
                self.pending.push(Command::MouseUp(b));
            }
        }

        &self.pending
    }

    /// Release every held input (disconnect, timeout, RESET, shutdown).
    /// Returns the commands to emit; afterwards `is_empty()` is true.
    pub fn release_all(&mut self) -> &[Command] {
        self.pending.clear();
        for code in std::mem::take(&mut self.held_keys) {
            self.pending.push(Command::KeyUp(code));
        }
        let bits = std::mem::replace(&mut self.held_mouse, 0);
        for b in [
            MouseButton::Forward,
            MouseButton::Back,
            MouseButton::Middle,
            MouseButton::Right,
            MouseButton::Left,
        ] {
            if bits & b.bit() != 0 {
                self.pending.push(Command::MouseUp(b));
            }
        }
        // movement remainder becomes meaningless without a session
        self.rem_x_q16 = 0;
        self.rem_y_q16 = 0;
        &self.pending
    }

    fn note_invalid(&mut self, _e: &DeltaEvent) {
        self.ignored_invalid += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pc_protocol::keycodes as kc;

    #[test]
    fn key_press_release_dedup() {
        let mut st = InputState::new();
        let cmds = st.apply_event(&DeltaEvent::key_down(kc::KEY_W));
        assert_eq!(cmds, [Command::KeyDown(kc::KEY_W)]);
        // duplicate press ignored
        assert!(st.apply_event(&DeltaEvent::key_down(kc::KEY_W)).is_empty());
        // release
        let cmds = st.apply_event(&DeltaEvent::key_up(kc::KEY_W));
        assert_eq!(cmds, [Command::KeyUp(kc::KEY_W)]);
        // duplicate release ignored
        assert!(st.apply_event(&DeltaEvent::key_up(kc::KEY_W)).is_empty());
        assert!(st.is_empty());
    }

    #[test]
    fn multiple_keys_held_simultaneously() {
        let mut st = InputState::new();
        let _ = st.apply_event(&DeltaEvent::key_down(kc::KEY_W));
        let _ = st.apply_event(&DeltaEvent::key_down(kc::KEY_A));
        let _ = st.apply_event(&DeltaEvent::key_down(kc::KEY_LEFTSHIFT));
        assert_eq!(st.held_keys().len(), 3);
        let release = st.release_all().to_vec();
        assert_eq!(
            release,
            vec![
                Command::KeyUp(kc::KEY_W),
                Command::KeyUp(kc::KEY_A),
                Command::KeyUp(kc::KEY_LEFTSHIFT),
            ]
        );
        assert!(st.is_empty());
        // second release emits nothing
        assert!(st.release_all().is_empty());
    }

    #[test]
    fn q16_accumulation_no_truncation_loss() {
        let mut st = InputState::new();
        // two half-pixel moves must eventually produce one full pixel
        let cmds = st.apply_event(&DeltaEvent::mouse_rel_q16(Q16 as i32 / 2, 0));
        assert!(cmds.is_empty(), "0.5 px emits nothing yet");
        let cmds = st.apply_event(&DeltaEvent::mouse_rel_q16(Q16 as i32 / 2, 0));
        assert_eq!(cmds, [Command::MouseRel { dx: 1, dy: 0 }]);

        // many sub-pixel steps accumulate correctly
        let mut total = 0;
        for _ in 0..100 {
            total += Q16 as i32 / 3; // 0.333 px per event
        }
        let mut st = InputState::new();
        let mut emitted = 0;
        for _ in 0..100 {
            emitted += st
                .apply_event(&DeltaEvent::mouse_rel_q16(Q16 as i32 / 3, 0))
                .iter()
                .map(|c| match c {
                    Command::MouseRel { dx, .. } => *dx,
                    _ => 0,
                })
                .sum::<i32>();
        }
        assert_eq!(emitted, total / Q16 as i32);
        assert!(emitted >= 33, "slow gyro movement must not be lost entirely");
    }

    #[test]
    fn negative_remainder_rounds_correctly() {
        let mut st = InputState::new();
        let _ = st.apply_event(&DeltaEvent::mouse_rel_q16(-(Q16 / 2) as i32, 0));
        let cmds = st.apply_event(&DeltaEvent::mouse_rel_q16(-(Q16 / 2) as i32, 0));
        assert_eq!(cmds, [Command::MouseRel { dx: -1, dy: 0 }]);
    }

    #[test]
    fn mouse_buttons_and_bits() {
        let mut st = InputState::new();
        let _ = st.apply_event(&DeltaEvent::mouse_down(1));
        let _ = st.apply_event(&DeltaEvent::mouse_down(2));
        assert_eq!(st.held_mouse_bits(), 0b0000_0011);
        // duplicate ignored
        assert!(st.apply_event(&DeltaEvent::mouse_down(1)).is_empty());
        assert_eq!(st.apply_event(&DeltaEvent::mouse_up(1)), [Command::MouseUp(MouseButton::Left)]);
        assert_eq!(st.held_mouse_bits(), 0b0000_0010);
        let _ = st.release_all();
    }

    #[test]
    fn invalid_button_ids_ignored_and_counted() {
        let mut st = InputState::new();
        assert!(st.apply_event(&DeltaEvent::mouse_down(9)).is_empty());
        assert_eq!(st.ignored_invalid_events(), 1);
        assert!(st.apply_event(&DeltaEvent::key_down(5000)).is_empty());
        assert_eq!(st.ignored_invalid_events(), 2);
    }

    #[test]
    fn scroll_axes() {
        let mut st = InputState::new();
        assert_eq!(
            st.apply_event(&DeltaEvent::scroll(3, 0)),
            [Command::Scroll { horizontal: false, ticks: 3 }]
        );
        assert_eq!(
            st.apply_event(&DeltaEvent::scroll(0, -2)),
            [Command::Scroll { horizontal: true, ticks: -2 }]
        );
    }

    #[test]
    fn snapshot_reconciles_both_directions() {
        let mut st = InputState::new();
        // receiver believes: W + A + left mouse held
        let _ = st.apply_event(&DeltaEvent::key_down(kc::KEY_W));
        let _ = st.apply_event(&DeltaEvent::key_down(kc::KEY_A));
        let _ = st.apply_event(&DeltaEvent::mouse_down(1));

        // phone says: only D + right mouse held (we lost a delta packet burst)
        let snap = InputSnapshot {
            session_id: 1,
            base_seq: 10,
            mouse_bits: MouseButton::Right.bit(),
            keys: vec![kc::KEY_D],
        };
        let cmds = st.apply_snapshot(&snap).to_vec();
        assert!(cmds.contains(&Command::KeyUp(kc::KEY_W)));
        assert!(cmds.contains(&Command::KeyUp(kc::KEY_A)));
        assert!(cmds.contains(&Command::MouseUp(MouseButton::Left)));
        assert!(cmds.contains(&Command::KeyDown(kc::KEY_D)));
        assert!(cmds.contains(&Command::MouseDown(MouseButton::Right)));
        assert_eq!(st.held_keys(), &BTreeSet::from([kc::KEY_D]));
        assert_eq!(st.held_mouse_bits(), MouseButton::Right.bit());
    }

    #[test]
    fn snapshot_with_no_diff_emits_nothing() {
        let mut st = InputState::new();
        let _ = st.apply_event(&DeltaEvent::key_down(kc::KEY_W));
        let snap = InputSnapshot {
            session_id: 1,
            base_seq: 12,
            mouse_bits: 0,
            keys: vec![kc::KEY_W],
        };
        assert!(st.apply_snapshot(&snap).is_empty());
    }

    #[test]
    fn release_all_is_deterministic_and_complete() {
        let mut st = InputState::new();
        for code in [kc::KEY_W, kc::KEY_SPACE, kc::KEY_F5] {
            let _ = st.apply_event(&DeltaEvent::key_down(code));
        }
        for id in 1..=5u16 {
            let _ = st.apply_event(&DeltaEvent::mouse_down(id));
        }
        let release = st.release_all().to_vec();
        assert_eq!(release.len(), 8, "3 keys + 5 buttons");
        // deterministic order: keys ascending, then buttons released 5→1
        let ups: Vec<u16> = release
            .iter()
            .map(|c| match c {
                Command::KeyUp(code) => *code,
                Command::MouseUp(b) => b.protocol_id(),
                _ => panic!("expected only up events"),
            })
            .collect();
        assert!(ups[0] < ups[1] && ups[1] < ups[2], "keys ascending");
        assert_eq!(&ups[3..], &[5, 4, 3, 2, 1], "buttons released 5→1");
        assert!(st.is_empty());
    }
}
