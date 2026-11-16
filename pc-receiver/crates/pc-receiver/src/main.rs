//! PhoneController receiver entry point.

use clap::Parser;
use pc_receiver::config::Config;
use input_core::InputBackend;
use pc_receiver::input_bus::{install_panic_hook, spawn_backend_thread, BusMsg};
use rand::Rng;
use std::sync::Arc;

#[derive(Parser, Debug)]
#[command(
    name = "phonecontroller-receiver",
    about = "Receives input from the PhoneController Android app and injects it as keyboard/mouse"
)]
struct Args {
    /// TCP control port
    #[arg(long, default_value_t = pc_protocol::DEFAULT_TCP_PORT)]
    port: u16,
    /// UDP input/discovery port (defaults to --port)
    #[arg(long)]
    udp_port: Option<u16>,
    /// Name shown in the phone's discovery list
    #[arg(long)]
    name: Option<String>,
    /// Pairing PIN (digits). Implies --pair mode with a fixed PIN.
    #[arg(long)]
    pin: Option<String>,
    /// Generate a random 6-digit PIN each run (default when no PIN is given)
    #[arg(long)]
    pair: bool,
    /// Run without any pairing PIN (insecure; anyone on the LAN can connect)
    #[arg(long)]
    open: bool,
    /// Input-silence watchdog timeout in ms (releases held inputs)
    #[arg(long, default_value_t = 5000)]
    timeout_ms: u64,
    /// Max concurrent sessions (one phone drives input at a time)
    #[arg(long, default_value_t = 1)]
    max_sessions: usize,
}

/// Create the platform input backend.
#[cfg(target_os = "linux")]
fn create_backend() -> Result<Box<dyn InputBackend>, String> {
    input_linux::LinuxUinputBackend::preflight()
        .map_err(|e| e.to_string())?;
    let backend = input_linux::LinuxUinputBackend::create().map_err(|e| e.to_string())?;
    if let Some(node) = backend.event_node() {
        tracing::info!(node, "virtual input device created");
    }
    Ok(Box::new(backend))
}

#[cfg(windows)]
fn create_backend() -> Result<Box<dyn InputBackend>, String> {
    let backend = input_windows::WindowsSendInputBackend::create().map_err(|e| e.to_string())?;
    Ok(Box::new(backend))
}

#[cfg(not(any(target_os = "linux", windows)))]
fn create_backend() -> Result<Box<dyn InputBackend>, String> {
    Err("no input backend for this platform".into())
}

fn generate_pin() -> String {
    let mut rng = rand::rng();
    format!("{:06}", rng.random_range(0..1_000_000u32))
}

fn main() {
    let args = Args::parse();

    let pin = if let Some(p) = args.pin {
        Some(p)
    } else if args.open {
        None
    } else {
        Some(generate_pin()) // default: pairing PIN generated per run
    };

    let hostname = std::fs::read_to_string("/etc/hostname")
        .map(|s| s.trim().to_owned())
        .unwrap_or_else(|_| "pc".to_owned());
    let cfg = Config {
        tcp_port: args.port,
        udp_port: args.udp_port.unwrap_or(args.port),
        device_name: args.name.unwrap_or(hostname),
        pin: pin.clone(),
        max_sessions: args.max_sessions,
        input_timeout_ms: args.timeout_ms,
        heartbeat_interval_ms: 2000,
        tcp_idle_timeout_ms: 10_000,
    };
    if let Err(e) = cfg.validate() {
        eprintln!("error: {e}");
        std::process::exit(2);
    }
    let cfg = Arc::new(cfg);

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime");

    runtime.block_on(async_main(cfg, pin.as_deref(), args.pair));
}

async fn async_main(cfg: Arc<Config>, printed_pin: Option<&str>, generated: bool) {
    // backend must exist before we accept any connections
    let backend = match create_backend() {
        Ok(b) => b,
        Err(e) => {
            tracing::error!("input backend unavailable: {e}");
            tracing::error!(
                "see docs/linux-setup.md: add yourself to the 'input' group or install the udev rule, then re-login"
            );
            std::process::exit(1);
        }
    };
    let bus = spawn_backend_thread(backend);
    install_panic_hook(bus.clone());

    let handle = match pc_receiver::server::start(cfg.clone(), bus.clone()).await {
        Ok(h) => h,
        Err(e) => {
            tracing::error!("failed to bind sockets: {e}");
            tracing::error!("is another receiver instance already running?");
            std::process::exit(1);
        }
    };

    // user-facing banner (stdout, not tracing)
    println!("PhoneController receiver");
    println!("  TCP control port : {tcp}", tcp = handle.tcp_port);
    println!("  UDP input port   : {udp}", udp = handle.udp_port);
    println!("  Device name      : {}", cfg.device_name);
    match printed_pin {
        Some(pin) => {
            if generated {
                println!("  Pairing PIN      : {} (generated, changes each run)", pin);
            } else {
                println!("  Pairing PIN      : {pin}");
            }
        }
        None => println!("  Pairing PIN      : (none) — OPEN MODE, no authentication!"),
    }
    println!();
    println!("Open the PhoneController app on your phone, pick this PC, enter the PIN.");
    println!("Press Ctrl+C to quit (all held inputs are released).");

    // graceful shutdown
    let ctrl_c = tokio::signal::ctrl_c();
    if ctrl_c.await.is_err() {
        tracing::error!("failed to install Ctrl+C handler");
    }
    tracing::info!("shutting down; releasing all inputs");
    handle.registry.release_everything().await;
    let _ = bus.send(BusMsg::Shutdown).await;
}
