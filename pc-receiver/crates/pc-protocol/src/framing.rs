//! Packet header and control-plane payload encode/decode.

use crate::{kind, op, ParseError, PROTOCOL_VERSION, MAGIC};

/// Fixed 10-byte header (docs/PROTOCOL.md §2).
pub const HEADER_LEN: usize = 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PacketHeader {
    pub kind: u8,
    /// Plaintext payload length (before encryption).
    pub payload_len: u16,
    pub seq: u32,
}

impl PacketHeader {
    /// Serialize the 10-byte header into `buf`.
    pub fn write_into(&self, buf: &mut [u8]) -> Result<(), ParseError> {
        if buf.len() < HEADER_LEN {
            return Err(ParseError::Truncated { needed: HEADER_LEN, got: buf.len() });
        }
        buf[0] = MAGIC[0];
        buf[1] = MAGIC[1];
        buf[2] = PROTOCOL_VERSION;
        buf[3] = self.kind;
        buf[4..6].copy_from_slice(&self.payload_len.to_le_bytes());
        buf[6..10].copy_from_slice(&self.seq.to_le_bytes());
        Ok(())
    }

    /// Serialize to a fresh vector.
    pub fn to_vec(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(HEADER_LEN);
        let _ = self.write_into(&mut v);
        v
    }

    /// Serialize into a fixed-size header buffer.
    pub fn to_bytes(&self) -> [u8; HEADER_LEN] {
        let mut b = [0u8; HEADER_LEN];
        self.write_into(&mut b).expect("10-byte buffer is always enough");
        b
    }

    /// Parse only the fixed header from a buffer (stream readers that fetch
    /// the payload separately must use this; `parse` expects header+payload
    /// in one buffer, e.g. a UDP datagram).
    pub fn parse_header(buf: &[u8]) -> Result<PacketHeader, ParseError> {
        if buf.len() < HEADER_LEN {
            return Err(ParseError::Truncated { needed: HEADER_LEN, got: buf.len() });
        }
        if buf[0] != MAGIC[0] || buf[1] != MAGIC[1] {
            return Err(ParseError::BadMagic);
        }
        if buf[2] != PROTOCOL_VERSION {
            return Err(ParseError::BadVersion(buf[2]));
        }
        Ok(PacketHeader {
            kind: buf[3],
            payload_len: u16::from_le_bytes([buf[4], buf[5]]),
            seq: u32::from_le_bytes([buf[6], buf[7], buf[8], buf[9]]),
        })
    }

    /// Parse header + payload from a single buffer. Payload completeness is
    /// verified; the payload slice borrows from `buf`.
    pub fn parse(buf: &[u8]) -> Result<(PacketHeader, &[u8]), ParseError> {
        let h = Self::parse_header(buf)?;
        let payload = &buf[HEADER_LEN..];
        let need = h.payload_len as usize;
        if payload.len() < need {
            return Err(ParseError::Truncated { needed: need, got: payload.len() });
        }
        Ok((h, &payload[..need]))
    }
}

/// Build a full plaintext packet (header + payload) into `out` (reused buffer).
pub fn build_packet(kind: u8, seq: u32, payload: &[u8], out: &mut Vec<u8>) {
    out.clear();
    // safe capacity push: header then payload
    out.extend_from_slice(&[MAGIC[0], MAGIC[1], PROTOCOL_VERSION, kind]);
    out.extend_from_slice(&(payload.len() as u16).to_le_bytes());
    out.extend_from_slice(&seq.to_le_bytes());
    out.extend_from_slice(payload);
}

/// Build an ERROR packet payload: `u16 code, u8 msg_len, msg`.
pub fn error_payload(code: u16, msg: &str) -> Vec<u8> {
    let mut v = Vec::with_capacity(3 + msg.len());
    v.extend_from_slice(&code.to_le_bytes());
    v.push(msg.len() as u8);
    v.extend_from_slice(msg.as_bytes());
    v
}

/// Decode an ERROR packet payload.
pub fn parse_error_payload(payload: &[u8]) -> Result<(u16, &str), ParseError> {
    if payload.len() < 3 {
        return Err(ParseError::Truncated { needed: 3, got: payload.len() });
    }
    let code = u16::from_le_bytes([payload[0], payload[1]]);
    let len = payload[2] as usize;
    if payload.len() < 3 + len {
        return Err(ParseError::Truncated { needed: 3 + len, got: payload.len() });
    }
    let msg = std::str::from_utf8(&payload[3..3 + len])
        .map_err(|_| ParseError::BadField("error message not utf8"))?;
    Ok((code, msg))
}

/// ANNOUNCE payload (docs/PROTOCOL.md §5.1).
pub struct Announce {
    pub proto_version: u16,
    pub tcp_port: u16,
    pub name: String,
    pub requires_pin: bool,
}

impl Announce {
    pub fn encode(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(6 + self.name.len());
        v.extend_from_slice(&self.proto_version.to_le_bytes());
        v.extend_from_slice(&self.tcp_port.to_le_bytes());
        let name = self.name.as_bytes();
        v.push(name.len() as u8);
        v.extend_from_slice(name);
        v.push(if self.requires_pin { 0b0000_0001 } else { 0 });
        v
    }

    pub fn decode(payload: &[u8]) -> Result<Announce, ParseError> {
        if payload.len() < 6 {
            return Err(ParseError::Truncated { needed: 6, got: payload.len() });
        }
        let proto_version = u16::from_le_bytes([payload[0], payload[1]]);
        let tcp_port = u16::from_le_bytes([payload[2], payload[3]]);
        let name_len = payload[4] as usize;
        if payload.len() < 6 + name_len {
            return Err(ParseError::Truncated { needed: 6 + name_len, got: payload.len() });
        }
        let name = std::str::from_utf8(&payload[5..5 + name_len])
            .map_err(|_| ParseError::BadField("announce name not utf8"))?
            .to_owned();
        let flags = payload[5 + name_len];
        Ok(Announce {
            proto_version,
            tcp_port,
            name,
            requires_pin: flags & 1 != 0,
        })
    }
}

/// HELLO payload (§5.2): u16 version, u8 name_len, name, u8 reserved, [16] nonce.
pub struct Hello {
    pub proto_version: u16,
    pub device_name: String,
    pub client_nonce: [u8; 16],
}

impl Hello {
    pub fn encode(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(4 + self.device_name.len() + 16);
        v.extend_from_slice(&self.proto_version.to_le_bytes());
        let name = self.device_name.as_bytes();
        v.push(name.len() as u8);
        v.extend_from_slice(name);
        v.push(0); // reserved
        v.extend_from_slice(&self.client_nonce);
        v
    }

    pub fn decode(payload: &[u8]) -> Result<Hello, ParseError> {
        if payload.len() < 4 {
            return Err(ParseError::Truncated { needed: 4, got: payload.len() });
        }
        let proto_version = u16::from_le_bytes([payload[0], payload[1]]);
        let name_len = payload[2] as usize;
        if payload.len() < 4 + name_len + 16 {
            return Err(ParseError::Truncated {
                needed: 4 + name_len + 16,
                got: payload.len(),
            });
        }
        let device_name = std::str::from_utf8(&payload[3..3 + name_len])
            .map_err(|_| ParseError::BadField("hello name not utf8"))?
            .to_owned();
        let mut client_nonce = [0u8; 16];
        // 4 = proto_version(2) + name_len(1) + reserved(1): the reserved byte
        // sits between the name and the nonce (§5.2)
        client_nonce.copy_from_slice(&payload[4 + name_len..4 + name_len + 16]);
        Ok(Hello { proto_version, device_name, client_nonce })
    }
}

/// HELLO_REPLY payload (§5.3).
pub struct HelloReply {
    pub proto_version: u16,
    pub session_id: u32,
    pub name: String,
    pub pin_required: bool,
    pub server_nonce: [u8; 16],
}

impl HelloReply {
    pub fn encode(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(7 + self.name.len() + 16);
        v.extend_from_slice(&self.proto_version.to_le_bytes());
        v.extend_from_slice(&self.session_id.to_le_bytes());
        let name = self.name.as_bytes();
        v.push(name.len() as u8);
        v.extend_from_slice(name);
        v.push(if self.pin_required { 1 } else { 0 });
        v.push(0); // reserved
        v.extend_from_slice(&self.server_nonce);
        v
    }

    pub fn decode(payload: &[u8]) -> Result<HelloReply, ParseError> {
        if payload.len() < 7 {
            return Err(ParseError::Truncated { needed: 7, got: payload.len() });
        }
        let proto_version = u16::from_le_bytes([payload[0], payload[1]]);
        let session_id = u32::from_le_bytes([payload[2], payload[3], payload[4], payload[5]]);
        let name_len = payload[6] as usize;
        if payload.len() < 7 + name_len + 18 {
            return Err(ParseError::Truncated {
                needed: 7 + name_len + 18,
                got: payload.len(),
            });
        }
        let name = std::str::from_utf8(&payload[7..7 + name_len])
            .map_err(|_| ParseError::BadField("hello_reply name not utf8"))?
            .to_owned();
        let mut off = 7 + name_len;
        let pin_required = payload[off] & 1 != 0;
        off += 1; // flags
        off += 1; // reserved
        let mut server_nonce = [0u8; 16];
        server_nonce.copy_from_slice(&payload[off..off + 16]);
        Ok(HelloReply { proto_version, session_id, name, pin_required, server_nonce })
    }
}

/// PING/PONG payload: 8-byte opaque timestamp echoed verbatim.
pub fn ping_payload(ts: u64) -> [u8; 8] {
    ts.to_le_bytes()
}

pub fn parse_ping_payload(payload: &[u8]) -> Result<u64, ParseError> {
    if payload.len() != 8 {
        return Err(ParseError::BadField("ping payload must be 8 bytes"));
    }
    Ok(u64::from_le_bytes(payload.try_into().unwrap()))
}

/// BYE payload: single reason byte.
pub fn bye_payload(reason: u8) -> [u8; 1] {
    [reason]
}

pub fn parse_bye_payload(payload: &[u8]) -> Result<u8, ParseError> {
    if payload.is_empty() {
        return Err(ParseError::Truncated { needed: 1, got: 0 });
    }
    Ok(payload[0])
}

/// PROFILE_INFO payload: u8 name_len + name (logging only, §4).
pub fn profile_info_payload(name: &str) -> Vec<u8> {
    let mut v = Vec::with_capacity(1 + name.len());
    v.push(name.len() as u8);
    v.extend_from_slice(name.as_bytes());
    v
}

pub fn parse_profile_info_payload(payload: &[u8]) -> Result<&str, ParseError> {
    if payload.is_empty() {
        return Err(ParseError::Truncated { needed: 1, got: 0 });
    }
    let len = payload[0] as usize;
    if payload.len() < 1 + len {
        return Err(ParseError::Truncated { needed: 1 + len, got: payload.len() });
    }
    std::str::from_utf8(&payload[1..1 + len])
        .map_err(|_| ParseError::BadField("profile name not utf8"))
}

/// Validate that a packet kind is one this protocol knows.
pub fn known_kind(kind_byte: u8) -> bool {
    matches!(
        kind_byte,
        kind::DISCOVER
            | kind::ANNOUNCE
            | kind::HELLO
            | kind::HELLO_REPLY
            | kind::AUTH_PROOF
            | kind::SECURE_FRAME
            | kind::PING
            | kind::PONG
            | kind::BYE
            | kind::RESET
            | kind::PROFILE_INFO
            | kind::ERROR
            | kind::INPUT_DELTA
            | kind::INPUT_SNAPSHOT
            | kind::STATS
    )
}

/// Whether a protocol mouse button id is valid.
pub fn valid_mouse_button(code: u16) -> bool {
    (op::MOUSE_LEFT..=op::MOUSE_FORWARD).contains(&code)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_roundtrip() {
        let h = PacketHeader { kind: kind::INPUT_DELTA, payload_len: 321, seq: 42 };
        let mut buf = vec![0u8; 10 + 321];
        h.write_into(&mut buf).unwrap();
        let (h2, payload) = PacketHeader::parse(&buf).unwrap();
        assert_eq!(h, h2);
        assert_eq!(payload.len(), 321);
    }

    #[test]
    fn header_rejects_magic_and_version() {
        let mut buf = [0u8; 12];
        let h = PacketHeader { kind: 1, payload_len: 2, seq: 0 };
        h.write_into(&mut buf).unwrap();
        buf[0] = b'X';
        assert!(matches!(PacketHeader::parse(&buf), Err(ParseError::BadMagic)));
        buf[0] = MAGIC[0];
        buf[2] = 9;
        assert!(matches!(PacketHeader::parse(&buf), Err(ParseError::BadVersion(9))));
        assert!(!known_kind(0xEE));
    }

    #[test]
    fn header_rejects_truncation() {
        let buf = [0u8; 9];
        assert!(matches!(
            PacketHeader::parse(&buf),
            Err(ParseError::Truncated { .. })
        ));
    }

    #[test]
    fn error_payload_roundtrip() {
        let p = error_payload(crate::error_code::AUTH_FAILED, "wrong pin");
        let (code, msg) = parse_error_payload(&p).unwrap();
        assert_eq!(code, crate::error_code::AUTH_FAILED);
        assert_eq!(msg, "wrong pin");
    }

    #[test]
    fn announce_roundtrip() {
        let a = Announce {
            proto_version: 1,
            tcp_port: 41000,
            name: "cachyos-box".into(),
            requires_pin: true,
        };
        let d = Announce::decode(&a.encode()).unwrap();
        assert_eq!(d.name, "cachyos-box");
        assert!(d.requires_pin);
        assert_eq!(d.tcp_port, 41000);
    }

    #[test]
    fn hello_roundtrip() {
        let hello = Hello {
            proto_version: 1,
            device_name: "pixel 8".into(),
            client_nonce: [7u8; 16],
        };
        let d = Hello::decode(&hello.encode()).unwrap();
        assert_eq!(d.device_name, "pixel 8");
        assert_eq!(d.client_nonce, [7u8; 16]);
    }

    #[test]
    fn hello_reply_roundtrip() {
        let hr = HelloReply {
            proto_version: 1,
            session_id: 0x0000_3B9A,
            name: "pc".into(),
            pin_required: true,
            server_nonce: [9u8; 16],
        };
        let d = HelloReply::decode(&hr.encode()).unwrap();
        assert_eq!(d.session_id, 0x3B9A);
        assert!(d.pin_required);
        assert_eq!(d.server_nonce, [9u8; 16]);
    }

    #[test]
    fn misc_payload_roundtrips() {
        assert_eq!(parse_ping_payload(&ping_payload(0xDEADBEEF)).unwrap(), 0xDEAD_BEEF);
        assert_eq!(parse_bye_payload(&bye_payload(crate::bye_reason::TIMEOUT)).unwrap(), 1);
        assert_eq!(
            parse_profile_info_payload(&profile_info_payload("half-life")).unwrap(),
            "half-life"
        );
    }
}
