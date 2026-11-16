//! Input-plane payloads: INPUT_DELTA, INPUT_SNAPSHOT, STATS (§5.5–5.7).

use crate::{op, valid_event_op, EncodeError, ParseError};

/// One input event, fixed-size on the wire (1 + 2 + 4 + 4 = 11 bytes).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DeltaEvent {
    /// One of [`crate::op`] values.
    pub op: u8,
    /// evdev key code, or protocol mouse button id 1..=5, or scroll axis 0/1.
    pub code: u16,
    /// For MOUSE_REL: dx in Q16.16 fixed point (65536 = 1 device pixel).
    /// For SCROLL: wheel ticks along the axis named by `code`.
    pub a: i32,
    /// For MOUSE_REL: dy in Q16.16 fixed point. Unused for other ops.
    pub b: i32,
}

pub const EVENT_WIRE_SIZE: usize = 11;
pub const MAX_EVENTS_PER_DELTA: usize = 32;

impl DeltaEvent {
    pub fn key_down(code: u16) -> Self {
        DeltaEvent { op: op::KEY_DOWN, code, a: 0, b: 0 }
    }
    pub fn key_up(code: u16) -> Self {
        DeltaEvent { op: op::KEY_UP, code, a: 0, b: 0 }
    }
    pub fn mouse_down(button: u16) -> Self {
        DeltaEvent { op: op::MOUSE_DOWN, code: button, a: 0, b: 0 }
    }
    pub fn mouse_up(button: u16) -> Self {
        DeltaEvent { op: op::MOUSE_UP, code: button, a: 0, b: 0 }
    }
    /// dx/dy in Q16.16 fixed point.
    pub fn mouse_rel_q16(dx_q16: i32, dy_q16: i32) -> Self {
        DeltaEvent { op: op::MOUSE_REL, code: 0, a: dx_q16, b: dy_q16 }
    }
    pub fn scroll(vertical_ticks: i32, horizontal_ticks: i32) -> Self {
        let (code, a, b) = if vertical_ticks != 0 {
            (0u16, vertical_ticks, 0i32)
        } else {
            (1u16, horizontal_ticks, 0i32)
        };
        DeltaEvent { op: op::SCROLL, code, a, b }
    }

    pub fn encode_into(&self, out: &mut Vec<u8>) {
        out.push(self.op);
        out.extend_from_slice(&self.code.to_le_bytes());
        out.extend_from_slice(&self.a.to_le_bytes());
        out.extend_from_slice(&self.b.to_le_bytes());
    }
}

/// INPUT_DELTA packet payload (encrypted on the wire).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InputDelta {
    pub session_id: u32,
    pub events: Vec<DeltaEvent>,
    /// Phone-local milliseconds (mod 2^32); echoed by STATS for RTT.
    pub probe_ts: u32,
}

impl InputDelta {
    pub fn plaintext_len(&self) -> usize {
        4 + 2 + self.events.len() * EVENT_WIRE_SIZE + 4
    }

    /// Encode into `out` (cleared first; buffer may be reused for zero-alloc
    /// hot paths).
    pub fn encode_into(&self, out: &mut Vec<u8>) -> Result<(), EncodeError> {
        if self.events.len() > MAX_EVENTS_PER_DELTA {
            return Err(EncodeError::OutOfRange("too many events in one delta"));
        }
        out.clear();
        out.extend_from_slice(&self.session_id.to_le_bytes());
        out.extend_from_slice(&(self.events.len() as u16).to_le_bytes());
        for e in &self.events {
            e.encode_into(out);
        }
        out.extend_from_slice(&self.probe_ts.to_le_bytes());
        Ok(())
    }

    pub fn encode(&self) -> Result<Vec<u8>, EncodeError> {
        let mut v = Vec::with_capacity(self.plaintext_len());
        self.encode_into(&mut v)?;
        Ok(v)
    }

    pub fn decode(payload: &[u8]) -> Result<InputDelta, ParseError> {
        if payload.len() < 6 {
            return Err(ParseError::Truncated { needed: 6, got: payload.len() });
        }
        let session_id = u32::from_le_bytes([payload[0], payload[1], payload[2], payload[3]]);
        let count = u16::from_le_bytes([payload[4], payload[5]]) as usize;
        if count > MAX_EVENTS_PER_DELTA {
            return Err(ParseError::TooLong { len: count, limit: MAX_EVENTS_PER_DELTA });
        }
        let need = 6 + count * EVENT_WIRE_SIZE + 4;
        if payload.len() < need {
            return Err(ParseError::Truncated { needed: need, got: payload.len() });
        }
        let mut events = Vec::with_capacity(count);
        let mut off = 6;
        for _ in 0..count {
            let code = u16::from_le_bytes([payload[off + 1], payload[off + 2]]);
            let a = i32::from_le_bytes([
                payload[off + 3],
                payload[off + 4],
                payload[off + 5],
                payload[off + 6],
            ]);
            let b = i32::from_le_bytes([
                payload[off + 7],
                payload[off + 8],
                payload[off + 9],
                payload[off + 10],
            ]);
            let op_byte = payload[off];
            if !valid_event_op(op_byte) {
                return Err(ParseError::BadField("unknown event op"));
            }
            events.push(DeltaEvent { op: op_byte, code, a, b });
            off += EVENT_WIRE_SIZE;
        }
        let probe_ts =
            u32::from_le_bytes([payload[off], payload[off + 1], payload[off + 2], payload[off + 3]]);
        Ok(InputDelta { session_id, events, probe_ts })
    }
}

/// INPUT_SNAPSHOT payload (§5.6): full held-state for reconciliation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InputSnapshot {
    pub session_id: u32,
    /// seq of the last INPUT_DELTA the phone considers applied.
    pub base_seq: u32,
    /// bit0..4 = protocol mouse buttons 1..5 held.
    pub mouse_bits: u8,
    /// Sorted ascending evdev codes currently held.
    pub keys: Vec<u16>,
}

impl InputSnapshot {
    pub fn encode(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(11 + self.keys.len() * 2);
        v.extend_from_slice(&self.session_id.to_le_bytes());
        v.extend_from_slice(&self.base_seq.to_le_bytes());
        v.push(self.mouse_bits);
        v.extend_from_slice(&(self.keys.len() as u16).to_le_bytes());
        for k in &self.keys {
            v.extend_from_slice(&k.to_le_bytes());
        }
        v
    }

    pub fn decode(payload: &[u8]) -> Result<InputSnapshot, ParseError> {
        if payload.len() < 11 {
            return Err(ParseError::Truncated { needed: 11, got: payload.len() });
        }
        let session_id = u32::from_le_bytes([payload[0], payload[1], payload[2], payload[3]]);
        let base_seq = u32::from_le_bytes([payload[4], payload[5], payload[6], payload[7]]);
        let mouse_bits = payload[8];
        let count = u16::from_le_bytes([payload[9], payload[10]]) as usize;
        if payload.len() < 11 + count * 2 {
            return Err(ParseError::Truncated { needed: 11 + count * 2, got: payload.len() });
        }
        let mut keys = Vec::with_capacity(count);
        for i in 0..count {
            let off = 11 + i * 2;
            keys.push(u16::from_le_bytes([payload[off], payload[off + 1]]));
        }
        Ok(InputSnapshot { session_id, base_seq, mouse_bits, keys })
    }
}

/// STATS payload (§5.7), PC → phone.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Stats {
    pub last_seq_processed: u32,
    pub events_per_sec: u32,
    pub packets_dropped_total: u32,
    pub rtt_probe_echo: u32,
}

impl Stats {
    pub const WIRE_SIZE: usize = 16;

    pub fn encode(&self) -> [u8; 16] {
        let mut out = [0u8; 16];
        out[0..4].copy_from_slice(&self.last_seq_processed.to_le_bytes());
        out[4..8].copy_from_slice(&self.events_per_sec.to_le_bytes());
        out[8..12].copy_from_slice(&self.packets_dropped_total.to_le_bytes());
        out[12..16].copy_from_slice(&self.rtt_probe_echo.to_le_bytes());
        out
    }

    pub fn decode(payload: &[u8]) -> Result<Stats, ParseError> {
        if payload.len() < 16 {
            return Err(ParseError::Truncated { needed: 16, got: payload.len() });
        }
        Ok(Stats {
            last_seq_processed: u32::from_le_bytes(payload[0..4].try_into().unwrap()),
            events_per_sec: u32::from_le_bytes(payload[4..8].try_into().unwrap()),
            packets_dropped_total: u32::from_le_bytes(payload[8..12].try_into().unwrap()),
            rtt_probe_echo: u32::from_le_bytes(payload[12..16].try_into().unwrap()),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keycodes;

    fn sample_delta() -> InputDelta {
        InputDelta {
            session_id: 0x0000_3B9A,
            events: vec![
                DeltaEvent::key_down(keycodes::KEY_W),
                DeltaEvent::mouse_down(op::MOUSE_LEFT),
                DeltaEvent::mouse_rel_q16(98112, 0), // 1.497 px
                DeltaEvent::scroll(3, 0),
            ],
            probe_ts: 0x1C_65_E5,
        }
    }

    #[test]
    fn delta_roundtrip() {
        let d = sample_delta();
        let wire = d.encode().unwrap();
        assert_eq!(wire.len(), d.plaintext_len());
        let d2 = InputDelta::decode(&wire).unwrap();
        assert_eq!(d, d2);
    }

    #[test]
    fn delta_rejects_bad_op() {
        let mut d = sample_delta();
        d.events[0].op = 0x7F;
        let wire = d.encode().unwrap();
        assert!(matches!(
            InputDelta::decode(&wire),
            Err(ParseError::BadField("unknown event op"))
        ));
    }

    #[test]
    fn delta_rejects_overflow_count() {
        // claim 33 events but only supply a few bytes — must fail on count cap
        let mut p = vec![0u8; 6];
        p[0..4].copy_from_slice(&1u32.to_le_bytes());
        p[4..6].copy_from_slice(&33u16.to_le_bytes());
        assert!(matches!(
            InputDelta::decode(&p),
            Err(ParseError::TooLong { .. })
        ));
    }

    #[test]
    fn delta_rejects_truncation() {
        let d = sample_delta();
        let mut wire = d.encode().unwrap();
        wire.truncate(wire.len() - 3);
        assert!(matches!(
            InputDelta::decode(&wire),
            Err(ParseError::Truncated { .. })
        ));
    }

    #[test]
    fn snapshot_roundtrip() {
        let s = InputSnapshot {
            session_id: 7,
            base_seq: 999,
            mouse_bits: 0b0000_0011,
            keys: vec![17, 30, 31, 57],
        };
        let d = InputSnapshot::decode(&s.encode()).unwrap();
        assert_eq!(s, d);
    }

    #[test]
    fn stats_roundtrip() {
        let s = Stats {
            last_seq_processed: 41,
            events_per_sec: 231,
            packets_dropped_total: 2,
            rtt_probe_echo: 0x1C_65_E5,
        };
        assert_eq!(Stats::decode(&s.encode()).unwrap(), s);
    }
}
