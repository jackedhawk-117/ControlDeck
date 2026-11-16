//! UDP task: discovery (DISCOVER/ANNOUNCE) and encrypted input routing
//! (INPUT_DELTA / INPUT_SNAPSHOT), plus STATS emission and the safety
//! watchdog.

use crate::config::Config;
use crate::input_bus::{try_apply, BusMsg};
use crate::server::registry::{now_ms, Registry, Session};
use pc_protocol::crypto::NONCE_CLIENT_TO_SERVER;
use pc_protocol::framing::{self, PacketHeader};
use pc_protocol::input::{InputDelta, InputSnapshot, Stats};
use pc_protocol::kind;
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

/// Max UDP datagram we accept (header + plaintext payload, before tag).
pub const MAX_UDP_DATAGRAM: usize = pc_protocol::MAX_UDP_PACKET;
/// ANNOUNCE replies per second per source IP (flood guard).
const ANNOUNCE_RATE_PER_SEC: u32 = 8;

pub struct UdpRouter {
    pub socket: Arc<UdpSocket>,
    pub cfg: Arc<Config>,
    /// Resolved (post-bind) TCP port for ANNOUNCE replies — `cfg.tcp_port`
    /// may still be 0 when the listener bound an ephemeral port.
    pub tcp_port: u16,
    pub registry: Arc<Registry>,
    pub bus: mpsc::Sender<BusMsg>,
}

impl UdpRouter {
    /// Run until the process ends. Owns discovery + input dispatch + STATS +
    /// watchdog cadence.
    pub async fn run(self) {
        let mut buf = vec![0u8; MAX_UDP_DATAGRAM + 64];
        let mut rate: HashMap<IpAddr, (tokio::time::Instant, u32)> = HashMap::new();
        let mut tick = tokio::time::interval(Duration::from_millis(250));
        let mut watchdog_ticks: u32 = 0;

        loop {
            tokio::select! {
                read = self.socket.recv_from(&mut buf) => {
                    let Ok((n, src)) = read else { continue };
                    if n > MAX_UDP_DATAGRAM {
                        continue;
                    }
                    // UDP framing: header.payload_len counts the PLAINTEXT;
                    // the datagram carries header || ciphertext(+tag). parse
                    // only the header and keep the full remainder as the
                    // ciphertext for INPUT kinds.
                    let Ok(hdr) = PacketHeader::parse_header(&buf[..n]) else {
                        continue;
                    };
                    match hdr.kind {
                        kind::DISCOVER => {
                            if !self.rate_ok(&mut rate, src.ip()) {
                                continue;
                            }
                            let announce = framing::Announce {
                                proto_version: pc_protocol::PROTOCOL_VERSION as u16,
                                tcp_port: self.tcp_port,
                                name: self.cfg.device_name.clone(),
                                requires_pin: self.cfg.requires_pin(),
                            };
                            let announce_buf = announce.encode();
                            let mut pkt = Vec::with_capacity(pc_protocol::HEADER_LEN + announce_buf.len());
                            framing::build_packet(kind::ANNOUNCE, 0, &announce_buf, &mut pkt);
                            let _ = self.socket.send_to(&pkt, src).await;
                        }
                        kind::INPUT_DELTA | kind::INPUT_SNAPSHOT => {
                            self.route_input(&hdr, &buf[pc_protocol::HEADER_LEN..n], src).await;
                        }
                        _ => {}
                    }
                }
                _ = tick.tick() => {
                    // STATS for active sessions
                    for session in self.registry.all().await {
                        if session.dead.load(Ordering::Relaxed) {
                            continue;
                        }
                        let events = session.counters.events_since_stats.swap(0, Ordering::Relaxed);
                        session.counters.last_events_per_sec.store(events * 4, Ordering::Relaxed);
                        let had_traffic = events > 0
                            || session.udp_addr.try_lock().map(|a| a.is_some()).unwrap_or(false);
                        if !had_traffic {
                            continue;
                        }
                        self.send_stats(&session).await;
                    }
                    // safety watchdog every 4th tick (1 s)
                    watchdog_ticks += 1;
                    if watchdog_ticks.is_multiple_of(4) {
                        let now = now_ms();
                        for session in self.registry.all().await {
                            if session.dead.load(Ordering::Relaxed) {
                                continue;
                            }
                            let idle = now - session.last_input_ms.load(Ordering::Relaxed);
                            if idle > self.cfg.input_timeout_ms as i64 {
                                let was_empty = session.input.lock().unwrap().is_empty();
                                if !was_empty {
                                    tracing::warn!(
                                        session_id = session.session_id,
                                        idle_ms = idle,
                                        "input silence timeout: releasing all held inputs"
                                    );
                                    self.registry.release_session_inputs(&session).await;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fn rate_ok(
        &self,
        rate: &mut HashMap<IpAddr, (tokio::time::Instant, u32)>,
        ip: IpAddr,
    ) -> bool {
        let now = tokio::time::Instant::now();
        let entry = rate.entry(ip).or_insert((now, 0));
        if now.duration_since(entry.0) >= Duration::from_secs(1) {
            *entry = (now, 0);
        }
        entry.1 += 1;
        entry.1 <= ANNOUNCE_RATE_PER_SEC
    }

    /// Route an encrypted input packet: try each live session key (there is
    /// almost always exactly one). Successful decryption *is* authentication.
    async fn route_input(&self, hdr: &PacketHeader, wire_payload: &[u8], src: SocketAddr) {
        let registry = &self.registry;
        let sessions = registry.all().await;
        let mut plaintext: Option<(Arc<Session>, Vec<u8>)> = None;
        for session in &sessions {
            if session.dead.load(Ordering::Relaxed) {
                continue;
            }
            match session.crypto.open(
                NONCE_CLIENT_TO_SERVER,
                &hdr.to_bytes(),
                hdr.seq,
                wire_payload,
            ) {
                Ok(p) => {
                    plaintext = Some((session.clone(), p));
                    break;
                }
                Err(_) => continue,
            }
        }
        let Some((session, plaintext)) = plaintext else {
            // no session accepted it: unauthenticated or stale
            return;
        };

        // replay / duplicate guard on the UDP seq stream
        if !session.udp_rx_seq.accept(hdr.seq) {
            session.counters.dropped_packets.fetch_add(1, Ordering::Relaxed);
            return;
        }

        // authenticated: record source (roaming allowed) + liveness
        {
            let mut addr = session.udp_addr.lock().await;
            if *addr != Some(src) {
                *addr = Some(src);
                tracing::debug!(session_id = session.session_id, "UDP peer addr = {src}");
            }
        }
        session.mark_seen_input(now_ms());

        match hdr.kind {
            kind::INPUT_DELTA => {
                match InputDelta::decode(&plaintext) {
                    Ok(delta) => {
                        if delta.session_id != session.session_id {
                            session.counters.dropped_packets.fetch_add(1, Ordering::Relaxed);
                            return;
                        }
                        session.rtt_probe.store(delta.probe_ts, Ordering::Relaxed);
                        let mut cmds = Vec::with_capacity(delta.events.len());
                        {
                            let mut st = session.input.lock().unwrap();
                            for e in &delta.events {
                                cmds.extend_from_slice(st.apply_event(e));
                            }
                        }
                        session
                            .counters
                            .events_total
                            .fetch_add(delta.events.len() as u64, Ordering::Relaxed);
                        session
                            .counters
                            .events_since_stats
                            .fetch_add(delta.events.len() as u32, Ordering::Relaxed);
                        let mut dropped = 0u32;
                        try_apply(&self.bus, cmds, &mut dropped);
                        if dropped > 0 {
                            session.counters.dropped_batches.fetch_add(dropped, Ordering::Relaxed);
                        }
                    }
                    Err(_) => {
                        session.counters.dropped_packets.fetch_add(1, Ordering::Relaxed);
                    }
                }
            }
            kind::INPUT_SNAPSHOT => {
                match InputSnapshot::decode(&plaintext) {
                    Ok(snap) => {
                        if snap.session_id != session.session_id {
                            session.counters.dropped_packets.fetch_add(1, Ordering::Relaxed);
                            return;
                        }
                        let cmds = {
                            let mut st = session.input.lock().unwrap();
                            st.apply_snapshot(&snap).to_vec()
                        };
                        let mut dropped = 0u32;
                        try_apply(&self.bus, cmds, &mut dropped);
                        if dropped > 0 {
                            session.counters.dropped_batches.fetch_add(dropped, Ordering::Relaxed);
                        }
                        tracing::debug!(session_id = session.session_id, "snapshot reconciled");
                    }
                    Err(_) => {
                        session.counters.dropped_packets.fetch_add(1, Ordering::Relaxed);
                    }
                }
            }
            _ => {}
        }
    }

    async fn send_stats(&self, session: &Session) {
        let Some(src) = *session.udp_addr.lock().await else {
            return;
        };
        let stats = Stats {
            last_seq_processed: session.udp_rx_seq.current(),
            events_per_sec: session.counters.last_events_per_sec.load(Ordering::Relaxed),
            packets_dropped_total: session.counters.dropped_packets.load(Ordering::Relaxed),
            rtt_probe_echo: session.rtt_probe.load(Ordering::Relaxed),
        };
        let seq = session.next_stats_tx_seq();
        // STATS is a plain encrypted packet (no SECURE_FRAME wrapper on UDP):
        // outer header kind=STATS is the AAD, payload = encrypted STATS.
        let mut out = Vec::with_capacity(pc_protocol::HEADER_LEN + 32);
        let header = PacketHeader {
            kind: kind::STATS,
            payload_len: Stats::WIRE_SIZE as u16,
            seq,
        }
        .to_bytes();
        let ct = session
            .crypto
            .seal(pc_protocol::crypto::NONCE_SERVER_TO_CLIENT, seq, &header, &stats.encode());
        out.extend_from_slice(&header);
        out.extend_from_slice(&ct);
        let _ = self.socket.send_to(&out, src).await;
        session.counters.stats_sent.fetch_add(1, Ordering::Relaxed);
    }
}
