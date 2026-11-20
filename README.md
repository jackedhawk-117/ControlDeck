# PhoneController

Turn an Android phone into a programmable wireless **keyboard + mouse** for PC
games — touchscreen grid, touchpad camera control, and console-style gyroscope
aiming. Works with Windows games, native Linux games, and Steam/Proton titles.
**No gamepad emulation.**

- **Android app** — Kotlin + Jetpack Compose (Material 3): discovery + PIN
  pairing, gameplay grid, diagnostics.
- **PC receiver** — Rust + tokio: UDP input / TCP control, PIN pairing +
  AES-256-GCM session crypto, injects events via **uinput** (Linux) or
  **SendInput** (Windows).

## Status

Milestones 1–6 are implemented. Milestones 1–4 are device-validated; the
Milestone 5 macro/UI path is JVM-tested and awaits on-device validation, and the
Milestone 6 gyro path has live sensor/yaw-sign evidence but not yet the full
physical acceptance sequence. See [`docs/PLAN.md`](docs/PLAN.md) for the living
checklist.

Working today:

- UDP broadcast discovery + manual IP connection, including **phone-hotspot
  mode**: the app probes every local IPv4 interface with its directed
  broadcast, so the PC is found even when the phone's default route is mobile
  data. Results show the interface used (e.g. `cachyos — 192.168.43.42 · Phone
  hotspot`).
- PIN pairing, HKDF-SHA256 session key, AES-256-GCM for every control/input
  packet, replay protection, heartbeat, watchdog, full input release on
  disconnect.
- Touchpad camera region: `TouchpadMapper` (velocity → Q16.16 mouse deltas,
  smoothing, acceleration, sensitivity X/Y, axis inversion, tap-to-click),
  120 Hz `MotionAccumulator` drain loop, adjustable width fraction, settings
  panel with live-update sliders persisted per profile.
- **Gyro aiming** with console-style hybrid control, designed for a phone held
  approximately flat: FUSED (drift-free rotation-vector) or optional raw-gyro
  source, gravity-aligned world-frame yaw/pitch from the phone's physical short
  axis (roll about that axis ignored; a resting grip tilt does not leak yaw into
  pitch; pitch flips for reverse landscape via the live display rotation),
  per-axis sensitivity, deadzone, motion-preserving smoothing,
  inversion, per-tick spike clamp, ADS/RMB multiplier, runtime bias calibration
  with persisted per-profile bias, dedicated recenter cell, and automatic
  re-prime on activation. Activation modes: Always On (default), ADS Only,
  Hold, Toggle, and an on-canvas touch region observed without consuming
  touches. ADS Only follows the reference-counted logical right button from any
  source and always re-primes on entry, so releasing one RMB source cannot
  disarm another and re-entry cannot replay a pose jump. Gyro and touchpad
  deltas sum into the same mouse accumulator, so fine aim and large camera
  swipes work simultaneously; the ADS multiplier scales gyro only.
- **Gyro tuning screen** with live sensor rates/availability, raw/filtered/output
  telemetry, activation/source chips, all response sliders, region controls,
  calibration status and recenter/reset actions; diagnostics shows
  armed/aiming/ADS-not-held state, activation mode, logical ADS state, sensor
  frequencies, live yaw/pitch rate and output.
  GYRO cells are editable in the cell editor and addable from the grid editor;
  the bundled FPS profile includes GYRO + CENTER cells and defaults to Always On.
- Freeform customizable touchscreen canvas: normalized `xPct`/`yPct`
  positioning, independent cell assignment (keys, arbitrary key combos up to
  four keys, mouse buttons, toggles), custom labels, shapes, dimensions,
  opacity, tint colors, and virtual WASD joystick. The full screen is the
  layout — there is no fixed row/column grid.
- Per-joystick **sprint toggle**: the editor can disable the full-deflection
  Shift chord for any joystick. The setting is stored as `joystickSprint` in the
  profile JSON (legacy profiles default to enabled) and suppresses the Shift
  key entirely when off.
- **Phone-side macros**: arbitrary tap/hold/release/wait sequences with chords,
  per-step hold and delay timing, and up to ten repeats. A dedicated editor
  provides step reordering, key selection and live duration checks. Macros run
  through the same `InputTracker` as touch controls, so reference counts,
  snapshots, release-all and the ordered UDP FIFO all still apply. Tapping the
  macro button again cancels it immediately; steps, repeats and total runtime
  are hard-capped (24 steps, ×10, 15 s).
- **Diagnostics HUD** in gameplay: connection state, RTT, receiver input rate,
  cumulative/packet-drop rate, derived connection quality, currently held keys
  and mouse buttons, and running macros.
- Dedicated layout-editing mode (`GridEditorScreen`) vs. gameplay mode (`PlayScreen`),
  with cumulative drag positioning, in-session undo stack and reset-to-defaults.
- Portable, versioned JSON profile format (`ProfileJson.kt`, schema v1) with full
  in-app Profile Manager (create, duplicate, import/export via clipboard, delete).
  Imports are clamped and ID-deduplicated; writes are atomic with a corrupt-file
  backup.
- Bundled genre presets: First-Person Shooter (WASD), Third-Person Action,
  Driving & Racing, Strategy / RTS, and General WASD + Mouse.
- Seamless profile switching during live sessions with receiver-side `RESET` and
  `PROFILE_INFO` synchronization, plus release-all before editor/settings
  transitions.
- Landscape + immersive mode, 1 Hz state snapshots, ordered UDP press/release
  enqueueing.
- Validated end-to-end on a motorola edge 70 fusion (Android 17): discovery,
  PIN pairing, UDP input through Linux uinput, 6–14 ms control-plane RTT, mouse
  left/right/middle, all joystick axes + sprint, touchpad relative motion,
  editor drag/undo/cancel, and immediate all-input release on
  disconnect/background or editor entry.
- Linux uinput backend (validated), Windows SendInput backend (compiled,
  runtime validation pending a Windows machine).
- Kotlin↔Rust compatibility tests pinned to the Rust implementation, including
  a real Kotlin-client ↔ real-Rust-receiver E2E test. Current suites: 153 Android
  JVM tests and 56 Rust tests, clippy clean.

Not yet validated on hardware: the full flat-phone gyro acceptance sequence
(far-edge up/down pitch, combined motion, roll isolation, recenter-at-pose,
normal and reverse landscape), Always On and ADS Only activation gating,
gyro drift/in-game feel, on-device macro execution, Windows runtime, and long
sessions. A live left/right yaw pass has been captured end to end through the
receiver; later pitch captures recorded zero motion because the phone was
stationary. The design and implementation reference is
[`docs/GYRO-AIMING.md`](docs/GYRO-AIMING.md); the milestone report is
[`docs/MILESTONE-6.md`](docs/MILESTONE-6.md).

## Quick start (Linux receiver)

```bash
# 1. Build
cd pc-receiver && cargo build --release

# 2. Allow uinput without root (once)
sudo usermod -aG input "$USER"       # then log out/in
#    …and/or install the udev rule from docs/linux-setup.md

# 3. Run the receiver (prints a random PIN)
./target/release/phonecontroller-receiver --pair

#    or use a fixed PIN / different ports:
./target/release/phonecontroller-receiver --pin 123456 --port 41000
```

The receiver prints its ports and PIN:

```text
INFO server listening tcp_port=41000 udp_port=41000 name=... pin_required=true max_sessions=1
PhoneController receiver
  TCP control port : 41000
  UDP input port   : 41000
  Device name      : ...
  Pairing PIN      : 246810
```

Then either use the Android app or the simulator.

## Build and install the Android app

Requirements:

- JDK 17 or newer.
- Android SDK with **platform 36** (`compileSdk = 36`, `minSdk = 26`). Set
  `sdk.dir` in `android-app/local.properties` or `ANDROID_HOME`.
- The Gradle wrapper downloads Gradle 9.8.2 automatically.

```bash
cd android-app
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app opens on the connection screen. Tap **Scan local network**, choose the
PC, enter the receiver's PIN, then tap **Pair with PC**. Gameplay is landscape;
use the Android back gesture/Disconnect button to return (all inputs are
released).

> The Compose BOM is pinned to `2026.06.01` because newer BOMs require
> `compileSdk 37`; this environment has SDK 36. Bump both together when SDK 37
> is installed.

## Test without a phone

Simulator (virtual phone, same wire protocol):

```bash
cd pc-receiver
./target/release/phonecontroller-receiver --pin 123456 &
./target/release/phonecontroller-simulator --host 127.0.0.1 --pin 123456 --demo-ms 5000
# watch the virtual keyboard/mouse:
#   libinput debug-events   (or evtest /dev/input/eventN)
```

Rust workspace:

```bash
cd pc-receiver
cargo test --workspace --no-fail-fast
cargo clippy --workspace --all-targets -- -D warnings
```

Android JVM tests (no device required):

```bash
cd android-app
./gradlew testDebugUnitTest

# Cross-language E2E against the real receiver (auto-skips if unavailable):
cd ../pc-receiver && cargo build --release
cd ../android-app && ./gradlew testDebugUnitTest --tests '*RealReceiverE2ETest*'
```

The E2E test needs `/dev/uinput` write access; it is skipped automatically when
the receiver binary or uinput is unavailable.

## Documentation

| Doc | Contents |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | system design, decisions, risks, concurrency |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | wire format v1 (normative) |
| [`docs/PLAN.md`](docs/PLAN.md) | milestone checklist and current status |
| [`docs/MILESTONE-1.md`](docs/MILESTONE-1.md) | M1 report: architecture, protocol, backends, tests |
| [`docs/MILESTONE-2.md`](docs/MILESTONE-2.md) | M2 report: communication, Android client, E2E results |
| [`docs/MILESTONE-3.md`](docs/MILESTONE-3.md) | M3 report: touchpad camera, all mouse buttons, key combos, toggles, unit tests |
| [`docs/MILESTONE-4.md`](docs/MILESTONE-4.md) | M4 report: customizable grid editor, profiles JSON, presets, receiver RESET |
| [`docs/MILESTONE-5.md`](docs/MILESTONE-5.md) | M5 report: macros, macro editor, safety limits, diagnostics HUD |
| [`docs/MILESTONE-6.md`](docs/MILESTONE-6.md) | M6 report: gyro sensors, mapping, calibration, activation modes, tests |
| [`docs/linux-setup.md`](docs/linux-setup.md) | uinput permissions, Wayland/X11, Proton notes |
| [`docs/windows-setup.md`](docs/windows-setup.md) | Windows build, SendInput limits, validation checklist |
| [`docs/HOTSPOT.md`](docs/HOTSPOT.md) | PC on the phone's Wi-Fi hotspot: setup, discovery routing, firewall, troubleshooting |
| [`docs/GYRO-AIMING.md`](docs/GYRO-AIMING.md) | gyro → mouse implementation reference (math, modes, calibration, tests) |

Security note: the current PIN scheme derives the session key from the PIN and
both handshake nonces. A recorded handshake allows an offline PIN guess
(6 digits). This is acceptable for the documented LAN threat model but SPAKE2
is the planned upgrade. Do not expose the receiver's ports to the internet.

## Layout

```text
pc-receiver/            Rust workspace
  crates/pc-protocol    wire format, framing, crypto (normative, tested)
  crates/input-core     platform-free input state machine + InputBackend trait
  crates/input-linux    uinput backend (no root needed)
  crates/input-windows  SendInput backend (windows-only build)
  crates/pc-receiver    receiver binary + simulator binary

android-app/            Kotlin/Compose app (Gradle)
  app/src/main/java     protocol mirror, session crypto, UI
  app/src/test/java     Rust-pinned vectors, codecs, fake-server pairing, real-receiver E2E

docs/                   protocol, setup guides, gyro design, milestone checklist
```
