//! Session registry and per-session state.

use crate::input_bus::BusMsg;
use input_core::{InputState, MouseButton};
use pc_protocol::{SessionCrypto, Watermark};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;
use tokio::sync::{mpsc, Mutex as AsyncMutex, RwLock};

/// Control messages into a TCP session actor.
#[derive(Debug)]
pub enum CtlMsg {
    /// Actor must release the session and close the TCP connection.
    Close(u8),
    /// Server-initiated secure packet (e.g. BYE before close).
    SendSecure { kind: u8, payload: Vec<u8> },
}

/// Counters surfaced to STATS and diagnostics.
#[derive(Debug, Default)]
pub struct SessionCounters {
    pub events_total: AtomicU64,
    pub dropped_packets: AtomicU32,
    pub dropped_batches: AtomicU32,
    pub stats_sent: AtomicU32,
    /// events since last stats tick (reset by the stats task).
    pub events_since_stats: AtomicU32,
    /// events/sec as of the last stats tick.
    pub last_events_per_sec: AtomicU32,
}

/// One established (or establishing→secure) session.
pub struct Session {
    pub session_id: u32,
    pub crypto: Arc<SessionCrypto>,
    pub device_name: String,
    pub ctl: mpsc::Sender<CtlMsg>,

    // replay watermarks: independent seq streams over the same key
    pub tcp_rx_seq: Watermark,
    pub udp_rx_seq: Watermark,
    /// Server→phone TCP SECURE_FRAME counter (starts at 1).
    pub tcp_tx_seq: AtomicU32,
    /// Server→phone UDP STATS counter (starts at 1).
    pub stats_tx_seq: AtomicU32,

    /// Per-session input state machine (authority for this phone's held inputs).
    pub input: Mutex<InputState>,

    pub counters: SessionCounters,
    /// Latest RTT probe value echoed by the phone (u32 ms).
    pub rtt_probe: AtomicU32,
    /// Source address of the last authenticated UDP input packet (roaming OK).
    pub udp_addr: AsyncMutex<Option<std::net::SocketAddr>>,
    /// Monotonic milliseconds of the last inbound TCP packet.
    pub last_tcp_ms: AtomicI64,
    /// Monotonic milliseconds of the last inbound UDP input packet.
    pub last_input_ms: AtomicI64,
    /// Once true, the session is being torn down; UDP packets are ignored.
    pub dead: AtomicBool,
}

impl Session {
    pub fn mark_seen_tcp(&self, now_ms: i64) {
        self.last_tcp_ms.store(now_ms, Ordering::Relaxed);
    }
    pub fn mark_seen_input(&self, now_ms: i64) {
        self.last_input_ms.store(now_ms, Ordering::Relaxed);
    }
    pub fn next_tcp_tx_seq(&self) -> u32 {
        self.tcp_tx_seq.fetch_add(1, Ordering::Relaxed) + 1
    }
    pub fn next_stats_tx_seq(&self) -> u32 {
        self.stats_tx_seq.fetch_add(1, Ordering::Relaxed) + 1
    }
}

/// Monotonic milliseconds (process-wide reference point at registry creation).
pub fn now_ms() -> i64 {
    use std::sync::OnceLock;
    static START: OnceLock<Instant> = OnceLock::new();
    let start = START.get_or_init(Instant::now);
    start.elapsed().as_millis() as i64
}

/// Registry of live sessions + the single input "owner".
pub struct Registry {
    sessions: RwLock<HashMap<u32, Arc<Session>>>,
    owner: AsyncMutex<Option<u32>>,
    bus: mpsc::Sender<BusMsg>,
}

impl Registry {
    pub fn new(bus: mpsc::Sender<BusMsg>) -> Self {
        Self {
            sessions: RwLock::new(HashMap::new()),
            owner: AsyncMutex::new(None),
            bus,
        }
    }

    /// Random nonzero, unused session id.
    pub async fn alloc_session_id(&self) -> u32 {
        loop {
            let id = rand::random::<u32>();
            if id == 0 {
                continue;
            }
            if !self.sessions.read().await.contains_key(&id) {
                return id;
            }
        }
    }

    /// Count of live (non-dead) sessions. A replaced session is marked dead
    /// the moment ownership transfers; its registry entry disappears when its
    /// actor tears down, so capacity checks must not count it.
    pub async fn live_count(&self) -> usize {
        self.sessions
            .read()
            .await
            .values()
            .filter(|s| !s.dead.load(Ordering::Relaxed))
            .count()
    }

    pub async fn insert(&self, session: Arc<Session>) {
        self.sessions.write().await.insert(session.session_id, session);
    }

    pub async fn get(&self, id: u32) -> Option<Arc<Session>> {
        self.sessions.read().await.get(&id).cloned()
    }

    pub async fn remove(&self, id: u32) -> Option<Arc<Session>> {
        let removed = self.sessions.write().await.remove(&id);
        if let Some(s) = &removed {
            s.dead.store(true, Ordering::SeqCst);
        }
        removed
    }

    pub async fn all(&self) -> Vec<Arc<Session>> {
        self.sessions.read().await.values().cloned().collect()
    }

    /// Release one session's held inputs through its own state machine and
    /// emit the resulting up-events to the backend.
    pub async fn release_session_inputs(&self, session: &Session) {
        let cmds = {
            let mut st = session.input.lock().unwrap();
            if st.is_empty() {
                return;
            }
            st.release_all().to_vec()
        };
        let _ = self
            .bus
            .send(BusMsg::Apply { cmds })
            .await;
    }

    /// Make `id` the single input owner, releasing the previous owner's
    /// inputs first (Engineering Rule 11: release before switching).
    pub async fn claim_ownership(&self, id: u32) {
        let mut owner = self.owner.lock().await;
        if let Some(old) = *owner {
            if old != id {
                if let Some(old_session) = self.get(old).await {
                    self.release_session_inputs(&old_session).await;
                    old_session.dead.store(true, Ordering::SeqCst);
                    let _ = old_session
                        .ctl
                        .send(CtlMsg::Close(pc_protocol::bye_reason::REPLACED))
                        .await;
                }
            }
        }
        *owner = Some(id);
    }

    /// Drop ownership if held by `id`, releasing its inputs first.
    pub async fn release_ownership_if(&self, id: u32) {
        let mut owner = self.owner.lock().await;
        if *owner == Some(id) {
            *owner = None;
            drop(owner);
            if let Some(session) = self.get(id).await {
                self.release_session_inputs(&session).await;
            }
        }
    }

    /// Full teardown used at shutdown: release everything.
    pub async fn release_everything(&self) {
        for session in self.all().await {
            self.release_session_inputs(&session).await;
        }
        let _ = self.bus.send(BusMsg::ReleaseAll).await;
    }
}

/// Diagnostic view of mouse buttons held in a session's state.
pub fn mouse_bits_summary(bits: u8) -> Vec<&'static str> {
    let mut out = Vec::new();
    for (bit, name) in [
        (MouseButton::Left.bit(), "left"),
        (MouseButton::Right.bit(), "right"),
        (MouseButton::Middle.bit(), "middle"),
        (MouseButton::Back.bit(), "back"),
        (MouseButton::Forward.bit(), "forward"),
    ] {
        if bits & bit != 0 {
            out.push(name);
        }
    }
    out
}
