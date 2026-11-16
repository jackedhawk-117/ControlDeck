//! Secure-frame composition and inner control packet handling (TCP).
//!
//! Frame layout: outer plaintext header (kind SECURE_FRAME, seq = tx counter)
//! is the AAD; the encrypted payload is the plaintext inner control packet
//! (its own header + payload), per docs/PROTOCOL.md §6.3.

use crate::server::registry::Session;
use pc_protocol::crypto::NONCE_SERVER_TO_CLIENT;
use pc_protocol::framing::{self, PacketHeader};
use pc_protocol::{error_code, kind};

/// What the actor loop should do after an inner packet was handled.
pub enum Action {
    /// Nothing special; continue the loop.
    None,
    /// Release this session's held inputs (RESET).
    ReleaseInputs,
    /// Close the session gracefully (BYE from phone, or fatal error).
    Close,
}

/// Compose and seal a SECURE_FRAME into `out` (cleared first).
pub fn seal_send(
    session: &Session,
    tx_seq: &mut u32,
    inner_kind: u8,
    payload: &[u8],
    out: &mut Vec<u8>,
) {
    *tx_seq += 1;
    let mut inner = Vec::with_capacity(pc_protocol::HEADER_LEN + payload.len());
    framing::build_packet(inner_kind, 0, payload, &mut inner);
    let outer = PacketHeader {
        kind: kind::SECURE_FRAME,
        payload_len: inner.len() as u16,
        seq: *tx_seq,
    };
    let header = outer.to_bytes();
    let ciphertext = session.crypto.seal(NONCE_SERVER_TO_CLIENT, *tx_seq, &header, &inner);
    out.clear();
    out.extend_from_slice(&header);
    out.extend_from_slice(&ciphertext);
}

/// Handle one decrypted inner control packet. May queue a reply into `out`.
pub fn handle_inner(
    session: &Session,
    plaintext: &[u8],
    tx_seq: &mut u32,
    out: &mut Vec<u8>,
) -> Action {
    let Ok((hdr, payload)) = PacketHeader::parse(plaintext) else {
        session
            .counters
            .dropped_packets
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        return Action::None;
    };
    match hdr.kind {
        kind::PING => {
            // echo the 8-byte timestamp verbatim
            let echo: Vec<u8> = payload.to_vec();
            seal_send(session, tx_seq, kind::PONG, &echo, out);
            Action::None
        }
        kind::PONG => Action::None,
        kind::BYE => {
            let reason = framing::parse_bye_payload(payload).unwrap_or(0);
            tracing::info!(session_id = session.session_id, reason, "phone sent BYE");
            Action::Close
        }
        kind::RESET => {
            tracing::info!(session_id = session.session_id, "RESET: releasing held inputs");
            Action::ReleaseInputs
        }
        kind::PROFILE_INFO => match framing::parse_profile_info_payload(payload) {
            Ok(name) => {
                tracing::info!(session_id = session.session_id, "profile: {name}");
                Action::None
            }
            Err(_) => {
                session
                    .counters
                    .dropped_packets
                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                Action::None
            }
        },
        kind::ERROR => {
            if let Ok((code, msg)) = framing::parse_error_payload(payload) {
                tracing::warn!(session_id = session.session_id, code, "phone error: {msg}");
                if code == error_code::INTERNAL {
                    return Action::Close;
                }
            }
            Action::None
        }
        _ => {
            session
                .counters
                .dropped_packets
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            Action::None
        }
    }
}
