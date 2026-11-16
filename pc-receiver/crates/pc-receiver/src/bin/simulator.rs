//! phonecontroller-simulator — a test phone.
//!
//! Walks the same wire protocol as the Android app: discovery, pairing,
//! encrypted input stream, heartbeats, BYE. Used for demos and for the
//! receiver's integration tests.

use clap::Parser;
use pc_protocol::crypto::{NONCE_CLIENT_TO_SERVER, NONCE_SERVER_TO_CLIENT};
use pc_protocol::framing::{self, PacketHeader};
use pc_protocol::handshake::HandshakeTranscript;
use pc_protocol::input::{DeltaEvent, InputDelta, InputSnapshot, Stats};
use pc_protocol::keycodes;
use pc_protocol::{bye_reason, kind, SessionCrypto, Watermark};
use rand::RngCore;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncReadExt, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::net::{TcpStream, UdpSocket as AsyncUdpSocket};

#[derive(Parser, Debug)]
#[command(name = "phonecontroller-simulator", about = "Simulates a PhoneController phone")]
struct Args {
    /// PC address (IP or host). With --discover, may be omitted.
    #[arg(long)]
    host: Option<String>,
    /// TCP control port
    #[arg(long, default_value_t = pc_protocol::DEFAULT_TCP_PORT)]
    port: u16,
    /// Pairing PIN (required unless the receiver runs in open mode)
    #[arg(long)]
    pin: String,
    /// Broadcast discovery before connecting
    #[arg(long)]
    discover: bool,
    /// Demo duration in ms
    #[arg(long, default_value_t = 4000)]
    demo_ms: u64,
    /// Device name to advertise
    #[arg(long, default_value = "simulator")]
    name: String,
}

fn now_ms_u32() -> u32 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u32
}

/// Broadcast DISCOVER and collect ANNOUNCE replies (blocking; pre-runtime).
fn discover(udp_port: u16) -> Vec<framing::Announce> {
    let sock = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("bind udp");
    sock.set_broadcast(true).expect("broadcast");
    let mut pkt = Vec::with_capacity(16);
    framing::build_packet(kind::DISCOVER, 0, &[], &mut pkt);
    for target in [
        SocketAddr::from((Ipv4Addr::BROADCAST, udp_port)),
        SocketAddr::from((Ipv4Addr::LOCALHOST, udp_port)),
    ] {
        let _ = sock.send_to(&pkt, target);
    }
    let mut found = Vec::new();
    let deadline = Instant::now() + Duration::from_millis(2500);
    sock.set_read_timeout(Some(Duration::from_millis(200))).unwrap();
    let mut buf = [0u8; 1400];
    while Instant::now() < deadline {
        match sock.recv_from(&mut buf) {
            Ok((n, src)) => {
                if let Ok((hdr, payload)) = PacketHeader::parse(&buf[..n]) {
                    if hdr.kind == kind::ANNOUNCE {
                        if let Ok(ann) = framing::Announce::decode(payload) {
                            println!(
                                "discovered: {} at {} (tcp {}, pin: {})",
                                ann.name, src.ip(), ann.tcp_port, ann.requires_pin
                            );
                            found.push(ann);
                        }
                    }
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock
                || e.kind() == std::io::ErrorKind::TimedOut => {}
            Err(_) => break,
        }
    }
    found
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
    let hdr = PacketHeader::parse_header(&header)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()))?;
    let mut wire_len = hdr.payload_len as usize;
    if hdr.kind == kind::SECURE_FRAME {
        wire_len += 16;
    }
    let mut payload = vec![0u8; wire_len];
    r.read_exact(&mut payload).await?;
    Ok(Some((hdr, payload)))
}

#[tokio::main]
async fn main() {
    let args = Args::parse();

    // ---- discovery ----
    let mut host = args.host.clone();
    if args.discover {
        let found = discover(args.port);
        if host.is_none() {
            if let Some(ann) = found.first() {
                // ANNOUNCE doesn't carry the IP; the responder's address was
                // printed during discovery. For the demo, fall back to localhost
                // unless --host points at the discovered box.
                eprintln!("discovered PCs above; re-run with --host <ip> to connect");
                let _ = ann;
                if found.iter().any(|a| a.name == "simulator-target") {
                    host = Some("127.0.0.1".into());
                }
            } else {
                eprintln!("no PC discovered; use --host");
                std::process::exit(1);
            }
        }
    }
    let Some(host) = host else {
        eprintln!("--host or --discover is required");
        std::process::exit(2);
    };

    // ---- TCP + handshake ----
    let stream = TcpStream::connect((host.as_str(), args.port))
        .await
        .expect("connect to PC");
    println!("connected to {host}:{}", args.port);
    let (mut tcp_rx, mut tcp_tx) = tokio::io::split(stream);

    let mut client_nonce = [0u8; 16];
    rand::rng().fill_bytes(&mut client_nonce);
    let hello = framing::Hello {
        proto_version: pc_protocol::PROTOCOL_VERSION as u16,
        device_name: args.name.clone(),
        client_nonce,
    };
    let mut out = Vec::new();
    framing::build_packet(kind::HELLO, 0, &hello.encode(), &mut out);
    tcp_tx.write_all(&out).await.expect("send hello");

    let (_, payload) = read_packet(&mut tcp_rx)
        .await
        .expect("read hello reply")
        .expect("stream closed");
    let reply = framing::HelloReply::decode(&payload).expect("parse hello reply");
    if reply.proto_version != pc_protocol::PROTOCOL_VERSION as u16 {
        eprintln!(
            "PC speaks protocol v{}, we speak v{}",
            reply.proto_version,
            pc_protocol::PROTOCOL_VERSION
        );
        std::process::exit(1);
    }
    println!(
        "PC: {} (session {}, pin required: {})",
        reply.name, reply.session_id, reply.pin_required
    );

    let transcript = HandshakeTranscript {
        client_nonce,
        server_nonce: reply.server_nonce,
        session_id: reply.session_id,
    };
    let key = transcript.derive_key(&args.pin);
    let proof = transcript.client_proof(&key);
    out.clear();
    framing::build_packet(kind::AUTH_PROOF, 0, &proof, &mut out);
    tcp_tx.write_all(&out).await.expect("send auth proof");

    // server proof (32 bytes) or an ERROR packet
    let (_, payload) = read_packet(&mut tcp_rx)
        .await
        .expect("read server proof")
        .expect("stream closed");
    if payload.len() != 32 {
        let (code, msg) = framing::parse_error_payload(&payload).unwrap_or((0, "?"));
        eprintln!("pairing failed: {msg} (code {code:#06x})");
        std::process::exit(1);
    }
    let mut server_tag = [0u8; 32];
    server_tag.copy_from_slice(&payload);
    if !transcript.verify(&key, &server_tag, false) {
        eprintln!("PC's proof did not verify (refusing to continue)");
        std::process::exit(1);
    }
    println!("pairing complete (session key established)");

    let crypto = std::sync::Arc::new(SessionCrypto::new(&key));
    let session_id = reply.session_id;

    // TCP reader task: answers PINGs, observes BYE, and performs our own
    // graceful BYE when asked (it owns both stream halves).
    let (bye_flag_tx, bye_flag_rx) = tokio::sync::oneshot::channel::<u8>();
    let (bye_req_tx, mut bye_req_rx) = tokio::sync::mpsc::channel::<()>(1);
    {
        let crypto = crypto.clone();
        tokio::spawn(async move {
            let mut tcp_tx_seq: u32 = 0;
            let mut sealed = Vec::with_capacity(256);
            loop {
                tokio::select! {
                    read = read_packet(&mut tcp_rx) => match read {
                        Ok(Some((hdr, payload))) if hdr.kind == kind::SECURE_FRAME => {
                            let Ok(plain) = crypto.open(
                                NONCE_SERVER_TO_CLIENT, &hdr.to_bytes(), hdr.seq, &payload,
                            ) else {
                                eprintln!("control frame failed to decrypt; exiting");
                                std::process::exit(1);
                            };
                            if let Ok((ihdr, ipayload)) = PacketHeader::parse(&plain) {
                                match ihdr.kind {
                                    kind::PING => {
                                        tcp_tx_seq += 1;
                                        let mut inner = Vec::new();
                                        framing::build_packet(kind::PONG, 0, ipayload, &mut inner);
                                        let o_hdr = PacketHeader {
                                            kind: kind::SECURE_FRAME,
                                            payload_len: inner.len() as u16,
                                            seq: tcp_tx_seq,
                                        }
                                        .to_bytes();
                                        let ct = crypto.seal(
                                            NONCE_CLIENT_TO_SERVER, tcp_tx_seq, &o_hdr, &inner,
                                        );
                                        sealed.clear();
                                        sealed.extend_from_slice(&o_hdr);
                                        sealed.extend_from_slice(&ct);
                                        let _ = tcp_tx.write_all(&sealed).await;
                                    }
                                    kind::BYE => {
                                        let reason =
                                            framing::parse_bye_payload(ipayload).unwrap_or(0);
                                        let _ = bye_flag_tx.send(reason);
                                        return;
                                    }
                                    _ => {}
                                }
                            }
                        }
                        _ => return,
                    },
                    msg = bye_req_rx.recv() => {
                        // graceful BYE (msg: Some(()) requested; None = sender dropped)
                        let _ = msg;
                        tcp_tx_seq += 1;
                        let mut inner = Vec::new();
                        framing::build_packet(
                            kind::BYE,
                            0,
                            &framing::bye_payload(bye_reason::NORMAL),
                            &mut inner,
                        );
                        let o_hdr = PacketHeader {
                            kind: kind::SECURE_FRAME,
                            payload_len: inner.len() as u16,
                            seq: tcp_tx_seq,
                        }
                        .to_bytes();
                        let ct = crypto.seal(NONCE_CLIENT_TO_SERVER, tcp_tx_seq, &o_hdr, &inner);
                        sealed.clear();
                        sealed.extend_from_slice(&o_hdr);
                        sealed.extend_from_slice(&ct);
                        let _ = tcp_tx.write_all(&sealed).await;
                        let _ = tcp_tx.shutdown().await;
                        return;
                    }
                }
            }
        });
    }

    // ---- UDP input socket ----
    let udp = AsyncUdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))
        .await
        .expect("bind udp input");
    let pc_udp: SocketAddr = SocketAddr::from((
        host.parse::<std::net::IpAddr>()
            .unwrap_or_else(|_| Ipv4Addr::LOCALHOST.into()),
        args.port,
    ));

    let mut udp_tx_seq: u32 = 0;
    let udp_rx = Watermark::new(0);
    let start = Instant::now();
    let demo = Duration::from_millis(args.demo_ms);
    let mut last_snapshot = Instant::now();
    let mut last_key_down = false;

    let mut in_buf = [0u8; 1400];
    let mut sealed = Vec::with_capacity(512);

    loop {
        if start.elapsed() >= demo {
            break;
        }
        let t = start.elapsed().as_millis() as u64;
        let demo_total = demo.as_millis() as u64;

        // W pressed between 200ms and 60% of the demo
        let hold_w = t >= 200 && t < demo_total * 3 / 5;
        if hold_w != last_key_down {
            last_key_down = hold_w;
            // send an explicit key event on transitions
            let delta = InputDelta {
                session_id,
                events: vec![if hold_w {
                    DeltaEvent::key_down(keycodes::KEY_W)
                } else {
                    DeltaEvent::key_up(keycodes::KEY_W)
                }],
                probe_ts: now_ms_u32(),
            };
            send_delta(&udp, &crypto, pc_udp, &mut udp_tx_seq, &delta, &mut sealed).await;
        }

        // gentle mouse sway: ±2 px per tick
        let phase = if (t / 100).is_multiple_of(2) { 2.0f64 } else { -2.0f64 };
        let delta = InputDelta {
            session_id,
            events: vec![DeltaEvent::mouse_rel_q16((phase * 65536.0) as i32, 0)],
            probe_ts: now_ms_u32(),
        };
        send_delta(&udp, &crypto, pc_udp, &mut udp_tx_seq, &delta, &mut sealed).await;

        // periodic snapshot
        if last_snapshot.elapsed() >= Duration::from_secs(1) {
            last_snapshot = Instant::now();
            let snap = InputSnapshot {
                session_id,
                base_seq: udp_tx_seq,
                mouse_bits: 0,
                keys: if last_key_down { vec![keycodes::KEY_W] } else { vec![] },
            };
            udp_tx_seq += 1;
            let w = snap.encode();
            let header = PacketHeader {
                kind: kind::INPUT_SNAPSHOT,
                payload_len: w.len() as u16,
                seq: udp_tx_seq,
            }
            .to_bytes();
            let ct = crypto.seal(NONCE_CLIENT_TO_SERVER, udp_tx_seq, &header, &w);
            sealed.clear();
            sealed.extend_from_slice(&header);
            sealed.extend_from_slice(&ct);
            let _ = udp.send_to(&sealed, pc_udp).await;
        }

        // drain STATS replies (bounded wait)
        while let Ok(n) = tokio::time::timeout(Duration::from_millis(5), udp.recv(&mut in_buf)).await
        {
            match n {
                Ok(n) => {
                    if let Ok((hdr, wire_payload)) = PacketHeader::parse(&in_buf[..n]) {
                        if hdr.kind == kind::STATS && udp_rx.accept(hdr.seq) {
                            if let Ok(plain) = crypto.open(
                                NONCE_SERVER_TO_CLIENT,
                                &hdr.to_bytes(),
                                hdr.seq,
                                wire_payload,
                            ) {
                                if let Ok(stats) = Stats::decode(&plain) {
                                    let rtt = now_ms_u32().wrapping_sub(stats.rtt_probe_echo);
                                    println!(
                                        "stats: events/s={} dropped={} last_seq={} rtt≈{}ms",
                                        stats.events_per_sec,
                                        stats.packets_dropped_total,
                                        stats.last_seq_processed,
                                        rtt
                                    );
                                }
                            }
                        }
                    }
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(_) => break,
            }
        }

        tokio::time::sleep(Duration::from_millis(20)).await;
    }

    // ---- graceful BYE (via the reader task) ----
    let _ = bye_req_tx.send(()).await;
    let _ = bye_flag_rx.await;
    println!("demo complete; BYE sent");
}

async fn send_delta(
    udp: &AsyncUdpSocket,
    crypto: &SessionCrypto,
    to: SocketAddr,
    seq: &mut u32,
    delta: &InputDelta,
    sealed: &mut Vec<u8>,
) {
    *seq += 1;
    let wire = delta.encode().expect("encode delta");
    let header = PacketHeader {
        kind: kind::INPUT_DELTA,
        payload_len: wire.len() as u16,
        seq: *seq,
    }
    .to_bytes();
    let ct = crypto.seal(NONCE_CLIENT_TO_SERVER, *seq, &header, &wire);
    sealed.clear();
    sealed.extend_from_slice(&header);
    sealed.extend_from_slice(&ct);
    let _ = udp.send_to(sealed, to).await;
}

// keep ReadHalf/WriteHalf in the API surface (used via tokio::io::split)
type _SplitStreams = (ReadHalf<TcpStream>, WriteHalf<TcpStream>);
