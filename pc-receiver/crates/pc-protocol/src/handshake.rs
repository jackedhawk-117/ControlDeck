//! Pairing handshake flow (docs/PROTOCOL.md §5.4, §6.4).
//!
//! Shared by the receiver (server role), the simulator (client role) and the
//! integration tests, so every implementation follows the exact same recipe.

use crate::crypto::{auth_proof, derive_session_key};

#[derive(Debug, Clone)]
pub struct HandshakeTranscript {
    pub client_nonce: [u8; 16],
    pub server_nonce: [u8; 16],
    pub session_id: u32,
}

impl HandshakeTranscript {
    /// Derive the AES-256 session key for this transcript + PIN.
    pub fn derive_key(&self, pin: &str) -> [u8; 32] {
        derive_session_key(pin, &self.client_nonce, &self.server_nonce)
    }

    /// Tag the phone must send (AUTH_PROOF from client role).
    pub fn client_proof(&self, key: &[u8; 32]) -> [u8; 32] {
        auth_proof(key, &self.client_nonce, &self.server_nonce, self.session_id, true)
    }

    /// Tag the receiver must send (AUTH_PROOF from server role).
    pub fn server_proof(&self, key: &[u8; 32]) -> [u8; 32] {
        auth_proof(key, &self.client_nonce, &self.server_nonce, self.session_id, false)
    }

    /// Verify an incoming proof. `from_client` selects which role the sender
    /// claims. Constant-time comparison via `hmac`'s verify.
    pub fn verify(&self, key: &[u8; 32], tag: &[u8; 32], from_client: bool) -> bool {
        let expected = if from_client {
            self.client_proof(key)
        } else {
            self.server_proof(key)
        };
        // constant-time compare
        let mut diff = 0u8;
        for (a, b) in expected.iter().zip(tag.iter()) {
            diff |= a ^ b;
        }
        tag.len() == 32 && diff == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn transcript() -> HandshakeTranscript {
        HandshakeTranscript {
            client_nonce: [0x11; 16],
            server_nonce: [0x22; 16],
            session_id: 0x0000_3B9A,
        }
    }

    #[test]
    fn correct_pin_completes_handshake() {
        let t = transcript();
        // both sides derive from the same PIN
        let server_key = t.derive_key("824613");
        let client_key = t.derive_key("824613");
        assert_eq!(server_key, client_key);

        let client_tag = t.client_proof(&client_key);
        let server_tag = t.server_proof(&server_key);

        assert!(t.verify(&server_key, &client_tag, true), "server accepts client proof");
        assert!(t.verify(&client_key, &server_tag, false), "client accepts server proof");
    }

    #[test]
    fn wrong_pin_fails_closed() {
        let t = transcript();
        let server_key = t.derive_key("824613");
        let client_key = t.derive_key("824614"); // mistyped
        let client_tag = t.client_proof(&client_key);
        assert!(!t.verify(&server_key, &client_tag, true));
    }

    #[test]
    fn proof_cannot_be_replayed_between_roles() {
        let t = transcript();
        let key = t.derive_key("824613");
        let client_tag = t.client_proof(&key);
        // presenting the client tag as a server tag must fail
        assert!(!t.verify(&key, &client_tag, false));
    }
}
