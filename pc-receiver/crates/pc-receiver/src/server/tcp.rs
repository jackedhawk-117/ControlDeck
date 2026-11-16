//! TCP session actor: pairing handshake, secure control frames, heartbeat,
//! and teardown with guaranteed input release.

use crate::config::Config;
use crate::server::registry::{now_ms, CtlMsg, Registry, Session};
use crate::server::secure::{self, Action};
use pc_protocol::crypto::NONCE_CLIENT_TO_SERVER;
use pc_protocol::framing::{self, PacketHeader};
use pc_protocol::handshake::HandshakeTranscript;
use pc_protocol::{error_code, kind, SessionCrypto, Watermark};
use rand::RngCore;
use std::io;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio::time::{interval, Duration, Instant};

/// Max bytes for any TCP packet payload (plaintext). Malformed peers are
/// dropped well before this.
pub const MAX_TCP_PAYLOAD: usize = 4096;
/// Max concurrent half-open handshakes (flood guard).
pub const MAX_PENDING_HANDSHAKES: usize = 16;

/// Read one framed packet. `None` at clean EOF with zero bytes read.
pub async fn read_packet<R: AsyncRead + Unpin>(
    r: &mut R,
) -> io::Result<Option<(PacketHeader, Vec<u8>)>> {
    let mut header = [0u8; pc_protocol::HEADER_LEN];
    let mut got = 0usize;
    while got < header.len() {
        let n = r.read(&mut header[got..]).await?;
        if n == 0 {
            if got == 0 {
                return Ok(None);
            }
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "partial header"));
        }
        got += n;
    }
    let hdr = PacketHeader::parse_header(&header)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
    if !framing::known_kind(hdr.kind) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unknown kind 0x{:02X}", hdr.kind),
        ));
    }
    let mut wire_len = hdr.payload_len as usize;
    if hdr.kind == kind::SECURE_FRAME {
        wire_len += 16; // GCM tag
    }
    if wire_len > MAX_TCP_PAYLOAD {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "payload too large"));
    }
    let mut payload = vec![0u8; wire_len];
    let mut got = 0usize;
    while got < wire_len {
        let n = r.read(&mut payload[got..]).await?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "partial payload"));
        }
        got += n;
    }
    Ok(Some((hdr, payload)))
}

/// Events the actor loop reacts to. No branch touches the writer; writes
/// happen in one place after the select resolves (borrow-friendly).
enum Event {
    Packet(PacketHeader, Vec<u8>),
    Eof,
    ReadError(io::Error),
    Heartbeat,
    Ctl(Option<CtlMsg>),
}

pub struct SessionIo {
    registry: Arc<Registry>,
    cfg: Arc<Config>,
}

impl SessionIo {
    pub fn new(registry: Arc<Registry>, cfg: Arc<Config>) -> Self {
        Self { registry, cfg }
    }

    /// Run one TCP session to completion. Always releases the session's
    /// inputs and removes it from the registry before returning.
    pub async fn run<R, W>(self, mut reader: R, mut writer: W)
    where
        R: AsyncRead + Unpin,
        W: AsyncWrite + Unpin,
    {
        let (ctl_tx, mut ctl_rx) = mpsc::channel::<CtlMsg>(32);
        let mut out: Vec<u8> = Vec::with_capacity(256);

        // ---- phase 1: HELLO ----
        let (device_name, client_nonce) = match read_packet(&mut reader).await {
            Ok(Some((hdr, payload))) if hdr.kind == kind::HELLO => {
                match framing::Hello::decode(&payload) {
                    Ok(h) => {
                        if h.proto_version != pc_protocol::PROTOCOL_VERSION as u16 {
                            let err = framing::error_payload(
                                error_code::VERSION_MISMATCH,
                                "protocol version mismatch",
                            );
                            framing::build_packet(kind::ERROR, 0, &err, &mut out);
                            let _ = writer.write_all(&out).await;
                            return;
                        }
                        (h.device_name, h.client_nonce)
                    }
                    Err(e) => {
                        tracing::debug!(error = %e, "bad HELLO payload");
                        return;
                    }
                }
            }
            Err(e) => {
                tracing::debug!(error = %e, "HELLO read failed");
                return;
            }
            _ => {
                tracing::debug!("session closed before HELLO");
                return;
            }
        };

        // ---- phase 2: HELLO_REPLY ----
        let session_id = self.registry.alloc_session_id().await;
        let mut server_nonce = [0u8; 16];
        rand::rng().fill_bytes(&mut server_nonce);
        let reply = framing::HelloReply {
            proto_version: pc_protocol::PROTOCOL_VERSION as u16,
            session_id,
            name: self.cfg.device_name.clone(),
            pin_required: self.cfg.requires_pin(),
            server_nonce,
        };
        framing::build_packet(kind::HELLO_REPLY, 0, &reply.encode(), &mut out);
        if writer.write_all(&out).await.is_err() {
            return;
        }

        // ---- phase 3: AUTH_PROOF from phone ----
        let tag = match read_packet(&mut reader).await {
            Ok(Some((hdr, payload))) if hdr.kind == kind::AUTH_PROOF && payload.len() == 32 => {
                payload
            }
            _ => {
                tracing::debug!("no AUTH_PROOF; closing");
                return;
            }
        };
        let transcript = HandshakeTranscript {
            client_nonce,
            server_nonce,
            session_id,
        };
        let pin = self.cfg.pin.clone().unwrap_or_default();
        let key = transcript.derive_key(&pin);
        let mut client_tag = [0u8; 32];
        client_tag.copy_from_slice(&tag);
        if !transcript.verify(&key, &client_tag, true) {
            let err = framing::error_payload(error_code::AUTH_FAILED, "wrong PIN");
            framing::build_packet(kind::ERROR, 0, &err, &mut out);
            let _ = writer.write_all(&out).await;
            tracing::info!(session_id, "pairing failed: wrong PIN");
            return;
        }

        // ---- session established ----
        let session = Arc::new(Session {
            session_id,
            crypto: Arc::new(SessionCrypto::new(&key)),
            device_name: device_name.clone(),
            ctl: ctl_tx,
            tcp_rx_seq: Watermark::new(0),
            udp_rx_seq: Watermark::new(0),
            tcp_tx_seq: std::sync::atomic::AtomicU32::new(0),
            stats_tx_seq: std::sync::atomic::AtomicU32::new(0),
            input: std::sync::Mutex::new(Default::default()),
            counters: Default::default(),
            rtt_probe: std::sync::atomic::AtomicU32::new(0),
            udp_addr: Default::default(),
            last_tcp_ms: std::sync::atomic::AtomicI64::new(now_ms()),
            last_input_ms: std::sync::atomic::AtomicI64::new(now_ms()),
            dead: std::sync::atomic::AtomicBool::new(false),
        });
        self.registry.insert(session.clone()).await;

        // Ownership transfer FIRST: the new phone replaces the previous
        // owner (its inputs are released and it is marked dead + told to
        // close). Only then can capacity be judged — counting the replaced
        // session would make replacement impossible at capacity.
        self.registry.claim_ownership(session_id).await;
        if self.registry.live_count().await > self.cfg.max_sessions {
            let err = framing::error_payload(error_code::SERVER_BUSY, "too many sessions");
            framing::build_packet(kind::ERROR, 0, &err, &mut out);
            let _ = writer.write_all(&out).await;
            self.registry.release_ownership_if(session_id).await;
            self.registry.remove(session_id).await;
            return;
        }
        tracing::info!(session_id, "phone paired; input enabled");

        // ---- phase 4: server AUTH_PROOF ----
        let server_tag = transcript.server_proof(&key);
        framing::build_packet(kind::AUTH_PROOF, 0, &server_tag, &mut out);
        if writer.write_all(&out).await.is_err() {
            self.teardown(&session).await;
            return;
        }

        // ---- phase 5: secure loop ----
        let mut heartbeat = interval(Duration::from_millis(self.cfg.heartbeat_interval_ms));
        heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        let mut last_seen = Instant::now();
        let mut tx_seq: u32 = 0; // server→phone SECURE_FRAME counter

        loop {
            let event = tokio::select! {
                read = read_packet(&mut reader) => match read {
                    Ok(Some((hdr, payload))) => Event::Packet(hdr, payload),
                    Ok(None) => Event::Eof,
                    Err(e) => Event::ReadError(e),
                },
                _ = heartbeat.tick() => Event::Heartbeat,
                ctl = ctl_rx.recv() => Event::Ctl(ctl),
            };

            match event {
                Event::Packet(hdr, wire_payload) => {
                    last_seen = Instant::now();
                    session.mark_seen_tcp(now_ms());
                    if hdr.kind != kind::SECURE_FRAME {
                        session
                            .counters
                            .dropped_packets
                            .fetch_add(1, Ordering::Relaxed);
                        continue;
                    }
                    if !session.tcp_rx_seq.accept(hdr.seq) {
                        session
                            .counters
                            .dropped_packets
                            .fetch_add(1, Ordering::Relaxed);
                        tracing::warn!(
                            session_id,
                            "replayed/stale TCP frame seq {}",
                            hdr.seq
                        );
                        continue;
                    }
                    let plaintext = match session.crypto.open(
                        NONCE_CLIENT_TO_SERVER,
                        &hdr.to_bytes(),
                        hdr.seq,
                        &wire_payload,
                    ) {
                        Ok(p) => p,
                        Err(_) => {
                            session
                                .counters
                                .dropped_packets
                                .fetch_add(1, Ordering::Relaxed);
                            tracing::warn!(session_id, "TCP frame failed to decrypt");
                            break;
                        }
                    };
                    match secure::handle_inner(&session, &plaintext, &mut tx_seq, &mut out) {
                        Action::None => {}
                        Action::ReleaseInputs => {
                            self.registry.release_session_inputs(&session).await;
                        }
                        Action::Close => break,
                    }
                    if !out.is_empty() {
                        if writer.write_all(&out).await.is_err() {
                            break;
                        }
                        out.clear();
                    }
                }
                Event::Heartbeat => {
                    if last_seen.elapsed()
                        > Duration::from_millis(self.cfg.tcp_idle_timeout_ms)
                    {
                        tracing::info!(session_id, "TCP idle timeout");
                        break;
                    }
                    let payload = framing::ping_payload(now_ms() as u64);
                    secure::seal_send(&session, &mut tx_seq, kind::PING, &payload, &mut out);
                    if writer.write_all(&out).await.is_err() {
                        break;
                    }
                    out.clear();
                }
                Event::Ctl(msg) => match msg {
                    Some(CtlMsg::SendSecure { kind: k, payload }) => {
                        secure::seal_send(&session, &mut tx_seq, k, &payload, &mut out);
                        if writer.write_all(&out).await.is_err() {
                            break;
                        }
                        out.clear();
                    }
                    Some(CtlMsg::Close(reason)) => {
                        let payload = framing::bye_payload(reason);
                        secure::seal_send(&session, &mut tx_seq, kind::BYE, &payload, &mut out);
                        let _ = writer.write_all(&out).await;
                        let _ = writer.shutdown().await;
                        break;
                    }
                    None => break, // registry dropped us
                },
                Event::Eof => {
                    tracing::info!(session_id, "phone disconnected (TCP EOF)");
                    break;
                }
                Event::ReadError(e) => {
                    tracing::debug!(session_id, error = %e, "TCP read error");
                    break;
                }
            }
        }

        self.teardown(&session).await;
    }

    /// Release everything this session may hold, then deregister it.
    async fn teardown(&self, session: &Arc<Session>) {
        session.dead.store(true, Ordering::SeqCst);
        self.registry.release_session_inputs(session).await;
        self.registry
            .release_ownership_if(session.session_id)
            .await;
        self.registry.remove(session.session_id).await;
        tracing::info!(
            session_id = session.session_id,
            events = session.counters.events_total.load(Ordering::Relaxed),
            dropped = session.counters.dropped_packets.load(Ordering::Relaxed),
            "session closed"
        );
    }
}
