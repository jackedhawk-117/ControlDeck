# Gyro aiming — implementation reference (Milestone 6)

> **Status: implemented phone-side, JVM-tested, not yet validated on a physical
> device.** The wire protocol is unchanged: raw sensor data never leaves the
> phone; only the resulting Q16.16 mouse deltas are transmitted.

The goal is console-style motion aiming (Switch/DualSense-like): large camera
movement on the touchpad, fine aim with the gyroscope, both feeding one mouse
pipeline.

## 1. Sensors and source selection

| Sensor | Use |
|---|---|
| `TYPE_GYROSCOPE` (rad/s) | High-rate relative rotation for the optional raw-gyro source; bias-corrected |
| `TYPE_GAME_ROTATION_VECTOR` | Preferred fused absolute orientation (no magnetometer noise) |
| `TYPE_ROTATION_VECTOR` | Fallback fused orientation when the game vector is unavailable |

`AndroidGyroSource` owns both listeners. They are registered on a dedicated
`HandlerThread`, so 200/100 Hz callbacks never run on the main looper. Each
callback copies its values into a three/four-float mailbox guarded by a small
lock; no Android sensor object or allocation escapes the callback. Sensors are
registered only while the active profile enables gyro and a session is paired.
They stop in the grid editor, touchpad settings and profile manager, on
disconnect and on disposal; the gyro tuning screen and stats HUD keep them
running for calibration and live rate readouts. Callbacks queued after a stop
are dropped so a restart cannot ingest a stale sample.

Two selectable sources share the same output path:

- **FUSED (default)** — reads each new rotation-vector sample and derives the
  heading/elevation of the screen normal. It never drifts.
- **GYRO (optional)** — integrates calibrated device-frame angular velocity at
  the input tick rate. It is smoother on phones with a slow rotation-vector
  sensor but drifts until the next recenter. Duplicate/stale sensor samples are
  ignored, so a stalled sensor cannot keep integrating forever.

Both sources require an orientation sensor for a stable yaw/pitch reference.
Phones without one show gyro as unavailable instead of guessing from the
accelerometer.

## 2. Orientation math

The mapper tracks the **screen normal** — device +Z, the direction the screen
faces — as a unit vector in world coordinates:

1. Convert Android's `(x, y, z, w)` rotation vector to a normalized quaternion
   `q`.
2. Rotate `(0, 0, 1)` by `q`: `d = q · ẑ`.
3. `heading = atan2(d.x, d.y)` and `pitch = asin(d.z)`.
4. Per new orientation sample, take wrapped differences
   `dHeading = wrapPi(heading - lastHeading)` and
   `dPitch = pitch - lastPitch`, divide by the sensor-timestamp interval, then
   convert to mouse pixels.
5. **Roll around the screen normal therefore produces no camera movement**,
   which is the requested default. There is no roll axis to disable in the UI;
   yaw and pitch can each be disabled independently.

Near the poles (`|d.xy| < 0.08`) heading is ill-defined. The mapper reports zero
yaw for that step but still updates the baseline, so leaving the pole does not
release a large stored jump.

The FUSED source uses sensor timestamps for `dt` (clamped to 2–200 ms). The GYRO
source rotates angular velocity into world space with the current quaternion,
computes the heading/pitch rates of the screen normal, and integrates with the
raw gyro sample timestamp (clamped to 1–50 ms).

Recenter stores the current orientation as the new baseline. The next sample
produces no delta, for both sources.

## 3. Activation modes

| Mode | Behavior |
|---|---|
| Always | Contributes whenever a paired session is in gameplay and this profile enables gyro |
| Hold | Contributes only while a `GYRO` activation cell is held |
| Toggle | A `GYRO` activation cell latches aiming on or off |
| Region | Contributes while at least one finger is inside the configured canvas rectangle |

Region detection observes pointer events in the **Initial** pass and never
consumes them, so a finger inside the zone still presses controls underneath
(or drives the camera touchpad). The rectangle is drawn as a translucent
`GYRO ZONE` overlay in gameplay.

Opening the editor, either tuning screen, the profile manager, or disconnecting
disarms gyro. A profile switch resets hold/toggle/region state. Activation and
deactivation always re-prime the mapper (unless "re-prime neutral pose after
hold release" is explicitly disabled), so transitions do not cause a camera
jump.

The `recenterOnRelease` option controls what happens to the neutral pose when
aiming is disarmed:

- **on (default)** — forget the old pose; the next activation is neutral
  wherever the phone is held.
- **off** — keep the old pose; re-arming reflects orientation changes made while
  disarmed. This is intentionally a jump, bounded by the per-tick clamp.

Overlays and profile changes always call `recenter()` regardless of this option.

## 4. Response pipeline

Per tick, in order:

1. Choose yaw/pitch rates (zero for a disabled axis).
2. Apply the angular-rate deadzone (`hypot(yaw, pitch) < deadzoneDps` → 0).
3. Low-pass the rates with a time-constant derived from `smoothing`; the filter
   is designed to preserve total motion, so it lowers the first step rather than
   losing distance.
4. `dx = yawRate · dt · sensitivityX · adsScale`.
5. `dy = -pitchRate · dt · sensitivityY · adsScale`.
6. Apply axis inversion.
7. Clamp each axis to `maxPixelsPerTick` (sensor-spike guard, 10–600 px).
8. Convert to Q16.16 and add to the same `MotionAccumulator` used by the
   touchpad. The 120 Hz sender drains the sum, so gyro and touchpad cannot
   overwrite or stall each other.

ADS is detected as mouse button 2 (right button) being held by any source,
including a toggle. The ADS multiplier is applied only to gyro sensitivity;
touchpad sensitivity is unchanged.

## 5. Calibration

- **Runtime auto-bias** while disarmed: samples within 0.25 rad/s of the current
  estimate slowly refine the bias. This absorbs a stale imported calibration
  without letting deliberate movement run away with it.
- **Explicit calibration**: the Gyro settings screen samples the gyroscope for
  1 s. At least 10 samples are required. If the standard deviation exceeds
  0.35 rad/s the pass is rejected (phone was moving); otherwise the mean
  becomes the bias.
- The accepted bias is persisted in the profile as `biasX`, `biasY`, `biasZ`
  (rad/s, clamped to ±1) and restored on load.
- A dedicated `CENTER` cell action, plus a "Recenter now" button in the tuning
  screen, reset the neutral pose without moving the camera.

Calibration applies to the GYRO source directly. The FUSED source is
orientation-based and does not need a bias to avoid drift, but calibration is
still available and is used when switching to the raw source.

## 6. Per-profile configuration

Stored in the additive `"gyro"` object of schema-v1 profile JSON. A profile
without that object decodes as a disabled default, so old exports still load.

| Field | Default | Range / notes |
|---|---|---|
| `enabled` | `false` (bundled FPS profile: `true`) | enable gyro for this profile |
| `activationMode` | `HOLD` | `ALWAYS`, `HOLD`, `TOGGLE`, `REGION` |
| `sensorSource` | `FUSED` | `FUSED`, `GYRO` |
| `sensitivityX/Y` | `3.0` | 0.25–20 px per degree |
| `invertX/Y` | `false` | axis inversion |
| `enableYaw/Pitch` | `true` | independent axis enable |
| `deadzoneDps` | `1.0` | 0–10 °/s |
| `smoothing` | `0.20` | 0–0.9 |
| `maxPixelsPerTick` | `200` | 10–600 px |
| `adsMultiplier` | `0.65` | 0.1–2.0 |
| `recenterOnRelease` | `true` | see §3 |
| `biasX/Y/Z` | `0` | ±1 rad/s |
| `regionXPct/YPct` | `0.30` | 0–0.9 |
| `regionWidthPct/HeightPct` | `0.40` | ≥0.10, kept inside the canvas |

Cells of type `GYRO` carry `"gyroAction": "ACTIVATE"` (default) or
`"RECENTER"`. The bundled FPS profile adds `GYRO` and `CENTER` cells and enables
gyro in `HOLD` mode with 2.6/2.2 px/° sensitivity.

Imports are sanitized (finite values, clamped ranges, enum fallback, region
kept inside the canvas). Hostile values cannot produce NaN deltas or an
out-of-bounds region.

## 7. UI

- **Play screen** top bar: `Gyro` button opens tuning; it shows `Gyro ●` while
  aiming is active.
- **Gyro settings screen**: source availability and live sensor rates, enable
  toggle, activation/source chips, all sliders and toggles, region rectangle
  controls, sensor explanation, calibration status/value, recenter and reset.
- **Cell editor**: a `Gyro` type with `Arm gyro` / `Recenter` action cards.
- **Grid editor**: `+ Gyro` adds an activation cell.
- **Diagnostics HUD**: gyro on/off/armed/aiming state, active source, and live
  gyro/orientation sample rates.

## 8. Testing and limitations

JVM tests (`GyroMapperTest`) cover:

- right/up turn signs, roll rejection, heading wrap;
- recenter and activation with no jump; `recenterOnRelease = false`;
- axis enable/invert, deadzone, per-tick clamp, smoothing motion preservation;
- ADS multiplier; region hit testing; hostile config sanitization;
- raw-gyro integration signs and duplicate-sample rejection;
- explicit calibration acceptance/rejection;
- quaternion helpers;
- gyro + touchpad summation through one `MotionAccumulator`.

`ProfileJsonTest` covers gyro/cell round-trip, legacy profiles without a gyro
object, and hostile import clamping.

Not yet verified: physical-device sensor rates, real drift, in-game feel,
touchpad+gyro hybrid aiming while firing, and behavior across all four screen
orientations. Those are Milestone 7 device tests; the math is synthetic-input
tested only. The original plan mentioned a one-euro filter and
`SensorManager.remapCoordinateSystem`; the implementation uses a simpler
time-constant low-pass and derives yaw/pitch from the screen normal, which is
already invariant to portrait/landscape rotation.
