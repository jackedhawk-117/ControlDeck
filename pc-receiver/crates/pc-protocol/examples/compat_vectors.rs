//! Print byte-level compatibility vectors for cross-platform protocol
//! implementations (currently: the Android Kotlin mirror in android-app/).
//!
//! Run: `cargo run -p pc-protocol --example compat_vectors`
//! The printed hex strings are pinned in the JVM unit tests so any drift
//! between the Rust and Kotlin implementations is caught immediately.

use pc_protocol::crypto::{
    auth_proof, build_nonce, derive_session_key, SessionCrypto, NONCE_CLIENT_TO_SERVER,
};
use pc_protocol::framing::{self, PacketHeader};
use pc_protocol::input::{DeltaEvent, InputDelta};
use pc_protocol::{kind, op};

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02X}")).collect()
}

fn main() {
    // ---- 1. session key derivation ----
    let cn = [1u8; 16];
    let sn = [2u8; 16];
    let key = derive_session_key("123456", &cn, &sn);
    println!("PIN = \"123456\", client_nonce = 01*16, server_nonce = 02*16");
    println!("session_key = {}", hex(&key));

    // ---- 2. AUTH_PROOF (client direction) ----
    let proof = auth_proof(&key, &cn, &sn, 0x0000_3B9A, true);
    println!("auth_proof(client, session_id=0x3B9A) = {}", hex(&proof));

    // ---- 3. GCM nonce layout ----
    let n = build_nonce(NONCE_CLIENT_TO_SERVER, 1);
    println!("nonce(dir=C2S, seq=1) = {}", hex(&n));

    // ---- 4. INPUT_DELTA plaintext encoding ----
    let delta = InputDelta {
        session_id: 0x0000_3B9A,
        events: vec![
            DeltaEvent::key_down(17),                   // KEY_W
            DeltaEvent::mouse_rel_q16(65536 + 32768, 0), // 1.5 px
        ],
        probe_ts: 0x1C_65_E5,
    };
    let plain = delta.encode().unwrap();
    println!("input_delta.plaintext = {}", hex(&plain));

    // ---- 5. full sealed UDP datagram (header || ct||tag) ----
    let header = PacketHeader {
        kind: kind::INPUT_DELTA,
        payload_len: plain.len() as u16,
        seq: 1,
    };
    let crypto = SessionCrypto::new(&key);
    let ct = crypto.seal(NONCE_CLIENT_TO_SERVER, 1, &header.to_bytes(), &plain);
    println!("input_delta.sealed   = {}", hex(&ct));

    // ---- 6. HELLO payload ----
    let hello = framing::Hello {
        proto_version: 1,
        device_name: "vector-phone".into(),
        client_nonce: cn,
    };
    println!("hello.payload        = {}", hex(&hello.encode()));

    // ---- 7. DISCOVER packet (plaintext) ----
    let mut pkt = Vec::new();
    framing::build_packet(kind::DISCOVER, 0, &[], &mut pkt);
    println!("discover.packet      = {}", hex(&pkt));

    // ---- 8. event op constants sanity (for the Kotlin constants) ----
    println!("op KEY_DOWN={} KEY_UP={} MOUSE_DOWN={} MOUSE_UP={} MOUSE_REL={} SCROLL={}",
        op::KEY_DOWN, op::KEY_UP, op::MOUSE_DOWN, op::MOUSE_UP, op::MOUSE_REL, op::SCROLL);
    println!("kind INPUT_DELTA=0x{:02X} SECURE_FRAME=0x{:02X} PING=0x{:02X} PONG=0x{:02X} BYE=0x{:02X} STATS=0x{:02X} DISCOVER=0x{:02X} ANNOUNCE=0x{:02X} HELLO=0x{:02X} HELLO_REPLY=0x{:02X} AUTH_PROOF=0x{:02X}",
        kind::INPUT_DELTA, kind::SECURE_FRAME, kind::PING, kind::PONG, kind::BYE,
        kind::STATS, kind::DISCOVER, kind::ANNOUNCE, kind::HELLO, kind::HELLO_REPLY, kind::AUTH_PROOF);
}
