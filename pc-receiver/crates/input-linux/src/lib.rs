//! Linux input backend: translates `Command`s into real uinput events.
//!
//! The device appears as a regular keyboard/mouse to Wayland, X11, SDL and
//! raw evdev consumers alike — no compositor-specific code is needed.

pub mod uinput;

pub use uinput::{UinputDevice, UI_DEVICE_PATH};

use input_core::{BackendError, Command, InputBackend, MouseButton};
use pc_protocol::keycodes;
use std::io;

/// Backend name advertised to the OS.
pub const DEVICE_NAME: &str = "PhoneController Virtual Input";

pub struct LinuxUinputBackend {
    dev: UinputDevice,
    /// uinput write error streak (circuit breaker for logging).
    write_errors: u32,
}

impl LinuxUinputBackend {
    /// Create the virtual device. Fails with actionable errors if
    /// `/dev/uinput` is unavailable or permission-denied.
    pub fn create() -> Result<Self, BackendError> {
        let dev = UinputDevice::create(DEVICE_NAME).map_err(io_to_backend)?;
        Ok(Self {
            dev,
            write_errors: 0,
        })
    }

    /// Location of the created evdev node, when discoverable (diagnostics).
    pub fn event_node(&self) -> Option<&str> {
        self.dev.event_node()
    }

    /// Verify `/dev/uinput` is usable without creating a device.
    pub fn preflight() -> Result<(), BackendError> {
        match std::fs::OpenOptions::new().read(true).write(true).open(UI_DEVICE_PATH) {
            Ok(_) => Ok(()),
            Err(e) => Err(io_to_backend(e)),
        }
    }
}

fn io_to_backend(e: io::Error) -> BackendError {
    BackendError::Unavailable(e.to_string())
}

impl InputBackend for LinuxUinputBackend {
    fn apply(&mut self, cmd: Command) -> Result<(), BackendError> {
        let res = self.apply_inner(cmd);
        if res.is_err() {
            self.write_errors += 1;
        } else {
            self.write_errors = 0;
        }
        res
    }

    fn release_all(&mut self) {
        // Release what the backend itself tracked, independent of any
        // upstream state machine divergence (Rule 11 defense in depth).
        if let Err(e) = self.dev.release_all() {
            tracing::error!(target: "input_linux", "release_all failed: {e}");
        }
    }

    fn name(&self) -> &'static str {
        "linux-uinput"
    }

    fn held_summary(&self) -> Vec<String> {
        let mut v = Vec::new();
        if self.dev.held_key_count() > 0 {
            v.push(format!("{} keys held", self.dev.held_key_count()));
        }
        if self.dev.held_button_count() > 0 {
            v.push(format!("{} mouse buttons held", self.dev.held_button_count()));
        }
        v
    }
}

impl LinuxUinputBackend {
    fn apply_inner(&mut self, cmd: Command) -> Result<(), BackendError> {
        let out: Result<(), io::Error> = match cmd {
            Command::KeyDown(code) => {
                self.dev.track_key(code, true);
                self.dev.emit(uinput::EV_KEY, code, 1)
            }
            Command::KeyUp(code) => {
                self.dev.track_key(code, false);
                self.dev.emit(uinput::EV_KEY, code, 0)
            }
            Command::MouseDown(b) => {
                let code = button_evdev(b);
                self.dev.track_button(code, true);
                self.dev.emit(uinput::EV_KEY, code, 1)
            }
            Command::MouseUp(b) => {
                let code = button_evdev(b);
                self.dev.track_button(code, false);
                self.dev.emit(uinput::EV_KEY, code, 0)
            }
            Command::MouseRel { dx, dy } => {
                if dx != 0 {
                    self.dev
                        .emit(uinput::EV_REL, uinput::REL_X, dx)
                        .map_err(io_to_backend)?;
                }
                if dy != 0 {
                    self.dev
                        .emit(uinput::EV_REL, uinput::REL_Y, dy)
                        .map_err(io_to_backend)?;
                }
                Ok(())
            }
            Command::Scroll { horizontal, ticks } => {
                let code = if horizontal { uinput::REL_HWHEEL } else { uinput::REL_WHEEL };
                // positive = wheel up (vertical) / right (horizontal)
                self.dev.emit(uinput::EV_REL, code, ticks).map_err(io_to_backend)?;
                Ok(())
            }
        };
        out.map_err(io_to_backend)
    }
}

fn button_evdev(b: MouseButton) -> u16 {
    match b {
        MouseButton::Left => keycodes::BTN_LEFT,
        MouseButton::Right => keycodes::BTN_RIGHT,
        MouseButton::Middle => keycodes::BTN_MIDDLE,
        MouseButton::Back => keycodes::BTN_SIDE,
        MouseButton::Forward => keycodes::BTN_EXTRA,
    }
}

#[cfg(test)]
mod readback {
    //! Readback harness: create a device, emit events, then read them back
    //! from the created evdev node to prove events reach the kernel.

    use super::uinput::{eviocgname, InputEvent, INPUT_EVENT_SIZE};
    use std::fs::{File, OpenOptions};
    use std::io::{self, Read};
    use std::os::unix::fs::OpenOptionsExt;
    use std::os::fd::{AsFd, AsRawFd};
    use std::time::Duration;

    /// Open an evdev node for reading (O_NONBLOCK).
    ///
    /// Must be attached BEFORE the events of interest are emitted:
    /// evdev only delivers events to clients that are connected when the
    /// events occur, and the node itself disappears the moment the uinput
    /// device is destroyed.
    pub fn open_reader(path: &str) -> io::Result<File> {
        OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NONBLOCK)
            .open(path)
    }

    /// Read up to `count` input_events from an already-opened reader.
    pub fn read_events(f: &mut File, count: usize, timeout: Duration) -> io::Result<Vec<InputEvent>> {
        let deadline = std::time::Instant::now() + timeout;
        let mut out = Vec::with_capacity(count);
        while out.len() < count {
            if std::time::Instant::now() > deadline {
                return Err(io::Error::new(io::ErrorKind::TimedOut, "no events arrived"));
            }
            let mut buf = [0u8; INPUT_EVENT_SIZE * 32];
            match f.read(&mut buf) {
                Ok(0) => std::thread::sleep(Duration::from_millis(5)),
                Ok(n) => {
                    for chunk in buf[..n].chunks_exact(INPUT_EVENT_SIZE) {
                        unsafe {
                            out.push(std::ptr::read_unaligned(chunk.as_ptr() as *const InputEvent));
                        }
                    }
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(5));
                }
                Err(e) => return Err(e),
            }
        }
        Ok(out)
    }

    /// Read a device name from an evdev node (used to sanity-check mapping).
    pub fn read_device_name(path: &str) -> Option<String> {
        let f = File::open(path).ok()?;
        let mut buf = [0u8; 128];
        let n = unsafe { libc::ioctl(f.as_fd().as_raw_fd(), eviocgname(buf.len()), buf.as_mut_ptr()) };
        if n <= 0 {
            return None;
        }
        let raw = &buf[..n as usize];
        let trimmed = raw.strip_suffix(&[0u8]).unwrap_or(raw);
        Some(std::str::from_utf8(trimmed).ok()?.to_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::readback::{open_reader, read_device_name, read_events};
    use super::*;
    use std::time::Duration;

    fn uinput_available() -> bool {
        match LinuxUinputBackend::preflight() {
            Ok(()) => true,
            Err(e) => {
                eprintln!("skipping uinput backend test: {e}");
                false
            }
        }
    }

    #[test]
    fn backend_emits_key_events_visible_on_evdev_node() {
        if !uinput_available() {
            return;
        }
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .subsec_millis();
        // unique per-run name so we never read a sibling test's device
        let name = format!("{DEVICE_NAME} rb{stamp}");
        let mut dev = UinputDevice::create(&name).unwrap();
        let node = dev.event_node().map(str::to_owned).expect("node located");

        // name must match what we created
        assert_eq!(read_device_name(&node).as_deref(), Some(name.as_str()));

        // attach the reader BEFORE emitting: evdev only delivers events to
        // clients connected when they occur, and the node dies with the device.
        let mut reader = open_reader(&node).expect("open evdev node");

        // KEY_A down/up
        dev.emit(uinput::EV_KEY, 30, 1).unwrap();
        dev.emit(uinput::EV_KEY, 30, 0).unwrap();

        let events = read_events(&mut reader, 4, Duration::from_secs(3)).expect("read back events");
        // down, syn, up, syn
        assert_eq!(events[0].type_, uinput::EV_KEY);
        assert_eq!(events[0].code, 30);
        assert_eq!(events[0].value, 1);
        assert_eq!(events[2].type_, uinput::EV_KEY);
        assert_eq!(events[2].code, 30);
        assert_eq!(events[2].value, 0);

        drop(reader);
        drop(dev);
    }

    #[test]
    fn backend_release_all_writes_release_events() {
        if !uinput_available() {
            return;
        }
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .subsec_millis();
        let name = format!("{DEVICE_NAME} rel{stamp}");
        let dev = UinputDevice::create(&name).unwrap();
        let node = dev.event_node().map(str::to_owned).unwrap();

        // attach the reader first so events emitted by `apply`/`release_all`
        // are queued for this client.
        let mut reader = open_reader(&node).unwrap();

        let mut backend = LinuxUinputBackend { dev, write_errors: 0 };
        let _ = backend.apply(Command::KeyDown(keycodes::KEY_W));
        let _ = backend.apply(Command::MouseDown(MouseButton::Left));
        // simulate a "disconnect": backend must clear everything it holds
        backend.release_all();

        // expect: W down, syn, LMB down, syn, W up, syn, LMB up, syn
        let events = read_events(&mut reader, 8, Duration::from_secs(3)).expect("read back events");
        assert_eq!(events[0].code, keycodes::KEY_W);
        assert_eq!(events[0].value, 1);
        assert_eq!(events[2].code, keycodes::BTN_LEFT);
        assert_eq!(events[2].value, 1);
        assert_eq!(events[4].code, keycodes::KEY_W);
        assert_eq!(events[4].value, 0);
        assert_eq!(events[6].code, keycodes::BTN_LEFT);
        assert_eq!(events[6].value, 0);

        drop(backend);
        drop(reader);
    }
}
