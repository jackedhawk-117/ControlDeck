# Milestone 2 report — Basic communication

**Status: complete, including physical-device validation.**

The Android app builds, its actual Kotlin client pairs with the real Rust
receiver, and encrypted input reaches the receiver with zero drops. The APK was
installed and exercised on a motorola edge 70 fusion (Android 17, arm64-v8a):
manual pairing, UDP keyboard/mouse input and disconnect release all work
against the real receiver and its uinput device.

## What was implemented

### Rust receiver

- TCP control server and UDP input/discovery server (`tokio`).
- DISCOVER/ANNOUNCE broadcast discovery with per-IP rate limiting and the
  resolved post-bind TCP port.
- PIN pairing: HELLO/HELLO_REPLY, HKDF-SHA256 key derivation, HMAC proofs in
  both directions, AES-256-GCM `SECURE_FRAME` control packets.
- Session registry with ownership rules (new pairing claims ownership before
  capacity checks), session replacement, PING/PONG heartbeat, BYE/RESET
  handling and STATS emission.
- Safety watchdog: input silence beyond `--timeout-ms` releases all held keys
  and mouse buttons; teardown always releases input.
- Simulator client used for demos and integration tests.

### Android app

- Gradle/AGP project: Kotlin, Jetpack Compose, Material 3, compileSdk 36,
  minSdk 26.
- `net/Protocol.kt`: byte-compatible mirror of the Rust framing, handshake,
  input payloads, stats and unsigned seq watermark.
- `net/Crypto.kt`: HKDF-SHA256, HMAC proofs, AES-256-GCM nonce/AAD rules.
- `net/Connection.kt`: multi-interface UDP discovery, pairing, TCP control loop,
  UDP sender + stats listener, 1 Hz snapshots, TCP PING/PONG RTT, drop
  diagnostics. Discovery binds one socket per local IPv4 interface and sends
  the interface's directed broadcast, so the PC is found while it is joined to
  the phone's Wi-Fi hotspot even when mobile data is the default route.
- UI: dark theme; home/connection screen with discovery, manual IP, PIN entry;
  landscape/immersive gameplay screen with a fixed 2×4 starter grid (W/A/S/D,
  Space, Shift, LMB, RMB) and multitouch-safe press/release.
- `AppModel` connection state, error handling and disconnect.
- Android lifecycle safety: disconnect on `ON_STOP`, keep-screen-on during
  gameplay, and safe-drawing insets on both screens.
- JVM test suite: pinned Rust vectors, crypto tamper/round-trip, RFC 5869 HKDF,
  protocol codecs, watermark semantics, RTT wrap/staleness, in-process
  reference-server pairing, and an optional real-receiver E2E.

## Files created or modified

```text
android-app/settings.gradle.kts
android-app/build.gradle.kts
android-app/gradle.properties
android-app/gradlew, gradlew.bat, gradle/wrapper/*
android-app/local.properties              (machine-specific, gitignored)
android-app/app/build.gradle.kts
android-app/app/src/main/AndroidManifest.xml
android-app/app/src/main/java/com/phonecontroller/app/MainActivity.kt
android-app/app/src/main/java/com/phonecontroller/app/AppModel.kt
android-app/app/src/main/java/com/phonecontroller/app/net/{Protocol,Crypto,Connection}.kt
android-app/app/src/main/java/com/phonecontroller/app/ui/{Theme,HomeScreen,PlayScreen}.kt
android-app/app/src/test/java/com/phonecontroller/app/net/
  CompatVectorsTest.kt
  PairingIntegrationTest.kt
  ConnectionRttTest.kt
  RealReceiverE2ETest.kt
docs/PROTOCOL.md                          (HELLO name_len corrected to u8)
docs/PLAN.md                              (statuses corrected)
docs/linux-setup.md, docs/windows-setup.md, docs/GYRO-AIMING.md
README.md, ARCHITECTURE.md
```

Rust receiver/simulator/protocol files were extended and fixed during the
milestone; the workspace remains the Milestone 1 structure.

## Build and test results

Date: 2026-11-14.

```text
# Rust
cargo test --workspace --no-fail-fast   → 56 passed, 0 failed
cargo clippy --workspace --all-targets  → 0 warnings

# Android
./gradlew testDebugUnitTest            → 39 tests passed, 0 failed
./gradlew assembleDebug                → app-debug.apk (≈12 MB)

# Cross-language E2E (real Kotlin client → real Rust receiver)
RealReceiverE2ETest                     → passed
receiver log: phone paired; input enabled
              events=3 dropped=0
              phone sent BYE reason=0; session closed
```

### On-device validation

Device: motorola edge 70 fusion (`ZA223M8MVQ`), Android 17, arm64-v8a,
1272×2772. PC: CachyOS, `192.168.29.31`; the phone and PC share the
`192.168.29.0/24` Wi-Fi.

```text
APK install                         → Success
Scan local network                  → "cachyos — 192.168.29.31"
Manual PIN pairing (246810)         → phone paired; input enabled
Hold W / tap LMB / tap RMB           → evtest captured
                                      KEY_W down/up, BTN_LEFT down/up,
                                      BTN_RIGHT down/up
Receiver session counters            → events=106 dropped=0
RTT after TCP PING/PONG fix          → 8 ms
Hold W, press Home                   → evtest KEY_W up immediately;
                                      phone sent BYE, events=10 dropped=0
```

The first attempt exposed two non-protocol issues: the phone's screen locked
mid-session (so the receiver watchdog released inputs), and the Home title
rendered under the Android 17 status bar. Both are fixed: gameplay holds
`FLAG_KEEP_SCREEN_ON`, `ON_STOP` disconnects and releases input, and both
screens apply `safeDrawingPadding`.

The earlier host-side `nc -u` UDP test was inconclusive (toybox netcat
behaviour), not evidence of a blocked port: the real app's UDP input and
discovery both work.

Hotspot topology (2026-11-16): the PC was joined to the phone's Wi-Fi hotspot
and the app's scan still listed it. The original single-socket broadcast used
the default route, which points at mobile data on a hotspot phone, so discovery
now opens one UDP socket per active IPv4 interface and sends `DISCOVER` to both
`255.255.255.255` and that interface's directed broadcast. The scan UI reports
which interface reached each PC (for example
`cachyos — 192.168.43.42 · Phone hotspot`). See
[`docs/HOTSPOT.md`](HOTSPOT.md) for setup, firewall rules and troubleshooting.
No protocol change was required; the receiver already listened on all
interfaces.

Notable bugs found and fixed while making the two sides interoperate:

- Kotlin handshake used `OutputStream.use {}`, which closed the TCP socket
  before reading HELLO_REPLY/AUTH_PROOF.
- A new buffered stream per TCP packet could swallow bytes for the next frame.
- RTT was derived from the last INPUT_DELTA timestamp echoed in STATS, so it
  grew without bound while the user paused. The phone now measures RTT with
  its own TCP PING/PONG every 2 s; the STATS echo remains wire-compatible.
- uinput node discovery could race the kernel: `UI_DEV_CREATE` returns before
  the `eventN` node is published. `UinputDevice::create` now polls briefly,
  which also fixes intermittent readback test failures on a busy host.
- Android 15+ edge-to-edge drew the Home title under the status bar; both
  screens now use `safeDrawingPadding` (and avoid the landscape camera cutout).
- Locking/backgrounding the phone left the session open until the receiver
  watchdog fired. The app now sends BYE on `ON_STOP`, and gameplay holds the
  screen on.
- TCP/UDP sequence numbers were assigned before the write lock/FIFO, allowing
  reordering and receiver-side drops; sends are now serialized and seq is
  assigned in wire order.
- Snapshot `base_seq` was encoded before enqueue; snapshots are now built by
  the single sender so they observe all preceding deltas.
- Kotlin watermark used signed comparison; now matches Rust's full u32 range.
- HELLO `name_len` in `docs/PROTOCOL.md` said u16; the implementation and
  vector are u8, and the doc was corrected.
- Compose BOM 2026.09.00 requires compileSdk 37; pinned to 2026.06.01
  (Compose UI 1.11.4 / Material3 1.4.0) for SDK 36.

## Known limitations

- **Windows runtime** remains untested (no Windows machine).
- Discovery was validated on one home network and over the phone's hotspot.
  UDP broadcast can still be blocked by AP isolation/guest networks; manual IP
  entry remains available.
- Touch-to-uinput latency has not been measured with an external high-speed
  camera. The 8 ms figure is the TCP control-plane RTT, not input latency.
- Multitouch chording and long-session soak are not yet stress-tested on
  hardware (Milestone 7).
- The gameplay grid is a fixed starter layout; touchpad, grid editor, profiles,
  macros and gyro are Milestones 3–6.
- PIN pairing allows offline brute force from a recorded handshake; documented
  and LAN-scoped. SPAKE2 is the planned upgrade.

## Remaining tasks

- Milestone 3: touchpad camera region, continuous mouse deltas, configurable
  key combinations, more mouse buttons.
- Milestone 7: Windows runtime validation, multitouch stress, long-session
  soak, and an end-to-end input-latency measurement.

## Exact commands

```bash
# Receiver (prints a random PIN)
cd pc-receiver
cargo build --release
./target/release/phonecontroller-receiver --pair

# Android build + tests
cd android-app
./gradlew testDebugUnitTest
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Cross-language E2E (requires the release receiver and /dev/uinput access)
cd pc-receiver && cargo build --release
cd ../android-app
./gradlew testDebugUnitTest --tests '*RealReceiverE2ETest*'

# Simulator demo
cd pc-receiver
./target/release/phonecontroller-receiver --pin 123456 &
./target/release/phonecontroller-simulator --host 127.0.0.1 --pin 123456 --demo-ms 5000
# inspect: libinput debug-events  (or evtest /dev/input/eventN)
```
