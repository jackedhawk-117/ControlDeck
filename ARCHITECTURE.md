# Architecture — PhoneController

A smartphone becomes a programmable wireless keyboard/mouse for PC games.
Two applications, one versioned binary protocol. **No gamepad emulation.**

> This document describes the target architecture. The living implementation
> status is in [`docs/PLAN.md`](docs/PLAN.md); Milestones 1–6 implement the
> transport, pairing, input state machine, Linux/Windows backends, freeform
> editor, profiles, macros, diagnostics and gyro aiming. On-device gyro and
> macro validation remain Milestone 7 work.

```
┌─────────────────────────── Android (Kotlin) ───────────────────────────┐
│  UI (Compose/M3)         Input pipeline              Network          │
│ ┌────────────────┐   ┌─────────────────────┐   ┌──────────────────┐   │
│ │ Home / Pairing │   │ Touch grid (multi-  │   │ Discovery (UDP   │   │
│ │ Grid editor    │──▶│ touch, pointer IDs) │──▶│  broadcast)      │   │
│ │ Play screen    │   │ Touchpad cell       │   │ Control (TCP)    │   │
│ │ Gyro/TP config │   │ Gyro mapper (quat.  │   │  pairing+auth    │   │
│ │ Profile mgr    │   │  world-frame yaw/   │   │ Input (UDP)      │   │
│ │ Macro editor   │   │  pitch, fusion)     │   │  AES-256-GCM     │   │
│ │ Diagnostics    │   │ Macros (phone-side) │   │  heartbeat/STATS │   │
│ └────────────────┘   └─────────────────────┘   └────────┬─────────┘   │
│  Profiles: versioned JSON, all scaling/sensitivity phone-side          │
└──────────────────────────────────────────────────────────┼────────────┘
                             UDP 41000 (discovery+input)   │ TCP 41000 (control)
┌─────────────────────────── PC receiver (Rust) ────────────▼────────────┐
│  Server (tokio)                    Input core              Backend     │
│ ┌────────────────────┐   ┌──────────────────────┐   ┌───────────────┐  │
│ │ TCP session actors │──▶│ Delta pipeline       │──▶│ Linux: uinput │  │
│ │  handshake, PIN,   │   │  (dedupe, held-state │   │  (evdev u16)  │  │
│ │  secure frames     │   │   machine, Q16.16    │   ├───────────────┤  │
│ │ UDP: DISCOVER/     │   │   remainder, release │   │ Windows:      │  │
│ │  ANNOUNCE, input   │   │   -all guarantees)   │   │ SendInput     │  │
│ │ Watchdog + STATS   │   └──────────────────────┘   └───────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

Crate / module map:

| Component | Path | Notes |
|---|---|---|
| Protocol (Rust) | `pc-receiver/crates/pc-protocol` | framing, handshake, crypto, payloads; zero IO |
| Input abstraction | `pc-receiver/crates/input-core` | `InputBackend` trait + state machine, platform-free |
| Linux backend | `pc-receiver/crates/input-linux` | raw `/dev/uinput` ioctls via `libc`, no root needed |
| Windows backend | `pc-receiver/crates/input-windows` | `SendInput`, scan-code based, compile-gated |
| Receiver server | `pc-receiver/crates/pc-receiver` | tokio TCP+UDP, sessions, watchdog, STATS |
| Simulator | `pc-receiver/crates/pc-receiver/src/bin/` | virtual phone for testing/demos |
| Android app | `android-app/` | Kotlin, Compose/M3, protocol mirror in `net/` |
| Protocol spec | `docs/PROTOCOL.md` | single source of truth (v1) |

---

## 1. Key architectural decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **evdev keycodes as canonical key space** (u16, Linux `input-event-codes.h`) | Linux backend is zero-map; Windows backend holds one static evdev→scancode/VK table; phone and receiver share the same constants. No re-mapping per profile. |
| D2 | **Event-based deltas + periodic full-state snapshots over UDP** | Low bandwidth and latency, while snapshots reconcile after packet loss. Receiver is idempotent (dedupe by held-state machine), so a lost event packet self-heals within ≤1 s (snapshot interval). |
| D3 | **Phone is the single source of truth for sensitivity, acceleration, smoothing, gyro mapping, and all scaling** | One sensitivity model; receiver applies deltas 1:1 (Q16.16 fixed point) with sub-pixel remainder accumulation. Receiver stays game-agnostic and profile-free. |
| D4 | **Macros execute on the phone** | They become ordinary delta events; cancellable instantly (phone kills its own scheduler), receiver needs no macro logic, and no "cheat automation" capability is added on the PC side. |
| D5 | **UDP for input + discovery, TCP for pairing/control** (ports 41000 both) | Per spec. Discovery rides the same UDP socket as input; one socket, one port to document/firewall. |
| D6 | **PIN pairing → HKDF-SHA256 → AES-256-GCM session crypto with direction-tagged nonces** | Local networks are never trusted (spec §6). AES-256-GCM is natively accelerated on phones (Java `Cipher`) and PCs (AES-NI). Nonce = `dir(1B) ‖ counter(7B)` prevents reuse across directions; packet `seq` doubles as the nonce counter and AAD. No certificates/CA to manage. Documented limitation: HKDF+MAC-confirmation PIN scheme allows offline PIN brute-force from a recorded handshake (LAN-scoped risk; SPAKE2 is the upgrade path). |
| D7 | **uinput via raw ioctls (`libc`), not libevdev bindings or hid/uhelper** | ~200 lines, zero extra bindings, full control over device semantics, no root required (needs write access to `/dev/uinput`). Works identically under Wayland and X11 because it is a kernel-level input device — compositors and games just see a regular keyboard/mouse (D9). |
| D8 | **Windows injection via `SendInput` with `KEYEVENTF_SCANCODE`** | Games (including those reading raw input/DirectInput) see hardware scan codes; VK-only injection often fails in games. Mouse buttons/relative move/scroll via `MOUSEINPUT`. Known, documented limits: elevated apps, UIPI, anti-cheat. |
| D9 | **No virtual gamepad / ViGEm / xpadneo anywhere** | Charter requirement: keyboard+mouse only. Also avoids the most fragile driver layer entirely. |
| D10 | **Input safety as a state property, not an event handler** | The held-state machine tracks every pressed code; `release_all()` is invoked on: TCP close, BYE, watchdog timeout, RESET (profile switch), explicit disconnect, server shutdown/panic path, and new-session replacement. A session's input is released *before* any state transition that could orphan it. |

## 2. Data flow (input path)

```
touch/sensor event (Android)
  → input pipeline @120 Hz tick: grid cells, touchpad filter, gyro world-frame
    mapping, fusion into one mouse-delta accumulator (phone applies sensitivity)
  → INPUT_DELTA packet (events batched, ≤1400 B, seq++, encrypted, UDP)
  → receiver UDP task: decrypt (session lookup), parse, feed session pipeline
  → input-core state machine: dedupe, held-set, sub-pixel remainder
  → BackendCommand → backend thread → uinput EV_KEY/REL events / SendInput
```

Latency budget targets: touch→packet < 5 ms (120 Hz tick, no allocations in hot
path), packet→uinput write < 1 ms (single thread hop), end-to-end < 15 ms typical
on WLAN.

## 3. Security model

- Threat: other hosts on the same LAN (dorm rooms, shared Wi-Fi, coffee shops).
- Pairing: 6+ digit PIN displayed by the receiver (QR code later). Handshake
  binds nonces + transcript into an HMAC proof both ways; wrong PIN fails
  closed. Derived key = HKDF-SHA256(PIN material ‖ client_nonce ‖ server_nonce).
- Every post-handshake byte (TCP control frames and UDP input packets) is
  AES-256-GCM sealed; AAD = packet header (incl. `seq`), so replay/reorder
  attacks fail the tag check and the state machine tolerates loss.
- Discovery packets (DISCOVER/ANNOUNCE) are intentionally plaintext (they carry
  no secrets and no state); receiver replies are rate-limited.
- Receiver binds all interfaces but enforces: max packet size (1400), max
  concurrent sessions (default 1 — new session kicks old **after** releasing
  its inputs), per-IP accept rate limiting, bounded parse errors.
- Session isolation: one session_id namespace; input packets authenticate via
  GCM with the session key — a foreign phone cannot inject without the key.

## 4. Platform notes & risks

| Risk | Assessment | Mitigation |
|---|---|---|
| **Wayland input permissions** | uinput creates a kernel evdev device; Wayland compositors read it like any HID device. No compositor-specific API needed. The real gate is `/dev/uinput` write access. | `input` group membership or a udev rule (see `docs/linux-setup.md`); receiver preflights `/dev/uinput` and prints exact remediation. |
| **Steam / Proton games** | SDL and raw-input stacks enumerate evdev devices; a uinput keyboard/mouse is indistinguishable from hardware. | Verified via SDL test harness readback (Milestone 7); no special-casing. |
| **uinput unavailable / no permission** | Common on stock setups. | Explicit preflight + clear instructions; never silently fall back to X11 tools (would break Wayland). |
| **Gyro → mouse correctness** | Naive accelerometer mapping is unusable. | Default FUSED path derives yaw/pitch from the world-space screen-normal heading/elevation (roll ignored, drift-free); optional raw-gyro path integrates calibrated world-frame angular velocity using sensor timestamps. Bias auto-calibration while disarmed plus explicit 1 s calibration; recenter; deadzone/smoothing; ADS multiplier. No raw sensor data crosses the wire — only final deltas (protocol stays lean). |
| **Packet loss / reorder on Wi-Fi** | UDP is lossy by design. | Event deltas + 1 Hz snapshots + idempotent state machine; sequence numbers; receiver drops stale seq but never releases held keys on a single loss (only on real disconnect conditions). |
| **Windows anti-cheat / elevated apps** | `SendInput` cannot inject into elevated processes from a non-elevated receiver; some anti-cheats flag synthetic input. | Documented in `docs/windows-setup.md` (run receiver elevated for elevated games; no anti-cheat bypass is implemented, by charter). |
| **Broadcast discovery blocked (AP isolation)** | Guest/IoT VLANs commonly block broadcast. | Manual IP entry is first-class in the UI; mDNS advertisement is a tracked follow-up. |
| **Stuck keys** | Worst failure mode for usability. | D10 above; integration tests assert zero residual held state after disconnect/timeout/reset. |

## 5. Concurrency model

- **Receiver:** tokio multi-thread runtime. One task per TCP session (actor, owns
  its session state), one UDP reader task (discovery + input routing, lock-free
  dispatch into per-session pipelines via mpsc), one dedicated OS thread per
  backend (uinput writes are sync syscalls; keeps the async runtime unblocked),
  one watchdog task (timeout sweep), one STATS emitter. Shared state: session
  registry behind a `RwLock`, input bus via bounded channels (backpressure =
  drop-oldest delta batch, never block the network task).
- **Android:** coroutines on `Dispatchers.IO` for network; UI and input mapping
  on main. Sensor callbacks run on a dedicated `HandlerThread` and publish into
  a small locked `FloatArray` mailbox. Input tick loop is a single coroutine
  with a fixed 8.33 ms period; the gyro mapper is fed from that loop and only
  writes Q16.16 deltas into the shared `MotionAccumulator`. Orientation math
  uses pre-allocated normal/rate arrays; the only per-sample allocation is the
  small two-int `Motion` result (encode buffers are pooled).

## 6. Smallest end-to-end prototype (Milestone 2)

1. Android app: Home screen → manual IP or discovered list → PIN prompt →
   "connected" state; a fixed 2×4 button grid (W/A/S/D, Space, Shift, LMB,
   RMB) with multitouch press/release and 1 Hz state snapshots. The touchpad
   camera region is the first Milestone 3 addition.
2. Receiver: discovery reply, PIN pairing, uinput device creation, delta
   application, release-all on disconnect/timeout.
3. Test harness: `phonecontroller-simulator` replays the same protocol against
   the receiver, and the Android app's actual Kotlin client is also exercised
   against the real receiver in a JVM E2E test. The phone and the simulator are
   peers on the wire.

Everything after this point (grid editor, profiles, gyro, macros) is
incremental extension of these two programs.

## 7. Versioning & compatibility

- Protocol version byte in every packet header (currently `1`). Mismatched
  minor features negotiate via HELLO feature flags; incompatible majors refuse
  with ERROR code 0x0001.
- Profile JSON carries `schema_version` and is migrated forward-only.
