# Windows setup (SendInput)

On Windows the receiver injects events with `SendInput`, using **hardware scan
codes** (`KEYEVENTF_SCANCODE`) rather than virtual-key-only injection. This is
the path most games (Raw Input / DirectInput consumers) accept. No driver,
service or virtual gamepad is installed.

## 1. Build

Requirements:

- Windows 10 or Windows 11 (x64).
- Rust MSVC toolchain:
  ```powershell
  rustup default stable-x86_64-pc-windows-msvc
  ```
- Visual Studio Build Tools with **Desktop development with C++** (MSVC linker
  and Windows SDK). Rust's MSVC toolchain uses `link.exe` from there.

Build:

```powershell
cd pc-receiver
cargo build --release
```

The resulting binary is
`pc-receiver\target\release\phonecontroller-receiver.exe`.

> Cross-compiling from Linux is not supported by this repository's test setup:
> the backend is behind `cfg(windows)` and runtime validation requires a real
> Windows host. Compiling on Windows with the MSVC target is the supported path.

## 2. Run and pair

```powershell
.\target\release\phonecontroller-receiver.exe --pair
```

The receiver prints its TCP/UDP ports and a 6-digit PIN. Allow the binary
through Windows Defender Firewall for **Private** networks when prompted (or
create inbound rules for TCP and UDP port 41000).

Useful flags:

```text
--port <PORT>          TCP control port (default 41000)
--udp-port <PORT>      UDP input/discovery port (defaults to --port)
--pin <PIN>            fixed pairing PIN
--open                 no PIN (insecure; isolated labs only)
--timeout-ms <MS>      input-silence release watchdog (default 5000)
```

If UDP broadcast discovery is blocked by the network, enter the PC's IPv4
address manually in the Android app.

## 3. What is supported

| Input | Method |
|---|---|
| Letter/number/punctuation keys | `SendInput` scan code |
| F1–F12, Esc, Tab, Enter, Backspace, Space | scan code |
| Modifiers (Ctrl/Shift/Alt/Win, left and right) | scan code + `KEYEVENTF_EXTENDEDKEY` where needed |
| Navigation cluster, Insert/Delete, keypad extras | extended scan codes |
| Mouse left/right/middle/back/forward | `MOUSEEVENTF_*` / `XBUTTON1/2` |
| Relative mouse movement | `MOUSEEVENTF_MOVE` with `dx/dy` |
| Vertical/horizontal wheel | `MOUSEEVENTF_WHEEL` / `HWHEEL`, 120 units per tick |

The protocol carries Linux evdev key codes; the Windows backend owns one static
evdev → set-1 scan-code table (`crates/input-windows/src/lib.rs`). Add new keys
there and in `pc-protocol/src/keycodes.rs`, with tests.

## 4. Known limitations (by design of Windows, not bugs)

- **Elevated applications / UIPI.** A non-elevated process cannot inject input
  into an elevated window. If a game runs as administrator, run the receiver as
  administrator too. Do not leave it elevated for normal desktop use.
- **Secure desktop.** Input cannot be injected into UAC prompts, the lock
  screen, or other secure desktops.
- **Anti-cheat systems.** Some anti-cheats flag or block synthetic input.
  Bypassing them is explicitly out of scope; use the app only where permitted.
- **Exclusive/raw-input games.** `SendInput` scan codes work with most raw-input
  consumers, but a few games only accept a physical device. Test the target
  game; there is no driver-level fallback in this project.
- **Remote desktop / some virtual machines.** Injected relative motion may be
  interpreted differently by the remote session.
- **No gamepad emulation.** Xbox/PlayStation controllers are intentionally not
  emulated (project charter).

## 5. Validation checklist

Run this on a real Windows machine before claiming Windows support:

1. `phonecontroller-receiver.exe --pair` starts and prints a PIN.
2. Android app (or simulator) pairs; receiver logs `phone paired`.
3. Notepad receives letters, Shift+letter, Enter, Backspace.
4. Mouse moves smoothly; left/right/middle clicks and wheel work.
5. Hold a key, then disconnect the phone: the key is released within the
   watchdog timeout and does not stick.
6. Reconnect and play again without restarting the receiver.
7. Repeat with the game running elevated (receiver elevated) and note any
   anti-cheat behaviour.

Automated Windows runtime tests are not present because no Windows machine is
available in the development environment; the backend compiles only under
`cfg(windows)` and its scan-code table is unit-tested on the host where
possible.

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| `link.exe not found` | Install VS Build Tools with the C++ workload, then reopen the shell |
| Receiver starts, phone cannot connect | Add inbound firewall rules for TCP/UDP 41000 on Private networks |
| Keys arrive, mouse does not (or vice versa) | Check the receiver log for backend errors; try a non-elevated target app |
| Nothing arrives in one specific game | Test in Notepad; if it works there, the game likely uses raw/exclusive input or anti-cheat |
| Stuck key after force-killing the receiver | The release watchdog cannot run after `TerminateProcess`; log out/in or use the app's Disconnect button |
| Discovery finds no PC | Enter the IP manually; broadcasts are often blocked between VLANs/guest Wi-Fi |
| Works on home Wi-Fi, not when the PC joins the phone's hotspot | The hotspot is usually classified as a **Public** network. Enable the receiver's Public-network rule, or add inbound TCP/UDP 41000 rules for all profiles; see [`HOTSPOT.md`](HOTSPOT.md) |
