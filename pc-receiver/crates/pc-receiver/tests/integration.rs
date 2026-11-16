//! End-to-end integration tests: full protocol against a running server
//! with a capture backend (no OS input injection involved).

use input_core::{BackendError, Command, InputBackend, MouseButton};
use pc_protocol::crypto::{NONCE_CLIENT_TO_SERVER, NONCE_SERVER_TO_CLIENT};
use pc_protocol::framing::{self, PacketHeader};
use pc_protocol::handshake::HandshakeTranscript;
use pc_protocol::input::{DeltaEvent, InputDelta, InputSnapshot, Stats};
use pc_protocol::keycodes;
use pc_protocol::{bye_reason, error_code, kind, op, SessionCrypto, Watermark};
use rand::RngCore;
use serde_json::json;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket as AsyncUdpSocket};

use pc_receiver::config::Config;
use pc_receiver::input_bus::spawn_backend_thread;
use pc_receiver::server;

#[derive(Debug, PartialEq)]
enum Log {
    Cmd(Command),
    ReleaseAll,
}

struct CaptureBackend(Arc<Mutex<Vec<Log>>>);

impl InputBackend for CaptureBackend {
    fn apply(&mut self, cmd: Command) -> Result<(), BackendError> {
        self.0.lock().unwrap().push(Log::Cmd(cmd));
        Ok(())
    }
    fn release_all(&mut self) {
        self.0.lock().unwrap().push(Log::ReleaseAll);
    }
    fn name(&self) -> &'static str {
        "capture"
    }
}

async fn spawn_server(pin: &str, input_timeout_ms: u64) -> (server::ServerHandle, Arc<Mutex<Vec<Log>>>) {
    let log = Arc::new(Mutex::new(Vec::new()));
    let bus = spawn_backend_thread(Box::new(CaptureBackend(log.clone())));
    let cfg = Arc::new(Config {
        tcp_port: 0,
        udp_port: 0,
        device_name: "test-pc".into(),
        pin: Some(pin.to_owned()),
        max_sessions: 1,
        input_timeout_ms,
        heartbeat_interval_ms: 100,
        tcp_idle_timeout_ms: 5000,
    });
    let handle = server::start(cfg, bus).await.unwrap();
    (handle, log)
}

fn log_contains(log: &Arc<Mutex<Vec<Log>>>, needle: &Log) -> bool {
    log.lock().unwrap().contains(needle)
}

async fn wait_log_contains(
    log: &Arc<Mutex<Vec<Log>>>,
    needle: Log,
    deadline: Duration,
) -> bool {
    let start = std::time::Instant::now();
    while start.elapsed() < deadline {
        if log_contains(log, &needle) {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    false
}

async fn read_packet<R: tokio::io::AsyncRead + Unpin>(
    r: &mut R,
) -> std::io::Result<Option<(PacketHeader, Vec<u8>)>> {
    let mut header = [0u8; pc_protocol::HEADER_LEN];
    match r.read_exact(&mut header).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let hdr = PacketHeader::parse_header(&header).unwrap();
    let mut wire_len = hdr.payload_len as usize;
    if hdr.kind == kind::SECURE_FRAME {
        wire_len += 16;
    }
    let mut payload = vec![0u8; wire_len];
    if wire_len > 0 {
        r.read_exact(&mut payload).await?;
    }
    Ok(Some((hdr, payload)))
}

struct Client {
    tcp: TcpStream,
    udp: AsyncUdpSocket,
    crypto: SessionCrypto,
    session_id: u32,
    tcp_tx_seq: u32,
    udp_tx_seq: u32,
    udp_rx: Watermark,
    pc_udp: SocketAddr,
}

impl Client {
    async fn connect(port: u16, udp_port: u16, pin: &str) -> Client {
        let mut tcp = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        let mut client_nonce = [0u8; 16];
        rand::rng().fill_bytes(&mut client_nonce);
        let hello = framing::Hello {
            proto_version: pc_protocol::PROTOCOL_VERSION as u16,
            device_name: "integration-client".into(),
            client_nonce,
        };
        let mut out = Vec::new();
        framing::build_packet(kind::HELLO, 0, &hello.encode(), &mut out);
        tcp.write_all(&out).await.unwrap();

        let (_, payload) = read_packet(&mut tcp).await.unwrap().unwrap();
        let reply = framing::HelloReply::decode(&payload).unwrap();
        let transcript = HandshakeTranscript {
            client_nonce,
            server_nonce: reply.server_nonce,
            session_id: reply.session_id,
        };
        let key = transcript.derive_key(pin);
        let proof = transcript.client_proof(&key);
        out.clear();
        framing::build_packet(kind::AUTH_PROOF, 0, &proof, &mut out);
        tcp.write_all(&out).await.unwrap();

        let (_, payload) = read_packet(&mut tcp).await.unwrap().unwrap();
        assert_eq!(payload.len(), 32, "server proof expected");
        let mut server_tag = [0u8; 32];
        server_tag.copy_from_slice(&payload);
        assert!(transcript.verify(&key, &server_tag, false), "server proof must verify");

        let udp = AsyncUdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let pc_udp = SocketAddr::from((Ipv4Addr::LOCALHOST, udp_port));
        Client {
            tcp,
            udp,
            crypto: SessionCrypto::new(&key),
            session_id: reply.session_id,
            tcp_tx_seq: 0,
            udp_tx_seq: 0,
            udp_rx: Watermark::new(0),
            pc_udp,
        }
    }

    async fn send_secure(&mut self, k: u8, payload: &[u8]) {
        self.tcp_tx_seq += 1;
        let mut inner = Vec::new();
        framing::build_packet(k, 0, payload, &mut inner);
        let o_hdr = PacketHeader {
            kind: kind::SECURE_FRAME,
            payload_len: inner.len() as u16,
            seq: self.tcp_tx_seq,
        }
        .to_bytes();
        let ct = self
            .crypto
            .seal(NONCE_CLIENT_TO_SERVER, self.tcp_tx_seq, &o_hdr, &inner);
        let mut out = Vec::with_capacity(o_hdr.len() + ct.len());
        out.extend_from_slice(&o_hdr);
        out.extend_from_slice(&ct);
        self.tcp.write_all(&out).await.unwrap();
    }

    /// Read one secure frame, skipping PING heartbeats. Returns (kind, payload).
    async fn read_secure(&mut self) -> Option<(u8, Vec<u8>)> {
        loop {
            let (hdr, wire) = read_packet(&mut self.tcp).await.ok()??;
            if hdr.kind != kind::SECURE_FRAME {
                continue;
            }
            let plaintext = self
                .crypto
                .open(NONCE_SERVER_TO_CLIENT, &hdr.to_bytes(), hdr.seq, &wire)
                .ok()?;
            let (ihdr, ipayload) = PacketHeader::parse(&plaintext).ok()?;
            match ihdr.kind {
                kind::PING | kind::PONG => continue,
                k => return Some((k, ipayload.to_vec())),
            }
        }
    }

    async fn send_udp(&mut self, k: u8, plaintext_payload: &[u8]) {
        self.udp_tx_seq += 1;
        let header = PacketHeader {
            kind: k,
            payload_len: plaintext_payload.len() as u16,
            seq: self.udp_tx_seq,
        }
        .to_bytes();
        let ct = self
            .crypto
            .seal(NONCE_CLIENT_TO_SERVER, self.udp_tx_seq, &header, plaintext_payload);
        let mut out = Vec::with_capacity(header.len() + ct.len());
        out.extend_from_slice(&header);
        out.extend_from_slice(&ct);
        self.udp.send_to(&out, self.pc_udp).await.unwrap();
    }

    async fn send_delta(&mut self, events: Vec<DeltaEvent>) {
        let delta = InputDelta {
            session_id: self.session_id,
            events,
            probe_ts: 12345,
        };
        self.send_udp(kind::INPUT_DELTA, &delta.encode().unwrap())
            .await;
    }

    async fn send_snapshot(&mut self, keys: &[u16], mouse_bits: u8) {
        let snap = InputSnapshot {
            session_id: self.session_id,
            base_seq: self.udp_tx_seq,
            mouse_bits,
            keys: keys.to_vec(),
        };
        self.send_udp(kind::INPUT_SNAPSHOT, &snap.encode()).await;
    }

    /// Expect a STATS packet on our UDP socket; returns it.
    async fn expect_stats(&mut self, timeout: Duration) -> Stats {
        let mut buf = [0u8; 1400];
        let deadline = std::time::Instant::now() + timeout;
        loop {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            if left.is_zero() {
                panic!("no STATS packet received");
            }
            match tokio::time::timeout(left, self.udp.recv(&mut buf)).await {
                Err(_) => panic!("no STATS packet received"),
                Ok(Err(e)) => panic!("udp recv failed: {e}"),
                Ok(Ok(n)) => {
                    // UDP framing: header.payload_len counts the plaintext;
                    // the ciphertext (+tag) is the datagram remainder.
                    let hdr = PacketHeader::parse_header(&buf[..n]).unwrap();
                    if hdr.kind != kind::STATS || !self.udp_rx.accept(hdr.seq) {
                        continue;
                    }
                    let wire = &buf[pc_protocol::HEADER_LEN..n];
                    let plain = self
                        .crypto
                        .open(NONCE_SERVER_TO_CLIENT, &hdr.to_bytes(), hdr.seq, wire)
                        .unwrap();
                    return Stats::decode(&plain).unwrap();
                }
            }
        }
    }
}

#[tokio::test]
async fn discovery_reply_carries_announce() {
    let (handle, _log) = spawn_server("123456", 5000).await;
    let sock = AsyncUdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let mut pkt = Vec::new();
    framing::build_packet(kind::DISCOVER, 0, &[], &mut pkt);
    let target = SocketAddr::from((Ipv4Addr::LOCALHOST, handle.udp_port));
    sock.send_to(&pkt, target).await.unwrap();

    let mut buf = [0u8; 1400];
    let (n, src) = tokio::time::timeout(Duration::from_secs(2), sock.recv_from(&mut buf))
        .await
        .expect("announce within 2s")
        .unwrap();
    assert_eq!(src, target);
    let (hdr, payload) = PacketHeader::parse(&buf[..n]).unwrap();
    assert_eq!(hdr.kind, kind::ANNOUNCE);
    let ann = framing::Announce::decode(payload).unwrap();
    assert_eq!(ann.name, "test-pc");
    assert!(ann.requires_pin);
    assert_eq!(ann.tcp_port, handle.tcp_port);
}

#[tokio::test]
async fn full_session_input_and_bye() {
    let (handle, log) = spawn_server("123456", 5000).await;
    let mut c = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;

    // press W + move 1.5 px
    c.send_delta(vec![
        DeltaEvent::key_down(keycodes::KEY_W),
        DeltaEvent::mouse_rel_q16(65536 + 32768, 0), // 1.5 px
    ])
    .await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyDown(keycodes::KEY_W)), Duration::from_secs(2)).await,
        "W keydown must reach the backend"
    );
    // 1.5 px → integer part 1 emitted immediately, remainder kept
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::MouseRel { dx: 1, dy: 0 }), Duration::from_secs(2)).await,
        "first integer pixel must be emitted"
    );

    // finish the pixel with another 0.5
    c.send_delta(vec![DeltaEvent::mouse_rel_q16(32768, 0)]).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::MouseRel { dx: 1, dy: 0 }), Duration::from_secs(2)).await,
        "second pixel after accumulation"
    );

    c.send_delta(vec![DeltaEvent::key_up(keycodes::KEY_W)]).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyUp(keycodes::KEY_W)), Duration::from_secs(2)).await
    );

    // graceful BYE
    c.send_secure(kind::BYE, &[bye_reason::NORMAL]).await;
    // server closes after teardown
    let eof = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            match read_packet(&mut c.tcp).await.unwrap() {
                None => break true,
                Some(_) => continue, // drain PINGs
            }
        }
    })
    .await;
    assert!(eof.unwrap_or(false), "stream should close after BYE");
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(handle.registry.live_count().await, 0, "session must be deregistered");
}

#[tokio::test]
async fn wrong_pin_is_rejected() {
    let (handle, _log) = spawn_server("123456", 5000).await;
    let mut tcp = TcpStream::connect(("127.0.0.1", handle.tcp_port)).await.unwrap();
    let mut client_nonce = [0u8; 16];
    rand::rng().fill_bytes(&mut client_nonce);
    let hello = framing::Hello {
        proto_version: pc_protocol::PROTOCOL_VERSION as u16,
        device_name: "attacker".into(),
        client_nonce,
    };
    let mut out = Vec::new();
    framing::build_packet(kind::HELLO, 0, &hello.encode(), &mut out);
    tcp.write_all(&out).await.unwrap();
    let (_, payload) = read_packet(&mut tcp).await.unwrap().unwrap();
    let reply = framing::HelloReply::decode(&payload).unwrap();
    let transcript = HandshakeTranscript {
        client_nonce,
        server_nonce: reply.server_nonce,
        session_id: reply.session_id,
    };
    // wrong PIN: 111111 instead of 123456
    let key = transcript.derive_key("111111");
    let proof = transcript.client_proof(&key);
    out.clear();
    framing::build_packet(kind::AUTH_PROOF, 0, &proof, &mut out);
    tcp.write_all(&out).await.unwrap();

    // server sends ERROR then closes
    let (_, payload) = read_packet(&mut tcp).await.unwrap().unwrap();
    let (code, msg) = framing::parse_error_payload(&payload).unwrap();
    assert_eq!(code, error_code::AUTH_FAILED);
    assert_eq!(msg, "wrong PIN");
    let eof = read_packet(&mut tcp).await.unwrap();
    assert!(eof.is_none(), "connection must close after auth failure");
}

#[tokio::test]
async fn input_silence_timeout_releases_keys() {
    let (handle, log) = spawn_server("123456", 400).await;
    let mut c = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;
    c.send_delta(vec![DeltaEvent::key_down(keycodes::KEY_W)]).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyDown(keycodes::KEY_W)), Duration::from_secs(2)).await
    );
    // stop sending: watchdog (1s cadence, timeout 400ms) must release W,
    // and the session itself stays alive
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyUp(keycodes::KEY_W)), Duration::from_secs(4)).await,
        "watchdog must release held keys on input silence"
    );
    // session still functional: PING/PONG heartbeat continues
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    let mut saw_ping = false;
    while std::time::Instant::now() < deadline {
        if let Ok(Ok(Some(_))) = tokio::time::timeout(Duration::from_millis(200), read_packet(&mut c.tcp)).await {
            saw_ping = true;
            break;
        }
    }
    assert!(saw_ping, "session should stay alive after watchdog release");
}

#[tokio::test]
async fn reset_releases_held_inputs() {
    let (handle, log) = spawn_server("123456", 5000).await;
    let mut c = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;
    c.send_delta(vec![DeltaEvent::key_down(keycodes::KEY_SPACE)]).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyDown(keycodes::KEY_SPACE)), Duration::from_secs(2)).await
    );
    c.send_secure(kind::RESET, &[]).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyUp(keycodes::KEY_SPACE)), Duration::from_secs(2)).await,
        "RESET must release held inputs"
    );
}

#[tokio::test]
async fn snapshot_reconciles_state() {
    let (handle, log) = spawn_server("123456", 5000).await;
    let mut c = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;

    // phone believes W + left mouse are held; receiver knows nothing yet
    c.send_snapshot(&[keycodes::KEY_W], MouseButton::Left.bit()).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyDown(keycodes::KEY_W)), Duration::from_secs(2)).await
    );
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::MouseDown(MouseButton::Left)), Duration::from_secs(2)).await
    );

    // phone releases everything; receiver must release too
    c.send_snapshot(&[], 0).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyUp(keycodes::KEY_W)), Duration::from_secs(2)).await
    );
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::MouseUp(MouseButton::Left)), Duration::from_secs(2)).await
    );
}

#[tokio::test]
async fn new_session_replaces_old_and_releases_its_inputs() {
    let (handle, log) = spawn_server("123456", 5000).await;
    let mut a = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;
    a.send_delta(vec![DeltaEvent::key_down(keycodes::KEY_W)]).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyDown(keycodes::KEY_W)), Duration::from_secs(2)).await
    );

    // second phone pairs; A must be released + replaced
    let mut b = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyUp(keycodes::KEY_W)), Duration::from_secs(2)).await,
        "old owner's inputs must be released on replacement"
    );
    // A receives BYE with reason REPLACED
    let (k, payload) = a.read_secure().await.expect("A must receive BYE");
    assert_eq!(k, kind::BYE);
    assert_eq!(framing::parse_bye_payload(&payload).unwrap(), bye_reason::REPLACED);

    // B can drive input now
    b.send_delta(vec![DeltaEvent::key_down(keycodes::KEY_E)]).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyDown(keycodes::KEY_E)), Duration::from_secs(2)).await
    );
}

#[tokio::test]
async fn stats_probe_and_counters_flow() {
    let (handle, _log) = spawn_server("123456", 5000).await;
    let mut c = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;
    c.send_delta(vec![DeltaEvent::mouse_down(op::MOUSE_LEFT)]).await;
    let stats = c.expect_stats(Duration::from_secs(3)).await;
    assert_eq!(stats.rtt_probe_echo, 12345, "probe_ts must be echoed");
    assert!(stats.last_seq_processed > 0, "processed seq must be tracked");
}

#[tokio::test]
async fn tampered_udp_packet_is_dropped() {
    let (handle, log) = spawn_server("123456", 5000).await;
    let mut c = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;
    c.send_delta(vec![DeltaEvent::key_down(keycodes::KEY_W)]).await;

    // craft a valid delta then flip a ciphertext byte: must not reach backend
    let delta = InputDelta {
        session_id: c.session_id,
        events: vec![DeltaEvent::key_down(keycodes::KEY_A)],
        probe_ts: 7,
    };
    c.udp_tx_seq += 1;
    let wire = delta.encode().unwrap();
    let header = PacketHeader {
        kind: kind::INPUT_DELTA,
        payload_len: wire.len() as u16,
        seq: c.udp_tx_seq,
    }
    .to_bytes();
    let mut ct = c.crypto.seal(NONCE_CLIENT_TO_SERVER, c.udp_tx_seq, &header, &wire);
    ct[0] ^= 0xFF;
    let mut out = Vec::new();
    out.extend_from_slice(&header);
    out.extend_from_slice(&ct);
    c.udp.send_to(&out, c.pc_udp).await.unwrap();

    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(
        !log_contains(&log, &Log::Cmd(Command::KeyDown(keycodes::KEY_A))),
        "tampered packet must never reach the backend"
    );
    // and the session still works
    c.send_delta(vec![DeltaEvent::key_down(keycodes::KEY_D)]).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyDown(keycodes::KEY_D)), Duration::from_secs(2)).await
    );
}

#[tokio::test]
async fn stale_seq_is_rejected() {
    let (handle, log) = spawn_server("123456", 5000).await;
    let _ = &log;
    let mut c = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;
    // seq 1
    c.send_delta(vec![DeltaEvent::key_down(keycodes::KEY_W)]).await;
    assert!(
        wait_log_contains(&log, Log::Cmd(Command::KeyDown(keycodes::KEY_W)), Duration::from_secs(2)).await
    );
    // replay seq 1 with different content (re-encrypt at same seq)
    let delta = InputDelta {
        session_id: c.session_id,
        events: vec![DeltaEvent::key_down(keycodes::KEY_A)],
        probe_ts: 0,
    };
    let saved = c.udp_tx_seq;
    c.udp_tx_seq = 1; // replay the first packet's seq
    let wire = delta.encode().unwrap();
    let header = PacketHeader {
        kind: kind::INPUT_DELTA,
        payload_len: wire.len() as u16,
        seq: 1,
    }
    .to_bytes();
    let ct = c.crypto.seal(NONCE_CLIENT_TO_SERVER, 1, &header, &wire);
    let mut out = Vec::new();
    out.extend_from_slice(&header);
    out.extend_from_slice(&ct);
    c.udp.send_to(&out, c.pc_udp).await.unwrap();
    c.udp_tx_seq = saved;

    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(
        !log_contains(&log, &Log::Cmd(Command::KeyDown(keycodes::KEY_A))),
        "stale seq must be rejected"
    );
}

#[tokio::test]
async fn second_session_replaces_first_within_capacity() {
    // Replacement (owner semantics), not busy-rejection: a second phone may
    // pair while one session is live — the old owner is released, told BYE
    // (REPLACED) and the registry keeps exactly one live session. True
    // busy-rejection only triggers when capacity is exceeded without an
    // owner to replace (e.g. max_sessions = 0).
    let (handle, _log) = spawn_server("123456", 5000).await;
    let a = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;
    let _b = Client::connect(handle.tcp_port, handle.udp_port, "123456").await;
    // A is replaced; registry still has exactly one live session
    tokio::time::sleep(Duration::from_millis(300)).await;
    let count = handle.registry.live_count().await;
    assert_eq!(count, 1, "one active session after replacement");
    let _ = a; // silence unused when assertions above suffice
}

#[tokio::test]
async fn config_serialization_roundtrip() {
    // receiver config is persistable as JSON (debugging + future file config)
    let cfg = json!({
        "tcp_port": 41000, "udp_port": 41000, "device_name": "box",
        "pin": "123456", "max_sessions": 1, "input_timeout_ms": 5000,
        "heartbeat_interval_ms": 2000, "tcp_idle_timeout_ms": 10000
    });
    let parsed: Config = serde_json::from_value(cfg).unwrap();
    assert_eq!(parsed.device_name, "box");
    assert_eq!(parsed.tcp_port, 41000);
    let _ = parsed;
}
