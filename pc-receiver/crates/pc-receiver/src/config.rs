//! Server configuration.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// TCP control port (0 = ephemeral, used by tests).
    pub tcp_port: u16,
    /// UDP input/discovery port (0 = ephemeral, used by tests).
    pub udp_port: u16,
    /// Name shown in the phone's discovery list.
    pub device_name: String,
    /// PIN required for pairing. `None` = open pairing (explicit opt-in).
    pub pin: Option<String>,
    /// Max concurrent TCP sessions. The single input "owner" is one of them.
    pub max_sessions: usize,
    /// UDP input silence before held inputs are released (safety watchdog).
    pub input_timeout_ms: u64,
    /// TCP heartbeat interval.
    pub heartbeat_interval_ms: u64,
    /// TCP idle before a session is dropped.
    pub tcp_idle_timeout_ms: u64,
}

impl Config {
    pub fn validate(&self) -> Result<(), String> {
        if self.max_sessions == 0 {
            return Err("max_sessions must be at least 1".into());
        }
        if let Some(pin) = &self.pin {
            let digits = pin.chars().collect::<Vec<_>>();
            if digits.len() < 4 || !digits.iter().all(|c| c.is_ascii_digit()) {
                return Err("PIN must be at least 4 ASCII digits".into());
            }
        }
        Ok(())
    }

    pub fn requires_pin(&self) -> bool {
        self.pin.is_some()
    }
}
