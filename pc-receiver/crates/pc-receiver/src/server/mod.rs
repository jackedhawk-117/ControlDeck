//! Server supervisor: binds TCP + UDP, spawns actors, exposes handles.

pub mod registry;
pub mod secure;
pub mod tcp;
pub mod udp;

use crate::config::Config;
use crate::input_bus::BusMsg;
use registry::{Registry, Session};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::mpsc;

/// Handle to a running server (ports resolved after binding).
pub struct ServerHandle {
    pub tcp_port: u16,
    pub udp_port: u16,
    pub registry: Arc<Registry>,
}

/// Start the receiver server. Returns once sockets are bound.
pub async fn start(cfg: Arc<Config>, bus: mpsc::Sender<BusMsg>) -> std::io::Result<ServerHandle> {
    let tcp = TcpListener::bind((std::net::Ipv4Addr::UNSPECIFIED, cfg.tcp_port)).await?;
    let udp_socket =
        UdpSocket::bind((std::net::Ipv4Addr::UNSPECIFIED, cfg.udp_port)).await?;
    let tcp_port = tcp.local_addr()?.port();
    let udp_port = udp_socket.local_addr()?.port();

    let registry = Arc::new(Registry::new(bus.clone()));
    let pending = Arc::new(AtomicUsize::new(0));

    // TCP accept loop
    {
        let cfg = cfg.clone();
        let registry = registry.clone();
        let pending = pending.clone();
        tokio::spawn(async move {
            loop {
                let Ok((stream, peer)) = tcp.accept().await else {
                    break;
                };
                if pending.load(Ordering::Relaxed) >= tcp::MAX_PENDING_HANDSHAKES {
                    tracing::warn!(peer = %peer, "too many pending handshakes; dropping");
                    continue;
                }
                pending.fetch_add(1, Ordering::Relaxed);
                let cfg = cfg.clone();
                let registry = registry.clone();
                let pending = pending.clone();
                tokio::spawn(async move {
                    let io = tcp::SessionIo::new(registry, cfg);
                    let (reader, writer) = stream.into_split();
                    io.run(reader, writer).await;
                    pending.fetch_sub(1, Ordering::Relaxed);
                });
            }
        });
    }

    // UDP router (owns discovery + input + STATS + watchdog)
    tokio::spawn(udp::UdpRouter {
        socket: Arc::new(udp_socket),
        cfg: cfg.clone(),
        tcp_port,
        registry: registry.clone(),
        bus: bus.clone(),
    }
    .run());

    tracing::info!(
        tcp_port,
        udp_port,
        name = %cfg.device_name,
        pin_required = cfg.requires_pin(),
        max_sessions = cfg.max_sessions,
        "server listening"
    );

    Ok(ServerHandle {
        tcp_port,
        udp_port,
        registry,
    })
}

// Re-exported for tests/diagnostics.
pub use registry::mouse_bits_summary;
pub type SharedSession = Arc<Session>;
