//! PhoneController protocol v1 — the normative implementation of
//! `docs/PROTOCOL.md`. Pure encode/decode/crypto: **no I/O happens here**
//! so the crate is portable and unit-testable on every platform.

pub mod crypto;
pub mod framing;
pub mod handshake;
pub mod input;
pub mod keycodes;

pub use crypto::{Watermark, SessionCrypto};
pub use framing::{PacketHeader, HEADER_LEN};
pub use input::{DeltaEvent, InputDelta, InputSnapshot, Stats, MAX_EVENTS_PER_DELTA};

/// Wire protocol version carried in every packet header (byte 2).
pub const PROTOCOL_VERSION: u8 = 1;

/// `0x50 0x43` = "PC", bytes 0 and 1 of every packet.
pub const MAGIC: [u8; 2] = [0x50, 0x43];

/// Maximum UDP datagram (header + plaintext payload, before GCM tag).
/// Encrypted payloads add 16 bytes of tag on top.
pub const MAX_UDP_PACKET: usize = 1400;

/// Default TCP control port.
pub const DEFAULT_TCP_PORT: u16 = 41000;
/// Default UDP input/discovery port.
pub const DEFAULT_UDP_PORT: u16 = 41000;

/// Packet kinds (docs/PROTOCOL.md §4).
pub mod kind {
    pub const DISCOVER: u8 = 0x01;
    pub const ANNOUNCE: u8 = 0x02;
    pub const HELLO: u8 = 0x03;
    pub const HELLO_REPLY: u8 = 0x04;
    pub const AUTH_PROOF: u8 = 0x05;
    pub const SECURE_FRAME: u8 = 0x06;
    pub const PING: u8 = 0x07;
    pub const PONG: u8 = 0x08;
    pub const BYE: u8 = 0x09;
    pub const RESET: u8 = 0x0A;
    pub const PROFILE_INFO: u8 = 0x0B;
    pub const ERROR: u8 = 0x0C;
    pub const INPUT_DELTA: u8 = 0x10;
    pub const INPUT_SNAPSHOT: u8 = 0x11;
    pub const STATS: u8 = 0x12;

    /// True for kinds that are always sent encrypted (after handshake).
    pub const fn is_secure_kind(kind: u8) -> bool {
        matches!(
            kind,
            PING | PONG | BYE | RESET | PROFILE_INFO | INPUT_DELTA | INPUT_SNAPSHOT | STATS
        )
    }

    /// Kinds allowed while the TCP session is still unauthenticated.
    pub const fn is_handshake_kind(kind: u8) -> bool {
        matches!(kind, HELLO | HELLO_REPLY | AUTH_PROOF | ERROR)
    }
}

/// ERROR packet codes (docs/PROTOCOL.md §4).
pub mod error_code {
    pub const VERSION_MISMATCH: u16 = 0x0001;
    pub const AUTH_FAILED: u16 = 0x0002;
    pub const MALFORMED: u16 = 0x0003;
    pub const SERVER_BUSY: u16 = 0x0004;
    pub const INTERNAL: u16 = 0x0005;
    pub const NOT_SECURE: u16 = 0x0006;
}

/// BYE reasons.
pub mod bye_reason {
    pub const NORMAL: u8 = 0;
    pub const TIMEOUT: u8 = 1;
    pub const REPLACED: u8 = 2;
    pub const ERROR: u8 = 3;
}

/// Input event ops (docs/PROTOCOL.md §5.5).
pub mod op {
    pub const KEY_DOWN: u8 = 0;
    pub const KEY_UP: u8 = 1;
    pub const MOUSE_DOWN: u8 = 2;
    pub const MOUSE_UP: u8 = 3;
    pub const MOUSE_REL: u8 = 4;
    pub const SCROLL: u8 = 5;

    /// Protocol mouse button ids.
    pub const MOUSE_LEFT: u16 = 1;
    pub const MOUSE_RIGHT: u16 = 2;
    pub const MOUSE_MIDDLE: u16 = 3;
    pub const MOUSE_BACK: u16 = 4;
    pub const MOUSE_FORWARD: u16 = 5;
}

/// Parse/validation failures shared by every decoder.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ParseError {
    #[error("truncated packet (need {needed} bytes, got {got})")]
    Truncated { needed: usize, got: usize },
    #[error("bad magic")]
    BadMagic,
    #[error("unsupported protocol version {0}")]
    BadVersion(u8),
    #[error("unknown packet kind 0x{0:02X}")]
    BadKind(u8),
    #[error("payload length {len} exceeds limit {limit}")]
    TooLong { len: usize, limit: usize },
    #[error("invalid field: {0}")]
    BadField(&'static str),
}

/// Encode failures.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum EncodeError {
    #[error("value out of range: {0}")]
    OutOfRange(&'static str),
    #[error("buffer too small (need {needed})")]
    BufferTooSmall { needed: usize },
}

/// Cross-check helper used by tests on both sides of the wire.
pub fn valid_event_op(op: u8) -> bool {
    matches!(
        op,
        op::KEY_DOWN
            | op::KEY_UP
            | op::MOUSE_DOWN
            | op::MOUSE_UP
            | op::MOUSE_REL
            | op::SCROLL
    )
}
