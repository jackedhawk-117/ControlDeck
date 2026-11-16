//! Session cryptography: key derivation, AES-256-GCM sealing/opening,
//! replay protection via direction-tagged counters (docs/PROTOCOL.md §6).

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::sync::atomic::{AtomicU32, Ordering};

pub type HmacSha256 = Hmac<Sha256>;

/// Nonce direction byte: 0x00 = phone→pc, 0x01 = pc→phone.
pub const NONCE_CLIENT_TO_SERVER: u8 = 0x00;
pub const NONCE_SERVER_TO_CLIENT: u8 = 0x01;

/// HKDF info label (docs/PROTOCOL.md §5.4).
pub const KDF_INFO: &[u8] = b"pc session key";

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum CryptoError {
    #[error("authentication failed (wrong key or tampered packet)")]
    AuthFailed,
}

/// Build the session key from the pairing PIN and both nonces.
///
/// `ikm = "phonecontroller-v1" || 0x00 || PIN || client_nonce || server_nonce`
/// HKDF-SHA256, salt = none (zeroed), info = "pc session key", 32 bytes.
pub fn derive_session_key(
    pin: &str,
    client_nonce: &[u8; 16],
    server_nonce: &[u8; 16],
) -> [u8; 32] {
    let mut ikm = Vec::with_capacity(18 + pin.len() + 32);
    ikm.extend_from_slice(b"phonecontroller-v1");
    ikm.push(0x00);
    ikm.extend_from_slice(pin.as_bytes());
    ikm.extend_from_slice(client_nonce);
    ikm.extend_from_slice(server_nonce);

    let hk = Hkdf::<Sha256>::new(None, &ikm);
    let mut key = [0u8; 32];
    hk.expand(KDF_INFO, &mut key).expect("32 byte output is valid");
    key
}

/// Compute the handshake AUTH_PROOF tag.
///
/// `tag = HMAC-SHA256(key, client_nonce || server_nonce || session_id_le || dir)`
pub fn auth_proof(
    key: &[u8; 32],
    client_nonce: &[u8; 16],
    server_nonce: &[u8; 16],
    session_id: u32,
    from_client: bool,
) -> [u8; 32] {
    let mut mac = <HmacSha256 as Mac>::new_from_slice(key).expect("hmac accepts 32-byte keys");
    mac.update(client_nonce);
    mac.update(server_nonce);
    mac.update(&session_id.to_le_bytes());
    mac.update(&[if from_client { 0 } else { 1 }]);
    let mut out = [0u8; 32];
    out.copy_from_slice(&mac.finalize().into_bytes());
    out
}

/// Build the 12-byte GCM nonce: `direction(1) || seq (7-byte LE, zero-padded)`.
pub fn build_nonce(direction: u8, seq: u32) -> [u8; 12] {
    let seq_le = seq.to_le_bytes();
    [
        direction, seq_le[0], seq_le[1], seq_le[2], seq_le[3], 0, 0, 0, 0, 0, 0, 0,
    ]
}

/// Per-session crypto: AES-256-GCM with AAD = full packet header.
///
/// Stateless with respect to sequence numbers: TCP SECURE_FRAMEs and UDP
/// input packets are **independent seq streams over the same key/direction**,
/// so replay protection is per-stream and lives with the session state
/// (see [`Watermark`]).
pub struct SessionCrypto {
    cipher: Aes256Gcm,
}

/// Monotonic per-stream seq tracker; accepts strictly increasing values.
#[derive(Debug)]
pub struct Watermark(AtomicU32);

impl Watermark {
    pub fn new(initial: u32) -> Self {
        Self(AtomicU32::new(initial))
    }
    /// Returns true if `v` is newer than the current watermark and promotes
    /// it. Zero is never accepted (seq starts at 1).
    pub fn accept(&self, v: u32) -> bool {
        if v == 0 {
            return false;
        }
        let mut cur = self.0.load(Ordering::Relaxed);
        while v > cur {
            match self
                .0
                .compare_exchange_weak(cur, v, Ordering::Relaxed, Ordering::Relaxed)
            {
                Ok(_) => return true,
                Err(actual) => cur = actual,
            }
        }
        false
    }

    pub fn current(&self) -> u32 {
        self.0.load(Ordering::Relaxed)
    }
}

impl SessionCrypto {
    pub fn new(key: &[u8; 32]) -> Self {
        Self {
            cipher: Aes256Gcm::new_from_slice(key).expect("AES-256 key is 32 bytes"),
        }
    }

    /// Seal `plaintext` (payload only) with AAD = 10-byte header.
    /// Returns ciphertext || 16-byte tag.
    pub fn seal(
        &self,
        direction: u8,
        seq: u32,
        header: &[u8; 10],
        plaintext: &[u8],
    ) -> Vec<u8> {
        let nonce_bytes = build_nonce(direction, seq);
        let nonce = Nonce::from_slice(&nonce_bytes);
        self.cipher
            .encrypt(
                nonce,
                Payload { msg: plaintext, aad: header },
            )
            .expect("AES-GCM encryption cannot fail with valid inputs")
    }

    /// Open an on-wire encrypted payload (ciphertext || tag).
    /// Returns the plaintext payload. Replay detection is the caller's job:
    /// check `watermark.accept(seq)` (as part of the combined
    /// watermark+tag check, never trust the watermark alone).
    pub fn open(
        &self,
        direction: u8,
        header: &[u8; 10],
        seq: u32,
        wire_payload: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        if wire_payload.len() < 16 {
            return Err(CryptoError::AuthFailed);
        }
        let nonce_bytes = build_nonce(direction, seq);
        let nonce = Nonce::from_slice(&nonce_bytes);
        self.cipher
            .decrypt(nonce, Payload { msg: wire_payload, aad: header })
            .map_err(|_| CryptoError::AuthFailed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(kind: u8, len: u16, seq: u32) -> [u8; 10] {
        let mut h = [0u8; 10];
        h[0] = crate::MAGIC[0];
        h[1] = crate::MAGIC[1];
        h[2] = crate::PROTOCOL_VERSION;
        h[3] = kind;
        h[4..6].copy_from_slice(&len.to_le_bytes());
        h[6..10].copy_from_slice(&seq.to_le_bytes());
        h
    }

    #[test]
    fn key_derivation_matches_documented_recipe() {
        let cn = [1u8; 16];
        let sn = [2u8; 16];
        let k1 = derive_session_key("123456", &cn, &sn);
        let k2 = derive_session_key("123456", &cn, &sn);
        assert_eq!(k1, k2, "derivation must be deterministic");
        assert_ne!(k1, derive_session_key("123457", &cn, &sn), "PIN changes key");
        assert_ne!(k1, derive_session_key("123456", &cn, &[3u8; 16]), "nonce changes key");
    }

    #[test]
    fn auth_proof_is_direction_bound() {
        let key = derive_session_key("654321", &[5u8; 16], &[6u8; 16]);
        let c = auth_proof(&key, &[5u8; 16], &[6u8; 16], 42, true);
        let s = auth_proof(&key, &[5u8; 16], &[6u8; 16], 42, false);
        assert_ne!(c, s, "client and server proofs must differ (dir byte)");
        assert_eq!(c, auth_proof(&key, &[5u8; 16], &[6u8; 16], 42, true));
    }

    #[test]
    fn seal_open_roundtrip_and_tamper() {
        let key = derive_session_key("111111", &[7u8; 16], &[8u8; 16]);
        let crypto = SessionCrypto::new(&key);
        let h = header(crate::kind::INPUT_DELTA, 4, 5);
        let pt = [0xABu8, 0, 0, 0];
        let ct = crypto.seal(NONCE_CLIENT_TO_SERVER, 5, &h, &pt);
        assert_eq!(ct.len(), pt.len() + 16);
        let open = crypto.open(NONCE_CLIENT_TO_SERVER, &h, 5, &ct);
        assert_eq!(open.unwrap(), pt);

        // tamper with a single ciphertext byte
        let mut bad = ct.clone();
        bad[0] ^= 1;
        assert!(crypto.open(NONCE_CLIENT_TO_SERVER, &h, 6, &bad).is_err());

        // tamper with the AAD (header)
        let mut bad_h = h;
        bad_h[6] ^= 1;
        assert!(crypto.open(NONCE_CLIENT_TO_SERVER, &bad_h, 7, &ct).is_err());
    }

    #[test]
    fn replay_and_reorder_rejected_with_explicit_watermark() {
        let key = derive_session_key("000001", &[9u8; 16], &[10u8; 16]);
        let crypto = SessionCrypto::new(&key);
        let wm = Watermark::new(0);
        let pt = [1u8, 2, 3];
        let h = header(crate::kind::INPUT_DELTA, 3, 1);
        let ct = crypto.seal(NONCE_CLIENT_TO_SERVER, 1, &h, &pt);

        assert!(wm.accept(1));
        assert!(crypto.open(NONCE_CLIENT_TO_SERVER, &h, 1, &ct).is_ok());
        assert!(
            !wm.accept(1),
            "duplicate seq must be rejected by the watermark"
        );
        assert!(!wm.accept(0), "seq 0 never accepted");
        // gap is fine, backwards is not
        let ct9 = crypto.seal(NONCE_CLIENT_TO_SERVER, 9, &header(1, 3, 9), &pt);
        assert!(wm.accept(9));
        assert!(crypto.open(NONCE_CLIENT_TO_SERVER, &header(1, 3, 9), 9, &ct9).is_ok());
        assert!(!wm.accept(2));
    }

    #[test]
    fn directions_are_independent() {
        let key = derive_session_key("424242", &[11u8; 16], &[12u8; 16]);
        let crypto = SessionCrypto::new(&key);
        let h = header(crate::kind::INPUT_DELTA, 1, 1);
        let c2s = crypto.seal(NONCE_CLIENT_TO_SERVER, 1, &h, &[1]);
        let s2c = crypto.seal(NONCE_SERVER_TO_CLIENT, 1, &h, &[2]);
        // opening in the wrong direction must fail (nonce direction byte)
        assert!(crypto.open(NONCE_SERVER_TO_CLIENT, &h, 1, &c2s).is_err());
        assert_eq!(crypto.open(NONCE_CLIENT_TO_SERVER, &h, 1, &c2s).unwrap(), [1]);
        assert_eq!(crypto.open(NONCE_SERVER_TO_CLIENT, &h, 1, &s2c).unwrap(), [2]);
    }
}
