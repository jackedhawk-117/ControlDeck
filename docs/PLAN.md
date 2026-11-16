# Implementation Checklist

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[b]` blocked (reason noted)

This file is the living implementation checklist (Engineering Rule 12). Update
status after every milestone or significant change.

Current position: **Milestones 1–4 complete and hardened**, **Milestone 5 in
progress** (joystick and toggle/hold done; macros and richer diagnostics
remain). The APK has been installed and validated on a motorola edge 70 fusion
(Android 17): discovery, PIN pairing, UDP keyboard/mouse input, touchpad camera,
customizable freeform canvas editor, and per-profile switching with receiver
RESET synchronization.

A second device session on 2026-11-16 re-verified fresh PIN pairing, Linux
uinput delivery of mouse left/right/middle, all four joystick axes plus the
sprint chord, touchpad relative motion, and release-all when entering the
editor. It also exposed an editor drag defect: only the first pointer delta
applied because the updated cell was moved to the end of the list, changing its
Composition slot and disposing the drag coroutine. `withCellUpdated()` now
preserves list order and regression tests cover repeated drag updates. Editor
drag/undo/cancel, Save & Exit + cold-restart persistence, and the new
per-joystick sprint switch (Shift suppressed when off) are verified on device.

Hotspot-aware discovery was added: the app now probes every local IPv4
interface and sends its directed broadcast, so a PC joined to the phone's
Wi-Fi hotspot is detected even when mobile data is the default route. The user
confirmed on-device detection over the phone hotspot. Profile switching,
touchpad-settings persistence, multitouch chording, Windows runtime, and
long-session testing remain (Milestone 7).

---

## Milestone 1 — Architecture and repository setup

- [x] Inspect existing repository (initially only `AGENTS.md`) and dev environment
- [x] Environment inventory:
  - CachyOS (Arch), Wayland session (XWayland present)
  - Rust 1.92.0, cargo 1.92.0
  - Android SDK: platforms 34/35/36, build-tools 34/35, NDK 25/25.2/28.2
  - JDK 17 + Gradle 9.8.2; crates.io + Google Maven reachable
  - `/dev/uinput` present; user in `input` group → uinput testable **without root**
- [x] Document architecture → `ARCHITECTURE.md`
- [x] Define protocol schemas (v1) → `docs/PROTOCOL.md`
- [x] Define Rust input abstraction → `pc-receiver/crates/input-core`
- [x] Establish directory structure (Rust workspace + Android Gradle project)
- [x] Build instructions → `README.md`, `docs/linux-setup.md`, `docs/windows-setup.md`
- [x] `pc-protocol` crate implemented (framing, handshake, input payloads, crypto) + unit tests
- [x] `input-core` state machine implemented + unit tests
- [x] `input-linux` uinput backend implemented + readback integration tests
- [x] `input-windows` SendInput backend implemented (compile-gated; runtime test blocked)
- [x] Smallest end-to-end prototype identified → `ARCHITECTURE.md` §6

**Acceptance met:** `cargo test --workspace --no-fail-fast` → 56 tests pass;
`cargo clippy --workspace --all-targets` → 0 warnings.

## Milestone 2 — Basic communication

- [x] Rust receiver binary: TCP control channel + UDP input/discovery channel
- [x] UDP broadcast discovery (DISCOVER/ANNOUNCE)
- [x] Multi-interface discovery: one UDP socket per local IPv4 interface plus
      directed broadcast, so the PC is found when joined to the phone's Wi-Fi
      hotspot (on-device detection confirmed); scan UI shows probed interfaces
- [x] PIN-based pairing handshake (HKDF-SHA256 + HMAC proof + AES-256-GCM session crypto)
- [x] Session registry, heartbeat (PING/PONG), BYE, full input release on disconnect
- [x] Safety watchdog (input silence → release all; default 5 s)
- [x] STATS channel (latency probe echo, drop counters) for phone diagnostics
- [x] Android app shell: Material 3 dark theme, Home/connection screen
- [x] Android network stack: discovery, TCP control handshake, UDP input channel (Kotlin mirror)
- [x] Android gameplay screen: fixed 2×4 starter grid (W/A/S/D, Space, Shift, LMB, RMB),
      landscape + immersive mode, 1 Hz snapshots
- [x] Simulator client (`phonecontroller-simulator`) acting as a virtual phone for testing
- [x] Rust loopback integration tests: receiver + simulator, events reach a capture backend
- [x] Android `assembleDebug` builds with compileSdk 36
- [x] Android JVM tests: pinned Rust compatibility vectors, crypto, codecs, watermark,
      RTT wrap/staleness, in-process reference-server pairing test (39 tests)
- [x] Cross-language E2E: real Kotlin `PhoneConnection` pairs with the real Rust
      receiver and delivers 3 events, 0 dropped (`RealReceiverE2ETest`)
- [x] On-device install/pairing: APK installed on a motorola edge 70 fusion
      (Android 17, arm64-v8a); manual pairing over TCP, UDP input (W/LMB/RMB)
      verified through the receiver's uinput device, clean BYE release
- [x] Real-device ergonomics: safe-drawing insets, keep-screen-on during
      gameplay, graceful disconnect when the activity stops

**Acceptance met:** receiver ↔ simulator, Kotlin client ↔ receiver and the
installed APK ↔ receiver all complete the PIN handshake and deliver encrypted
input. The physical-device session reported `events=106 dropped=0`, and `evtest`
captured the matching `KEY_W`, `BTN_LEFT` and `BTN_RIGHT` transitions.

## Milestone 3 — Keyboard and mouse

- [x] Basic Compose button rendering with press/release → KEY_DOWN/KEY_UP
- [x] Per-cell pointer tracking: release only when the last finger lifts; release on
      gesture cancellation
- [x] Mouse button events (LMB/RMB in the starter grid; protocol supports middle/back/forward)
- [x] Mouse relative movement wire format (Q16.16) + receiver sub-pixel accumulation
- [x] Linux backend end-to-end via real uinput (readback tests + simulator + JVM E2E)
- [x] Touchpad camera region: TouchpadMapper (velocity → Q16.16), smoothing, acceleration,
      sensitivity X/Y, axis inversion, adjustable width fraction, tap-to-click
- [x] Continuous mouse-delta batching at ~120 Hz (8 ms MotionAccumulator drain loop)
- [x] Middle / Back / Forward mouse buttons exposed in the UI (control cluster)
- [x] Configurable key combinations (Sprint = Shift+W; architecture supports arbitrary combos)
- [x] Toggle / hold actions (Aim-Toggle = latching RMB)
- [x] Touchpad settings panel (live-update sliders + toggles, persisted per profile)
- [b] Windows backend runtime validation — needs a Windows 10/11 machine; backend is
      compiled on Linux and code-reviewed, not run
- [ ] On-device touch latency and multitouch validation (Milestone 7)
- [x] On-device evtest verification: mouse `BTN_LEFT` (hold), `BTN_RIGHT`,
      `BTN_MIDDLE`, joystick W/A/S/D + `KEY_LEFTSHIFT` sprint chord,
      touchpad `REL_X`/`REL_Y` with correct signs, and full release on mode change

**Acceptance met:** 56 Rust tests pass, 0 warnings. Android unit test suite
extended with TouchpadMapperTest (11 tests) and InputTrackerTest (9 tests).
Receiver simulator starts cleanly and creates `/dev/input/event5` uinput device.
Integration tests cover Q16.16 sub-pixel accumulation, all 5 mouse buttons,
key combination ordering, snapshot reconciliation and watchdog release.


## Milestone 4 — Customizable Canvas & Game Profiles

- [x] Unconstrained mobile-gaming style screen canvas (drag-and-drop buttons anywhere)
- [x] Normalized coordinates (xPct, yPct) for multi-aspect ratio responsiveness
- [x] Full standard PC keyboard keycode mapping (>100 keys: A-Z, 0-9, Numpad, F1-F12, navigation, symbols, modifiers, media)
- [x] Searchable key picker in CellEditorDialog with category filters and live text search
- [x] Button shape customization (Rounded Rectangle, Circle, Pill), dimensions (widthDp, heightDp), and opacity
- [x] Virtual analog joystick (WASD + Sprint) with deadzone, 8-directional diagonal chording, and outer threshold
- [x] Layout-editing mode vs. gameplay mode separation with floating inspector and drag feedback
- [x] Per-profile layout persistence and versioned JSON profile format (`ProfileJson.kt`, schema v1)
- [x] Profile manager dialog (create, duplicate, import, export via clipboard, delete, switch active)
- [x] Bundled genre example profiles: FPS, Third-Person Action, Racing, Strategy, General WASD+Mouse
- [x] In-session Undo stack and Reset-to-defaults for layout edits
- [x] Receiver RESET (0x0A) and PROFILE_INFO (0x0B) integration on profile switch with release-all guarantee
- [x] Post-review hardening (2026-11-16):
  - [x] Release all held inputs before editor/settings/profile-manager transitions
  - [x] Single synchronous UDP FIFO so key-up can never overtake key-down
  - [x] Cumulative freeform drag + one undo entry per drag gesture
  - [x] Import sanitization (dimensions, opacity, coordinates, keycodes, duplicate ids, trailing data)
  - [x] Atomic profile writes and corrupt-file backup instead of silent replacement
  - [x] Last-finger release for multiple fingers on the same button; touchpad save debounce
  - [x] Order-preserving `withCellUpdated()` so a drag keeps its Composition slot
        (on-device defect: only the first pointer delta applied)
  - [x] Device-verified editor drag, Undo restoration, and Cancel discard
- [ ] On-device Save & Exit + app-relaunch persistence retest (session ended
      before this check when USB disconnected)

**Acceptance met:** Unit test suite passing with `ProfileJsonTest` (round-trip serialization,
defaults fallback, out-of-bounds dimension trimming, canvas coordinate distribution,
hostile-import clamping and duplicate-id repair) and `ProfileModelTest` (in-place
updates, stable order across repeated drags, unknown-id append). The Android suite
now has 71 tests across 11 classes; all 56 Rust tests and clippy pass. APK builds and
installs cleanly on device (`ZA223M8MVQ`).

## Milestone 5 — Advanced controls

- [x] Virtual analog joystick cell (WASD keyboard directions + sprint threshold)
- [x] Per-joystick sprint enable/disable: editor switch, persisted as
      `joystickSprint` (legacy profiles default on), Shift suppressed when off;
      verified on device (W only vs W+Shift)
- [ ] Macro system (sequences, chords, delays, repeats, safety caps, immediate cancel)
- [ ] Macro editor with step/duration/rate limits
- [x] Toggle and hold action wrappers
- [~] Diagnostics HUD (loss, quality, event rate, active states) beyond the current RTT line

## Milestone 6 — Gyroscope

- [ ] TYPE_GYROSCOPE + TYPE_ROTATION_VECTOR sensor collection
- [ ] Quaternion world-frame yaw/pitch extraction; roll disabled by default
- [ ] Bias calibration at rest + recenter action and auto-recenter
- [ ] Deadzone, smoothing, axis inversion, sensitivity scaling, ADS profile
- [ ] Activation modes: always / hold / toggle / touch region
- [ ] Hybrid touchpad + gyro fusion into one mouse-delta pipeline
- [ ] Gyro configuration screen and unit tests with synthetic sensor data
      (design notes: `docs/GYRO-AIMING.md`)

## Milestone 7 — Cross-platform validation

- [ ] Linux Wayland validation in real games
- [ ] Linux X11/XWayland validation
- [ ] Steam + Proton title validation
- [ ] Windows 10/11 validation `[b]` — needs a physical Windows test machine
- [x] Android on-device discovery, PIN pairing, basic keyboard/mouse input,
      landscape gameplay and immediate release on BYE/ON_STOP
- [x] Android on-device mouse buttons, joystick axes + sprint, touchpad motion,
      and release-all on editor transition (evtest-verified, 2026-11-16)
- [x] Hotspot discovery: PC joined to the phone's Wi-Fi hotspot is listed by the
      app's scan (user-confirmed on-device, 2026-11-16)
- [ ] Hotspot pairing/input latency capture and long-session soak
- [ ] On-device profile switching and touchpad-settings persistence
- [ ] Android on-device multitouch chording, input-latency measurement, soak test
- [ ] Network interruption / reconnection drills; no stuck inputs
- [ ] Long-session soak test (≥2 h)

## Milestone 8 — Polish

- [ ] Connection diagnostics screen (RTT, loss, quality, per-stream counters)
- [ ] Iconography, accent-color theming, haptic feedback
- [ ] Onboarding (uinput permission check with copy-paste udev rule)
- [ ] QR-code pairing (deferred; see below)
- [ ] Performance pass on the phone hot path
- [ ] Troubleshooting guide/FAQ and final docs sweep

---

## Known limitations and deferrals (tracked, not hidden)

| Item | Why | Tracking |
|---|---|---|
| Android on-device stress testing | Discovery/pairing/keyboard/mouse/joystick/touchpad/editor drag validated (evtest, 2026-11-16); hotspot discovery validated; hotspot input capture, profile switching, touchpad-settings persistence, multitouch chording, latency measurement and long-session soak still pending | Milestone 7 |
| Windows runtime validation | No Windows machine; `SendInput` backend compiles under `cfg(windows)` only | Milestone 7 |
| QR-code pairing | PIN pairing is implemented; QR adds camera/permission complexity | M8 candidate |
| mDNS/Avahi advertisement | UDP broadcast + manual IP cover normal and AP-isolated networks | M8 candidate |
| Gyro/macros | Freeform profiles and the layout editor are implemented; gyro and macros remain design-documented | Milestones 5–6 |
| SPAKE2 pairing | Current HKDF+HMAC PIN scheme allows offline PIN brute force from a recorded handshake; LAN-scoped threat model | Security upgrade path |
| Compose BOM pinned to 2026.06.01 | Newer BOMs require compileSdk 37, while this environment has SDK 36 | Revisit when SDK 37 is installed |
| Simulator cross-process TCP in sandbox | OS network sandbox blocks loopback between sandboxed processes; integration tests use in-process tokio server and pass | Dev environment only |
