# Milestone 6 — Gyroscope aiming

**Status: implemented and JVM-tested; on-device validation pending.**
Date: 2026-11-17.

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
  elevation of the screen normal, so roll about the screen normal is ignored
  and orientation does not drift.
- Optional **GYRO** source integrates calibrated world-frame angular velocity
  using sensor timestamps, with duplicate/stale-sample rejection.
- Shared pipeline: deadzone → time-constant smoothing → per-axis sensitivity →
  ADS multiplier → inversion → per-tick clamp → Q16.16
  `MotionAccumulator`, shared with the touchpad.
- Recenter primes the next sample and emits nothing; activation/deactivation
  re-prime according to `recenterOnRelease`.
- Runtime auto-bias while disarmed plus a 1 s explicit calibration pass with
  movement rejection; accepted bias persists per profile.

### Activation and controls

- All four activation modes: Always, Hold, Toggle, and touch Region.
- Region mode observes pointers in the `Initial` pass without consuming them,
  and draws a translucent `GYRO ZONE` rectangle over the gameplay canvas.
- New `GYRO` cell type with `ACTIVATE` and `RECENTER` actions; multitouch-safe
  first/last-finger semantics through the existing canvas button.
- `CENTER` cell and "Recenter now" action; configurable `recenterOnRelease`.
- ADS = right mouse button held; scales gyro sensitivity only.
- Hybrid touchpad + gyro aiming sums both sources in one accumulator.

### Profiles and UI

- Schema-v1 JSON gains an additive `"gyro"` object and cell `"gyroAction"`
  field. Profiles without them decode as a disabled default.
- Import sanitization for enums, finite floats, sensitivity/deadzone/smoothing,
  ADS, bias, max pixels and the activation rectangle.
- Bundled FPS profile gains `GYRO`/`CENTER` cells and an enabled `HOLD`-mode
  gyro configuration (2.6/2.2 px/°, 1.0 °/s deadzone, 0.25 smoothing,
  0.60 ADS multiplier).
- Top-bar `Gyro` button (`Gyro ●` while aiming) opens the new tuning screen.
- `GyroSettingsScreen`: availability and sensor rates, enable toggle,
  activation/source chips, all sliders and toggles, region controls,
  calibration/recenter actions, reset.
- `CellEditorDialog`: seventh type (`Gyro`) with action cards.
- `GridEditorScreen`: `+ Gyro` add button.
- Diagnostics HUD: gyro state, active source and both sensor rates.

## Files created

| File | Purpose |
|---|---|
| `android-app/app/src/main/java/com/phonecontroller/app/input/GyroMapper.kt` | Quaternion math, config, sources, activation-independent mapping, calibration |
| `android-app/app/src/main/java/com/phonecontroller/app/input/AndroidGyroSource.kt` | SensorManager listeners, HandlerThread, mailbox, rates |
| `android-app/app/src/main/java/com/phonecontroller/app/ui/GyroSettingsScreen.kt` | Full tuning/calibration UI |
| `android-app/app/src/test/java/com/phonecontroller/app/input/GyroMapperTest.kt` | 25 synthetic sensor/math tests |
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
→ 124 tests, 0 failures, 15 classes
→ APK: app/build/outputs/apk/debug/app-debug.apk
→ lint: 0 errors (11 warnings, all pre-existing project/tooling advisories)

cd pc-receiver
cargo test --workspace --no-fail-fast
→ 56 tests passed, 0 failed
cargo clippy --workspace --all-targets -- -D warnings
→ clean
```

No receiver or protocol change was required; gyro output is ordinary
`MOUSE_REL` Q16.16 data.

## Known limitations

- **No physical-device gyro validation.** No adb device/emulator was attached.
  Sensor rates, drift, feel, all four orientations, and hybrid in-game aiming
  are unverified on hardware. The math and state transitions are JVM-tested
  with synthetic rotations.
- **No in-game validation.** Touchpad + gyro while firing/moving, 60/90/120 Hz
  display behavior, and ADC/aim-assist interactions are Milestone 7 work.
- **Fused source depends on the device's orientation sensor.** Devices without
  a rotation vector show gyro unavailable; there is no accelerometer-only
  fallback by design.
- **Raw gyro drifts** until an explicit recenter or re-activation. This is
  documented and why FUSED is the default.
- **No roll action.** Roll is intentionally ignored; the original optional
  roll→wheel idea is not exposed in the UI.
- **No one-euro filter.** A time-constant low-pass is used; it preserves total
  motion but is simpler than the one-euro filter sketched in the original
  design.
- Windows runtime and on-device macro validation remain blocked/pending as
  before.

## Remaining tasks

1. Milestone 7 device pass:
   - verify both sources, drift and calibration on hardware;
   - verify `HOLD`/`TOGGLE`/`REGION` and `CENTER` with evtest;
   - verify hybrid aiming with fire/movement keys held;
   - test all four screen orientations and multiple devices.
2. Optional follow-up: persist auto-bias refinements, add one-euro filtering,
   expose roll→wheel, and add a sensor-availability self-test screen.
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
