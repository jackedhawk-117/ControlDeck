//! Input bus: the single channel from async network tasks into the
//! dedicated backend OS thread. All `Command`s flow through here in order;
//! the thread owns the platform backend.

use input_core::{Command, InputBackend};
use std::sync::OnceLock;
use tokio::sync::mpsc;

/// Messages into the backend thread.
#[derive(Debug)]
pub enum BusMsg {
    /// Apply a batch of commands (from one session's state machine).
    Apply { cmds: Vec<Command> },
    /// Release everything the backend holds (owner switch, shutdown, panic).
    ReleaseAll,
    Shutdown,
}

/// Global handle used by the panic hook (best-effort release).
static BUS_FOR_PANIC: OnceLock<mpsc::Sender<BusMsg>> = OnceLock::new();

/// Install a panic hook that releases all held inputs before the unwinding
/// continues (Engineering Rule 11). Best effort: if the backend thread is
/// already gone, nothing more can be done.
pub fn install_panic_hook(bus: mpsc::Sender<BusMsg>) {
    let _ = BUS_FOR_PANIC.set(bus.clone());
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        if let Some(bus) = BUS_FOR_PANIC.get() {
            // release-all must not be dropped by backpressure, but the
            // channel may already be closed; both outcomes are acceptable.
            let _ = bus.try_send(BusMsg::ReleaseAll);
        }
        default_hook(info);
    }));
}

/// Spawn the OS thread that owns the backend. Returns the bus sender.
pub fn spawn_backend_thread(mut backend: Box<dyn InputBackend>) -> mpsc::Sender<BusMsg> {
    let (tx, mut rx) = mpsc::channel::<BusMsg>(1024);
    let backend_name = backend.name();
    std::thread::Builder::new()
        .name("input-backend".into())
        .spawn(move || {
            tracing::info!(backend = backend_name, "input backend thread started");
            loop {
                match rx.blocking_recv() {
                    Some(BusMsg::Apply { cmds }) => {
                        for cmd in cmds {
                            if let Err(e) = backend.apply(cmd) {
                                tracing::warn!(
                                    target: "input_bus",
                                    "backend apply failed for {cmd:?}: {e}"
                                );
                            }
                        }
                    }
                    Some(BusMsg::ReleaseAll) => {
                        backend.release_all();
                        tracing::info!(target: "input_bus", "backend release_all applied");
                    }
                    Some(BusMsg::Shutdown) | None => break,
                }
            }
            // final safety release on thread exit
            backend.release_all();
            tracing::info!("input backend thread stopped");
        })
        .expect("spawn input backend thread");
    tx
}

/// Send a command batch without blocking the network task: on a full bus the
/// batch is dropped and counted (input deltas are periodic; the next batch
/// or snapshot reconciles state).
pub fn try_apply(bus: &mpsc::Sender<BusMsg>, cmds: Vec<Command>, dropped: &mut u32) {
    if cmds.is_empty() {
        return;
    }
    if let Err(mpsc::error::TrySendError::Full(_)) = bus.try_send(BusMsg::Apply { cmds }) {
        *dropped += 1;
        tracing::debug!(target: "input_bus", "input bus full; dropped delta batch");
    }
}
