//! Raw `/dev/uinput` interface (ioctls + event writes).
//!
//! Hand-rolled against `linux/uinput.h` (verified against the system header)
//! so no binding crate is needed (Engineering Rule 4). Requires write access
//! to `/dev/uinput` — normally granted by the `input` group or a udev rule
//! (see docs/linux-setup.md). Never requires root.

use std::fs::{File, OpenOptions};
use std::io;
use std::os::unix::io::AsRawFd;
use std::time::Duration;

pub const UI_DEVICE_PATH: &str = "/dev/uinput";

// ---------------------------------------------------------------------------
// ioctl helpers (asm-generic _IOC encoding)
// ---------------------------------------------------------------------------

const _IOC_NONE: u64 = 0;
const _IOC_WRITE: u64 = 1;
const _IOC_READ: u64 = 2;

const fn ioc(dir: u64, ty: u8, nr: u8, size: usize) -> u64 {
    (dir << 30) | ((size as u64) << 16) | ((ty as u64) << 8) | (nr as u64)
}

const fn iow<T>(ty: u8, nr: u8) -> u64 {
    ioc(_IOC_WRITE, ty, nr, std::mem::size_of::<T>())
}

const fn io(ty: u8, nr: u8) -> u64 {
    ioc(_IOC_NONE, ty, nr, 0)
}

const fn ior<T>(ty: u8, nr: u8) -> u64 {
    ioc(_IOC_READ, ty, nr, std::mem::size_of::<T>())
}

const UINPUT: u8 = b'U';
const EVIOCGNAME_GROUP: u8 = b'E';

pub const UI_DEV_CREATE: u64 = io(UINPUT, 1);
pub const UI_DEV_DESTROY: u64 = io(UINPUT, 2);
pub const UI_DEV_SETUP: u64 = iow::<UinputSetup>(UINPUT, 3);
pub const UI_SET_EVBIT: u64 = iow::<i32>(UINPUT, 100);
pub const UI_SET_KEYBIT: u64 = iow::<i32>(UINPUT, 101);
pub const UI_SET_RELBIT: u64 = iow::<i32>(UINPUT, 102);
pub const UI_GET_SYSNAME_LEN: u64 = ior::<u8>(UINPUT, 44); // size set per-call
/// EVIOCGNAME(len) — read device name from an evdev node.
pub fn eviocgname(len: usize) -> u64 {
    ioc(_IOC_READ, EVIOCGNAME_GROUP, 0x06, len)
}

// ---------------------------------------------------------------------------
// Event constants (input-event-codes.h)
// ---------------------------------------------------------------------------

pub const EV_SYN: u16 = 0x00;
pub const EV_KEY: u16 = 0x01;
pub const EV_REL: u16 = 0x02;

pub const REL_X: u16 = 0x00;
pub const REL_Y: u16 = 0x01;
pub const REL_WHEEL: u16 = 0x08;
pub const REL_HWHEEL: u16 = 0x09;

pub const SYN_REPORT: u16 = 0x00;

/// `struct uinput_setup` (linux/uinput.h).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct UinputSetup {
    pub id: InputId,
    pub name: [u8; 80],
    pub ff_effects_max: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct InputId {
    pub bustype: u16,
    pub vendor: u16,
    pub product: u16,
    pub version: u16,
}

/// `struct input_event` on 64-bit Linux (timeval is two i64).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct InputEvent {
    pub tv_sec: i64,
    pub tv_usec: i64,
    pub type_: u16,
    pub code: u16,
    pub value: i32,
}

pub const INPUT_EVENT_SIZE: usize = std::mem::size_of::<InputEvent>();
const _: () = assert!(INPUT_EVENT_SIZE == 24);

// ---------------------------------------------------------------------------
// Device
// ---------------------------------------------------------------------------

/// A created uinput virtual device (keyboard + relative mouse + wheels).
pub struct UinputDevice {
    file: File,
    name: String,
    /// evdev node (e.g. `/dev/input/event12`) if it could be located.
    event_node: Option<String>,
    held_keys: Vec<bool>,
    held_buttons: [bool; 5],
}

/// Key bits registered on the virtual device: 1..=248 covers every gaming
/// relevant key; mouse buttons registered separately.
const REGISTERED_KEY_MAX: u16 = 248;

impl UinputDevice {
    /// Open `/dev/uinput` and create the virtual device.
    pub fn create(name: &str) -> io::Result<Self> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(UI_DEVICE_PATH)
            .map_err(|e| io::Error::new(
                e.kind(),
                format!(
                    "cannot open {UI_DEVICE_PATH} ({e}); add yourself to the 'input' group or \
                     install the udev rule from docs/linux-setup.md, then re-login"
                ),
            ))?;
        let fd = file.as_raw_fd();

        // event type bits
        for bit in [EV_SYN, EV_KEY, EV_REL] {
            set_bit(fd, UI_SET_EVBIT, bit as i32)?;
        }
        // relative axes
        for bit in [REL_X, REL_Y, REL_WHEEL, REL_HWHEEL] {
            set_bit(fd, UI_SET_RELBIT, bit as i32)?;
        }
        // keys 1..=248 plus mouse buttons
        for code in 1..=REGISTERED_KEY_MAX {
            set_bit(fd, UI_SET_KEYBIT, code as i32)?;
        }
        for code in [0x110, 0x111, 0x112, 0x113, 0x114] {
            set_bit(fd, UI_SET_KEYBIT, code)?;
        }

        // device identity
        let mut dev_name = [0u8; 80];
        let bytes = name.as_bytes();
        let n = bytes.len().min(79);
        dev_name[..n].copy_from_slice(&bytes[..n]);
        let setup = UinputSetup {
            id: InputId {
                bustype: 0x03, // BUS_USB
                vendor: 0x1B9A,
                product: 0x5043, // "PC"
                version: 1,
            },
            name: dev_name,
            ff_effects_max: 0,
        };
        unsafe {
            let rc = libc::ioctl(fd, UI_DEV_SETUP, &setup as *const UinputSetup);
            if rc < 0 {
                return Err(io::Error::last_os_error());
            }
            let rc = libc::ioctl(fd, UI_DEV_CREATE);
            if rc < 0 {
                return Err(io::Error::last_os_error());
            }
        }

        // UI_DEV_CREATE is asynchronous with respect to udev/evdev node
        // publication. Poll briefly so callers (and tests) reliably get the
        // node instead of losing a race on a busy machine.
        let mut event_node = None;
        for _ in 0..100 {
            event_node = find_event_node_by_name(name);
            if event_node.is_some() {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        Ok(Self {
            file,
            name: name.to_owned(),
            event_node,
            held_keys: vec![false; (REGISTERED_KEY_MAX + 1) as usize],
            held_buttons: [false; 5],
        })
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    /// Located evdev node, for diagnostics/tests (`/dev/input/eventN`).
    pub fn event_node(&self) -> Option<&str> {
        self.event_node.as_deref()
    }

    /// Write one event followed by a SYN_REPORT.
    pub fn emit(&mut self, type_: u16, code: u16, value: i32) -> io::Result<()> {
        let now = now_timeval();
        let ev = InputEvent {
            tv_sec: now.0,
            tv_usec: now.1,
            type_,
            code,
            value,
        };
        let bytes = unsafe {
            std::slice::from_raw_parts(
                (&ev as *const InputEvent) as *const u8,
                INPUT_EVENT_SIZE,
            )
        };
        write_all(self.file.as_raw_fd(), bytes)?;
        // SYN_REPORT terminates the batch
        let syn = InputEvent {
            tv_sec: now.0,
            tv_usec: now.1,
            type_: EV_SYN,
            code: SYN_REPORT,
            value: 0,
        };
        let syn_bytes = unsafe {
            std::slice::from_raw_parts((&syn as *const InputEvent) as *const u8, INPUT_EVENT_SIZE)
        };
        write_all(self.file.as_raw_fd(), syn_bytes)
    }

    /// Internal bookkeeping so `release_all` can clear the OS state even if
    /// the caller's state machine ever diverges.
    pub fn track_key(&mut self, code: u16, down: bool) {
        if (code as usize) < self.held_keys.len() {
            self.held_keys[code as usize] = down;
        }
    }

    pub fn track_button(&mut self, evdev_code: u16, down: bool) {
        let idx = match evdev_code {
            0x110 => 0,
            0x111 => 1,
            0x112 => 2,
            0x113 => 3,
            0x114 => 4,
            _ => return,
        };
        self.held_buttons[idx] = down;
    }

    pub fn held_key_count(&self) -> usize {
        self.held_keys.iter().filter(|&&h| h).count()
    }

    pub fn held_button_count(&self) -> usize {
        self.held_buttons.iter().filter(|&&h| h).count()
    }

    /// Release everything currently tracked as held.
    pub fn release_all(&mut self) -> io::Result<()> {
        for code in 1..=REGISTERED_KEY_MAX {
            if self.held_keys[code as usize] {
                self.held_keys[code as usize] = false;
                self.emit(EV_KEY, code, 0)?;
            }
        }
        let buttons = [0x110u16, 0x111, 0x112, 0x113, 0x114];
        for (i, &code) in buttons.iter().enumerate() {
            if self.held_buttons[i] {
                self.held_buttons[i] = false;
                self.emit(EV_KEY, code, 0)?;
            }
        }
        Ok(())
    }
}

impl Drop for UinputDevice {
    fn drop(&mut self) {
        // Best-effort release before destroy (Rule 11: never leave stuck inputs).
        let _ = self.release_all();
        unsafe {
            let _ = libc::ioctl(self.file.as_raw_fd(), UI_DEV_DESTROY);
        }
        // file close removes the device
    }
}

fn set_bit(fd: i32, req: u64, bit: i32) -> io::Result<()> {
    unsafe {
        if libc::ioctl(fd, req, bit) < 0 {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}

fn write_all(fd: i32, mut buf: &[u8]) -> io::Result<()> {
    while !buf.is_empty() {
        let n = unsafe { libc::write(fd, buf.as_ptr() as *const libc::c_void, buf.len()) };
        if n < 0 {
            let e = io::Error::last_os_error();
            if e.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(e);
        }
        buf = &buf[n as usize..];
    }
    Ok(())
}

fn now_timeval() -> (i64, i64) {
    // CLOCK_MONOTONIC is fine for input timestamps
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    unsafe {
        libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts);
    }
    (ts.tv_sec, ts.tv_nsec / 1000)
}

/// Locate the evdev node of a device by its exact name by scanning
/// `/dev/input/event*` with EVIOCGNAME. Returns `None` if not found
/// (e.g. missing read permission) — callers must treat this as cosmetic.
pub fn find_event_node_by_name(name: &str) -> Option<String> {
    let dir = std::fs::read_dir("/dev/input").ok()?;
    let mut candidates: Vec<String> = dir
        .flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.starts_with("event") && n[5..].chars().all(|c| c.is_ascii_digit()))
        .collect();
    candidates.sort_by_key(|n| n[5..].parse::<u32>().unwrap_or(0));

    let mut namebuf = [0u8; 128];
    for node in candidates {
        let path = format!("/dev/input/{node}");
        let Ok(f) = std::fs::File::open(&path) else {
            continue;
        };
        let req = eviocgname(namebuf.len());
        let n = unsafe { libc::ioctl(f.as_raw_fd(), req, namebuf.as_mut_ptr()) };
        if n <= 0 {
            continue;
        }
        let len = (n as usize).min(namebuf.len() - 1);
        // EVIOCGNAME includes the trailing NUL; strip it before comparing
        let raw = &namebuf[..len];
        let trimmed = raw.strip_suffix(&[0u8]).unwrap_or(raw);
        let devname = std::str::from_utf8(trimmed).unwrap_or("");
        if devname == name {
            return Some(path);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `/dev/uinput` availability — skips gracefully in restricted CI.
    fn uinput_available() -> bool {
        match OpenOptions::new().read(true).write(true).open(UI_DEVICE_PATH) {
            Ok(_) => true,
            Err(e) => {
                eprintln!("skipping uinput test: {e}");
                false
            }
        }
    }

    #[test]
    fn create_destroy_device() {
        if !uinput_available() {
            return;
        }
        let name = "pc-test-destroy";
        let dev = UinputDevice::create(name).expect("device creation should work with uinput access");
        assert_eq!(dev.name(), name);
        drop(dev); // must not panic; destroys cleanly
    }

    #[test]
    fn create_then_find_event_node() {
        if !uinput_available() {
            return;
        }
        let suffix = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .subsec_millis();
        let name = format!("pc-test-node-{suffix}");
        let dev = UinputDevice::create(&name).unwrap();
        let node = dev.event_node().map(|s| s.to_owned()).expect("event node should be findable");
        assert!(node.starts_with("/dev/input/event"));
        drop(dev);
    }

    #[test]
    fn tracking_and_release() {
        if !uinput_available() {
            return;
        }
        let mut dev = UinputDevice::create("pc-test-track").unwrap();
        assert_eq!(dev.held_key_count(), 0);
        dev.track_key(30, true); // KEY_A
        dev.track_key(57, true); // KEY_SPACE
        assert_eq!(dev.held_key_count(), 2);
        dev.track_key(30, false);
        assert_eq!(dev.held_key_count(), 1);
        dev.release_all().unwrap();
        assert_eq!(dev.held_key_count(), 0);
    }
}
