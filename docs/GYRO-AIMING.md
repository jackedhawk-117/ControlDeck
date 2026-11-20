# Gyro aiming — implementation reference (Milestone 6)

> **Status: implemented and JVM-tested; live device telemetry and an end-to-end
> horizontal sweep are verified, but far-edge pitch and the full physical
> acceptance sequence are still pending.** The wire protocol is unchanged: raw
> sensor data never leaves the phone; only the resulting Q16.16 mouse deltas are
> transmitted.

The goal is console-style motion aiming for a phone held approximately flat,
screen up: large camera movement on the touchpad, fine aim with the gyroscope,
both feeding one mouse pipeline.

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
  world heading and tilt of the phone's physical short axis. It never drifts.
- **GYRO (optional)** — uses the calibrated device-frame angular velocity,
  rotates it into the world frame with the current orientation, and tracks the
  same short-axis heading/tilt at the input tick rate. It is smoother on phones
  with a slow rotation-vector sensor but drifts until the next recenter.
  Duplicate/stale sensor samples are ignored, so a stalled sensor cannot keep
  integrating forever.

Both sources require an orientation sensor for a stable yaw/pitch reference.
Phones without one show gyro as unavailable instead of guessing from the
accelerometer.

## 2. Orientation math

The player holds the phone approximately flat, screen up, in landscape. The
mapper tracks the **physical short axis** (+X) of the phone, not the top edge
and not the screen normal:

1. Convert Android's `(x, y, z, w)` rotation vector to a normalized quaternion
   `q` (device → world, world Z up).
2. Rotate the physical short axis by `q`: `f = q · x̂` (world coordinates).
3. `yaw = −atan2(f.y, f.x)` — heading about the gravity-aligned world Z axis.
   A right rotation is positive and maps to positive mouse `dx`.
4. `pitch = pitchSign · asin(f.z)` — tilt of the short axis. Lifting the far
   edge raises `f.z`; `pitchSign = +1` when the display is
   `Surface.ROTATION_90` (normal landscape, short axis points away from the
   player) and `−1` at `ROTATION_270` (reverse landscape).
5. Per new orientation sample, take wrapped differences
   `dYaw = wrapPi(yaw − lastYaw)` and `dPitch = pitch − lastPitch`, divide by
   the sensor-timestamp interval, then convert to mouse pixels.

`PlayScreen` registers a `DisplayManager.DisplayListener` and calls
`GyroMapper.setDisplayRotation(display.rotation)` whenever the surface rotates.
The math always uses the physical device axes; this only corrects the facing of
the pitch gesture in reverse landscape. A mid-session rotation change re-primes
the mapper, so the sign switch cannot become a one-frame camera jump.

For `q = Rz(yaw)·Ry(pitch)·Rx(roll)`, the pair above is exact (verified to
float precision over ±170° yaw and ±80° pitch with arbitrary roll). A rotation
about the short axis (`Rx`) leaves `f` invariant, so that roll is ignored
exactly. A rotation about the long axis (`Ry`) changes only the tilt, and a
rotation about the world vertical (`Rz`) changes only the heading.

This model replaced an earlier top-edge scheme. The vertical phyphox capture is
97.7% rotation about **device Y** (the long edge). The top-edge vector lies
along that rotation axis, so it barely moves during the real pitch gesture; the
old model could not see it. The short axis is perpendicular to the gesture and
tilts by exactly the pitch angle, which is why the current model tracks it.

The neutral pose is a **relative baseline anchored at recenter**: the mapper
stores yaw and pitch at the current pose and emits only differences. Absolute
world heading and the grip's resting tilt therefore do not matter. The 18.7°
and 23.2° principal-axis offsets in the horizontal captures are a constant
far-edge-up hold (a pitch offset about the long axis), not roll; they baseline
out and cannot leak into yaw because yaw is measured about gravity. The JVM
regressions `flat yaw from a tilted neutral does not leak into pitch`,
`pitch from a tilted neutral changes only the vertical axis` and the 23.1°
alternating-sweep test pin this down.

**Requirement conflict, resolved deliberately.** With the phone flat, a twist
about the screen normal *is* the required left/right yaw, and it is physically
the same motion as yaw about gravity. It cannot also be ignored. The ignored
axis is roll about the short axis, which is not a useful aiming gesture.

Near the poles (`|f.xy| < 0.08`, i.e. the short axis within ~4.6° of vertical)
heading is ill-defined. The mapper reports zero yaw for that step and for the
first step after leaving the pole, but still updates the baseline, so leaving
the pole does not release a large stored jump. `asin` represents pitch over
±90°; aiming past vertical is outside the usable range.

The FUSED source uses sensor timestamps for `dt` (clamped to 2–200 ms). The
GYRO source rotates angular velocity into world space with the current
quaternion, forms `df/dt = ω × f`, and uses the analytic rates
`yawRate = (f.y·df.x − f.x·df.y) / |f.xy|²` and
`pitchRate = pitchSign · df.z / |f.xy|`. It integrates with the raw gyro sample
timestamp (clamped to 1–50 ms).

Recenter stores the current pose as the new baseline. The next sample produces
no delta, for both sources.

## 3. Activation modes

| Mode | Behavior |
|---|---|
| Always (default) | Contributes whenever a paired session is in gameplay and this profile enables gyro; releasing RMB does not stop it |
| ADS only | Contributes only while the logical right mouse button (ADS) is held; touchpad input keeps working when it is released |
| Hold | Contributes only while a `GYRO` activation cell is held |
| Toggle | A `GYRO` activation cell latches aiming on or off |
| Region | Contributes while at least one finger is inside the configured canvas rectangle |

ADS-only reads `InputTracker.isMouseButtonHeld(MOUSE_RIGHT)`, not a cell id.
The tracker reference-counts the button across every source, so a touchscreen
aim button, a key combination that contains RMB and a macro all activate gyro,
and releasing one source does not disable it while another still holds RMB.
The right button is also what applies the ADS sensitivity multiplier in the
other modes; it never gates Always On.

Region detection observes pointer events in the **Initial** pass and never
consumes them, so a finger inside the zone still presses controls underneath
(or drives the camera touchpad). The rectangle is drawn as a translucent
`GYRO ZONE` overlay in gameplay.

Opening the editor, either tuning screen, the profile manager, or disconnecting
disarms gyro. A profile switch resets hold/toggle/region state. Activation and
deactivation always re-prime the mapper (unless "re-prime neutral pose after
hold release" is explicitly disabled), so transitions do not cause a camera
jump. ADS entry is stricter: it always re-primes, even when
`recenterOnRelease = false`, because otherwise the pose change accumulated
while ADS was released would replay as a jump. The tuning overlay keeps the
mapper running for preview but bypasses the ADS gate and never injects.

The `recenterOnRelease` option controls what happens to the neutral pose when
aiming is disarmed:

- **on (default)** — forget the old pose; the next activation is neutral
  wherever the phone is held.
- **off** — keep the old pose; re-arming reflects orientation changes made while
  disarmed. This is intentionally a jump, bounded by the per-tick clamp. In
  ADS-only mode the explicit ADS-entry re-prime overrides this for safety.

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
including a toggle. The same logical state arms the mapper in ADS-only mode;
the decision is re-read on every 120 Hz tick, so release stops gyro output on
the next tick without waiting for a Compose recomposition. The ADS multiplier
is applied only to gyro sensitivity; touchpad sensitivity is unchanged. In
Always On, RMB only changes that multiplier and never disables gyro.

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
| `activationMode` | `ALWAYS` | `ALWAYS`, `ADS_ONLY`, `HOLD`, `TOGGLE`, `REGION` |
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
always-on gyro with 2.6/2.2 px/° sensitivity.

A profile or exported JSON without `activationMode` decodes as `ALWAYS`; this
includes profiles written before ADS-only existed. Profiles that explicitly
stored `HOLD`, `TOGGLE` or `REGION` keep that behavior. Serialization writes the
enum name, so `ADS_ONLY` survives export/import and profile switching.

Imports are sanitized (finite values, clamped ranges, enum fallback, region
kept inside the canvas). Hostile values cannot produce NaN deltas or an
out-of-bounds region.

## 7. UI

- **Play screen** top bar: `Gyro` button opens tuning; it shows `Gyro ●` while
  aiming is active.
- **Gyro settings screen**: source availability and live sensor rates, enable
  toggle, activation/source chips (Always on, ADS only, Hold, Toggle, Region),
  all sliders and toggles, region rectangle controls, sensor explanation,
  calibration status/value, recenter and reset. Status distinguishes disabled,
  aiming active, ADS-not-held and live tuning preview. The mapping text states
  that short-axis roll is ignored by construction, so there is no misleading
  roll toggle.
- **Cell editor**: a `Gyro` type with `Arm gyro` / `Recenter` action cards.
- **Grid editor**: `+ Gyro` adds an activation cell.
- **Diagnostics HUD**: gyro on/off/armed/aiming state, activation mode, logical
  ADS state, active source, live gyro/orientation sample rates, yaw/pitch rates
  and per-tick output. ADS-only shows `inactive · ADS not held` explicitly.
- **Overlays**: tune while the mapper runs as a non-injecting preview; the
  preview deliberately ignores the ADS gate so ADS-only can be tuned without
  holding RMB.

## 8. Testing and limitations

Automated validation (all green at the time of writing):

- Android JVM: **153 tests**, `assembleDebug`, `lintDebug`.
- Rust receiver: **56 tests**, `cargo clippy --workspace --all-targets -D warnings`.

`GyroMapperTest` (52 tests) covers:

- short-axis yaw/pitch signs in normal and reverse landscape;
- a 60° sweep about the long edge producing pitch only (the vertical capture);
- roll about the short axis ignored at identity and from an arbitrary
  yaw+pitch pose;
- yaw purity from a tilted neutral (19°/23.1° matching the horizontal captures)
  and pitch from a tilted neutral;
- six alternating ±140° sweeps at the 23.1° tilt measured in the second
  capture, with accumulated pitch held at zero;
- landscape grip, combined yaw+pitch, a 40-step coupled yaw+pitch sweep,
  heading wrap and pole exit;
- recenter and activation with no jump, including after a session
  disconnect/reconnect; `recenterOnRelease = false`;
- Always On without ADS, ADS-only gating from the logical right button,
  independence from hold/toggle/region inputs, ADS release, and ADS re-entry
  after a disarmed pose change with no replay jump;
- FUSED and GYRO totals independent of the sensor sample rate, plus the
  duplicate/stall gap guards;
- axis enable/invert, deadzone, per-tick clamp, smoothing motion preservation;
- ADS multiplier and proof that the touchpad contribution is not scaled;
- region hit testing; hostile config sanitization;
- raw-gyro yaw/pitch signs, rolled-grip yaw purity, duplicate/stall handling;
- explicit calibration acceptance/rejection; quaternion helpers;
- gyro + touchpad summation through one `MotionAccumulator`.

`ProfileJsonTest` (17 tests) covers gyro/cell round-trip, legacy profiles
without a gyro object, ADS-only round-trip, missing-mode migration to `ALWAYS`,
and hostile import clamping.

### phyphox captures

All four captures run at ~397 Hz. Energy is the share of rotational variance on
each device axis; the principal axis is given in device coordinates.

| Capture | Length | X / Y / Z energy | Principal axis (angle from Z) | Interpretation |
|---|---|---|---|---|
| `left movement.xlsx` | 14.1 s, 5,604 | 10.5 / 2.9 / 86.7% | (0.306, −0.097, 0.947), 18.7° | yaw through a ~19° far-edge-up grip |
| `horizontal movement.xlsx` | 34.3 s, 13,600 | 16.2 / 3.5 / 80.3% | (0.394, −0.029, 0.919), 23.2° | 22 alternating strokes (+132°±40 left, −142°±30 right, 1.3–1.5 s each); `gx`–`gz` r=0.93 at zero lag |
| `verticalmovement.xlsx` | 69.1 s, 27,413 | 1.5 / **97.7** / 0.8% | (−0.022, −1.000, 0.003), 89.8° | far-edge up/down is a clean rotation about device Y; the decisive capture for the short-axis model |
| `combined horizontal and vertical.xlsx` | 38.0 s, 15,079 | 20.1 / 46.1 / 33.9% | (0.047, −0.993, 0.104), 84.0° | concurrent yaw and pitch, still dominated by long-axis rotation; xy −0.06, xz +0.41, yz −0.02 |

Device evidence:

- live sensor mailbox at ~199 Hz gyro / ~100 Hz orientation;
- a 200 s end-to-end evtest recording on the virtual device captured 14,463
  `REL_X`/`REL_Y` events. The first activity cluster (16.5 s of left rotation)
  produced −597 px horizontal and +20 px vertical, i.e. ~3% leakage; no
  vertical or combined gesture was captured in that pass.

Not yet verified: Always On and ADS-only activation **end to end** on hardware,
the far-edge-up/down and combined gestures end to end, reverse-landscape
physical repeat, recenter at a new tilted pose, in-game feel, hybrid aiming
while firing, drift/long-session soak, Steam/Proton and Windows runtime. The
2026-11-19 pitch captures recorded zero motion because the phone was stationary,
so the pitch sign is still backed by synthetic tests rather than a clean live
run. Those remain Milestone 7 device tests.
`SensorManager.remapCoordinateSystem` is unnecessary: the mapper uses the
physical device axes and only uses the display rotation to select the pitch
sign.
