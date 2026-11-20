# Milestone 6 — Gyroscope aiming

**Status: implemented; automated tests green; live sensor data and a real
horizontal end-to-end input sweep observed; far-edge pitch, Always On/ADS Only
gating and the full flat-phone physical acceptance sequence are still pending.**
Date: 2026-11-19 (device telemetry + capture pass), updated 2026-11-20 with the
PUBG/CODM-style activation update.

## What was implemented

### Sensor ingestion

- `AndroidGyroSource` owns `TYPE_GYROSCOPE` and
  `TYPE_GAME_ROTATION_VECTOR`/`TYPE_ROTATION_VECTOR` listeners.
- Listeners run on a dedicated `HandlerThread` at ~200 Hz (gyro) and ~100 Hz
  (orientation). Callbacks copy into a small locked mailbox and never touch
  input state or allocate sensor objects.
- Sensors start only when the active profile enables gyro and a session is
  paired. They stop in the grid editor, touchpad/profile overlays, disconnected
  state and on disposal; the gyro tuning screen and stats HUD keep them running
  for calibration and live rate readouts.
- Live sample rates are exposed to the diagnostics HUD and Gyro settings.

### Mapping and fusion

- `GyroMapper` is pure Kotlin (no Android imports) and fully JVM-testable.
- Default **FUSED** source derives yaw/pitch from the world-space heading and
  tilt of the physical short axis (+X). Yaw is measured about gravity, so a
  horizontal sweep stays horizontal even when the grip is pitched; roll about
  the short axis is ignored and orientation does not drift. The neutral pose is
  the heading/tilt captured at recenter/activation. The pitch sign flips for
  reverse landscape through a `DisplayManager` rotation listener.
- Optional **GYRO** source rotates the calibrated body rate into the world frame
  with the current orientation and tracks the same short axis, using sensor
  timestamps with duplicate/stale-sample rejection.
- Shared pipeline: deadzone → time-constant smoothing → per-axis sensitivity →
  ADS multiplier → inversion → per-tick clamp → Q16.16
  `MotionAccumulator`, shared with the touchpad.
- Recenter baselines the current heading/tilt and emits nothing;
  activation/deactivation re-prime according to `recenterOnRelease`.
- Runtime auto-bias while disarmed plus a 1 s explicit calibration pass with
  movement rejection; accepted bias persists per profile.

### Model correction from the phyphox captures

The first flat-phone model tracked the top-edge vector (+Y). The vertical
capture (69.1 s, 27,413 samples) showed the real far-edge up/down gesture is a
rotation about device Y — the top-edge vector's own axis — with 97.7% of the
rotational energy on Y and only 1.5%/0.8% on X/Z. The top edge therefore barely
moves, while the perpendicular short axis tilts directly with the gesture. The
mapper now tracks the short axis: `yaw = −atan2(X.y, X.x)` and
`pitch = pitchSign·asin(X.z)`. This decomposition is exact for
`q = Rz(yaw)·Ry(pitch)·Rx(roll)`, so roll about the short axis is ignored
exactly. The horizontal captures' 18.7°/23.2° off-Z principal axes are a
constant far-edge-up grip, not roll; centering removes them.

### Activation and controls

- All five activation modes: Always On (default), ADS Only, Hold, Toggle, and
  touch Region.
- ADS Only arms only while the tracker's reference-counted logical RMB is held
  from any source (touch cell, key combination or macro); release disarms on the
  next 120 Hz tick. ADS entry always re-primes, even with
  `recenterOnRelease = false`, so re-entry cannot replay a pose change.
- Always On is the profile default; RMB then only applies the ADS multiplier and
  never disables gyro.
- Region mode observes pointers in the `Initial` pass without consuming them,
  and draws a translucent `GYRO ZONE` rectangle over the gameplay canvas.
- New `GYRO` cell type with `ACTIVATE` and `RECENTER` actions; multitouch-safe
  first/last-finger semantics through the existing canvas button.
- `CENTER` cell and "Recenter now" action; configurable `recenterOnRelease`.
- ADS = logical right mouse button held; scales gyro sensitivity only, never the
  touchpad.
- Hybrid touchpad + gyro aiming sums both sources in one accumulator.

### Profiles and UI

- Schema-v1 JSON gains an additive `"gyro"` object and cell `"gyroAction"`
  field. Profiles without them decode as a disabled default.
- Import sanitization for enums, finite floats, sensitivity/deadzone/smoothing,
  ADS, bias, max pixels and the activation rectangle.
- Bundled FPS profile gains `GYRO`/`CENTER` cells and an enabled, Always On
  gyro configuration (2.6/2.2 px/°, 1.0 °/s deadzone, 0.25 smoothing,
  0.60 ADS multiplier).
- Top-bar `Gyro` button (`Gyro ●` while aiming) opens the new tuning screen.
- `GyroSettingsScreen`: availability and sensor rates, enable toggle,
  activation/source chips (Always on, ADS only, Hold, Toggle, Region), all
  sliders and toggles, region controls, calibration/recenter actions, reset.
  Status distinguishes ADS-not-held from live preview.
- `CellEditorDialog`: seventh type (`Gyro`) with action cards.
- `GridEditorScreen`: `+ Gyro` add button.
- Diagnostics HUD: gyro state, activation mode, logical ADS state, active
  source, both sensor rates, live yaw/pitch rate and output pixels.

## Files created

| File | Purpose |
|---|---|
| `android-app/app/src/main/java/com/phonecontroller/app/input/GyroMapper.kt` | Quaternion math, config, sources, activation-independent mapping, calibration |
| `android-app/app/src/main/java/com/phonecontroller/app/input/AndroidGyroSource.kt` | SensorManager listeners, HandlerThread, mailbox, rates |
| `android-app/app/src/main/java/com/phonecontroller/app/ui/GyroSettingsScreen.kt` | Full tuning/calibration UI |
| `android-app/app/src/test/java/com/phonecontroller/app/input/GyroMapperTest.kt` | Synthetic sensor/math tests, including vertical/combined capture sweeps and reverse landscape |
| `docs/MILESTONE-6.md` | This report |

## Files modified

| File | Change |
|---|---|
| `android-app/app/src/main/java/com/phonecontroller/app/profile/ProfileModel.kt` | `CellType.GYRO`, `gyroAction`, `GameProfile.gyro` |
| `android-app/app/src/main/java/com/phonecontroller/app/profile/ProfileJson.kt` | Gyro/cell JSON serialization, parsing, sanitization |
| `android-app/app/src/main/java/com/phonecontroller/app/profile/DefaultProfiles.kt` | FPS gyro cells + config |
| `android-app/app/src/main/java/com/phonecontroller/app/ui/PlayScreen.kt` | Sensor lifecycle, 120 Hz tick, activation, region overlay, GYRO cells, tuning entry, diagnostics |
| `android-app/app/src/main/java/com/phonecontroller/app/ui/CellEditorDialog.kt` | Gyro type and action editor |
| `android-app/app/src/main/java/com/phonecontroller/app/ui/GridEditorScreen.kt` | `+ Gyro` control |
| `android-app/app/src/main/java/com/phonecontroller/app/input/InputTracker.kt` | `isMouseButtonHeld()` for ADS detection |
| `android-app/app/src/test/java/com/phonecontroller/app/profile/ProfileJsonTest.kt` | Gyro round-trip, legacy default, hostile import tests |
| `docs/GYRO-AIMING.md` | Rewritten from design-only to implementation reference |
| `docs/PLAN.md`, `README.md`, `ARCHITECTURE.md`, `docs/PROTOCOL.md` | Status and architecture updates |

## Build and test results

```text
cd android-app
./gradlew testDebugUnitTest assembleDebug lintDebug
→ BUILD SUCCESSFUL
→ 153 tests, 0 failures, 15 classes (52 GyroMapperTest, 17 ProfileJsonTest)
→ APK: app/build/outputs/apk/debug/app-debug.apk
→ lint: 0 errors

cd pc-receiver
cargo test --workspace --no-fail-fast
→ 56 tests passed, 0 failed
cargo clippy --workspace --all-targets -- -D warnings
→ clean
```

No receiver or protocol change was required; gyro output is ordinary
`MOUSE_REL` Q16.16 data.

## Known limitations

- **Physical acceptance incomplete.** Four phyphox captures (all ~397 Hz) drove
  the current model: the horizontal captures show 22 alternating ±140° yaw
  strokes through an 18.7–23.2° far-edge-up grip; the vertical capture is 97.7%
  long-axis rotation; the combined capture mixes yaw and pitch. An end-to-end
  evtest recording captured 16.5 s of left rotation as −597 px `REL_X` with
  +20 px `REL_Y` (~3% leakage). The far-edge up/down and combined gestures have
  not yet been verified end to end; later pitch captures recorded zero motion
  because the phone was stationary. Always On and ADS Only gating, logical RMB
  from multiple sources, reverse-landscape physical repeat, recenter-at-pose and
  long-session drift are still pending. Do not claim the gyro scheme complete
  until they pass.
- **No in-game validation.** Touchpad + gyro while firing/moving, 60/90/120 Hz
  display behavior, and ADC/aim-assist interactions are Milestone 7 work.
- **Fused source depends on the device's orientation sensor.** Devices without
  a rotation vector show gyro unavailable; there is no accelerometer-only
  fallback by design.
- **Raw gyro drifts** until an explicit recenter or re-activation. This is
  documented and why FUSED is the default.
- **No roll action.** Roll about the short axis is intentionally ignored.
  With the phone flat, a twist about the screen normal is the required yaw and
  cannot also be ignored.
- **No one-euro filter.** A time-constant low-pass is used; it preserves total
  motion but is simpler than the one-euro filter sketched in the original
  design.
- Windows runtime and on-device macro validation remain blocked/pending as
  before.

## Remaining tasks

1. Milestone 7 device pass:
   - complete the flat-phone physical sequence: left/right yaw (end-to-end
     horizontal captured), far-edge up/down pitch, combined motion, roll
     isolation, recenter at a flat and a tilted pose, and repeats in normal and
     reverse landscape;
   - verify both sources, drift and calibration on hardware;
   - verify Always On/ADS Only with the aim button, a key combination and a
     macro, including release and re-entry without a jump;
   - verify `HOLD`/`TOGGLE`/`REGION` and `CENTER` with evtest;
   - verify hybrid aiming with fire/movement keys held;
   - test multiple devices.
2. Optional follow-up: persist auto-bias refinements, add one-euro filtering,
   add a sensor-availability self-test screen.
3. Update the milestone checklist after the device pass.

## Commands

```bash
# Android build + tests
cd android-app
./gradlew testDebugUnitTest assembleDebug lintDebug

# Install when a device is attached
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Receiver (Linux)
cd ../pc-receiver
cargo run --release
```

In the app: **Start / connect → pair → Gyro** (top bar) to tune. The bundled
FPS profile has `GYRO` (hold) and `CENTER` cells; other profiles start with
gyro disabled.
