# Milestone 5 report — Advanced controls

**Status: complete (JVM-tested; on-device macro validation pending).**

Milestone 5 adds a bounded, phone-side macro system with a dedicated editor,
plus the gameplay diagnostics HUD. Macros expand into ordinary input deltas on
the phone, so the receiver gained no new input capability and the wire protocol
is unchanged.

## What was implemented

### 1. Macro model and safety limits

- **`profile/ProfileModel.kt`**
  - `CellType.MACRO` and `CellConfig.macro`.
  - `MacroStepType`: `TAP`, `HOLD`, `RELEASE`, `WAIT`.
  - `MacroStep`: keys, hold duration, delay after the step.
  - `MacroConfig`: ordered steps + repeat count.
  - `MacroLimits`: 24 steps, 4 keys per step, 2 s hold, 2 s delay, ×10 repeats,
    15 s total. `sanitized()` filters invalid evdev codes, drops excess keys,
    converts keyless non-wait steps to `WAIT`, clamps timings/repeats, trims
    steps that exceed the total cap, and reduces repeats until the plan fits.
  - `isRunnable()` requires at least one `TAP` or `HOLD` step, so a macro that
    only waits or only releases cannot start.
- **`profile/ProfileJson.kt`**
  - Optional `"macro": {"repeatCount": N, "steps": [...]}` object per cell.
  - Import filtering uses the same limits; legacy profiles and macro cells
    without a macro object decode to an empty, safe `MacroConfig`.
  - Additive schema change: files remain schema v1 and old profiles load
    unchanged.

### 2. Macro runtime

- **`input/MacroEngine.kt`**
  - Runs plans on the Compose/UI dispatcher in the app scope.
  - Every press/release goes through `InputTracker`, preserving reference
    counting, snapshots and release-all safety.
  - `TAP` presses the chord, waits `holdMs`, releases in reverse key order.
  - `HOLD` leaves the chord held; a later `RELEASE` releases the newest held
    action with exactly the same keys. Any remaining holds are released when
    the macro ends.
  - `WAIT` pauses without touching input state.
  - `repeatCount` repeats the full sequence.
  - Tapping a running macro cell cancels it immediately; `cancel()` releases
    held keys synchronously. Entering the editor, opening settings, switching
    profiles, disconnecting, backgrounding the app and leaving gameplay all call
    `cancelAll()`.
  - A runtime `withTimeout` (cap + 2 s) is a second line of defence; a
    run-generation token prevents an old cancelled coroutine from touching a
    newly started macro for the same cell.
  - Zero-delay plans are launched lazily so the running state is published
    before the body can finish.

### 3. Macro editor

- **`ui/MacroEditorDialog.kt`**
  - Compact step list with type/summary, reorder up/down and delete.
  - Add Tap/Hold/Release/Wait, with sensible default keys.
  - Dedicated step editor: type switch, key chord picker (reuses the existing
    searchable combo UI), tap-hold slider and delay slider.
  - Repeat slider (×1–×10), live estimated duration, step/limit counters.
  - Save is disabled for empty/non-runnable plans and for plans over 15 s; a
    clear message explains how to reduce the plan.
- **`ui/CellEditorDialog.kt`**
  - Two-row, six-type selector (`Key`, `Combo`, `Mouse`, `Joystick`,
    `Touchpad`, `Macro`).
  - Macro configuration card with summary and “Create/Edit Macro…”.
  - Macro settings persisted with the cell.

### 4. Diagnostics HUD

- **`ui/PlayScreen.kt`** — “Stats” toggle in the gameplay bar opens a compact
  overlay showing:
  - paired/disconnected state and PC name,
  - RTT from the TCP PING/PONG probe,
  - receiver input rate (`events/s`),
  - cumulative receiver packet drops and the 1 Hz drop delta,
  - derived quality (`Good`/`Fair`/`Poor`/`measuring`/`offline`),
  - currently held keys and mouse buttons from `InputTracker`,
  - number of running macros.
- The overlay does not replace the gameplay canvas and can be dismissed without
  interrupting input.
- `PlayScreen` uses `DisposableEffect` to cancel macro jobs if the screen is
  disposed, preventing work against a closed connection.

### 5. Tests

- **`profile/MacroTest.kt`** (8): duration math, clamping of keys/timings/
  repeats, duration-cap trimming, WAIT normalization, runnable rules.
- **`input/MacroEngineTest.kt`** (8): tap chord order and reverse release,
  Hold→Tap→Release sequencing, repeats, immediate cancel, cancel-all,
  reference counting alongside a touch-held key, empty macro and duplicate
  start rejection.
- **`profile/ProfileJsonTest.kt`** (+3): macro round-trip, legacy macro cell
  with no object, hostile macro clamping.
- **`profile/ProfileModelTest.kt`** unchanged; order-preserving cell updates
  still covered.

## Files created or modified

```text
android-app/app/src/main/java/com/phonecontroller/app/profile/ProfileModel.kt
android-app/app/src/main/java/com/phonecontroller/app/profile/ProfileJson.kt
android-app/app/src/main/java/com/phonecontroller/app/input/MacroEngine.kt          (new)
android-app/app/src/main/java/com/phonecontroller/app/ui/MacroEditorDialog.kt       (new)
android-app/app/src/main/java/com/phonecontroller/app/ui/CellEditorDialog.kt
android-app/app/src/main/java/com/phonecontroller/app/ui/PlayScreen.kt
android-app/app/src/test/java/com/phonecontroller/app/profile/MacroTest.kt          (new)
android-app/app/src/test/java/com/phonecontroller/app/input/MacroEngineTest.kt      (new)
android-app/app/src/test/java/com/phonecontroller/app/profile/ProfileJsonTest.kt
docs/MILESTONE-5.md                                                                 (new)
docs/PLAN.md
README.md
ARCHITECTURE.md
```

No Rust or protocol changes were required.

## Build and test results

Date: 2026-11-17.

```text
# Android JVM tests, APK and lint
cd android-app && ./gradlew testDebugUnitTest assembleDebug lintDebug
  BUILD SUCCESSFUL
  96 tests, 0 failures across 14 classes
  lintDebug: 0 errors (11 dependency/target-version warnings)
  app-debug.apk: 12 MB

# Rust workspace (unchanged by this milestone)
cd pc-receiver && cargo test --workspace --no-fail-fast
  56 passed, 0 failed
cargo clippy --workspace --all-targets -- -D warnings
  0 warnings
```

Test class totals: ProtocolCodec 16, ProfileJson 13, TouchpadMapper 11,
InputTracker 9, MacroTest 8, MacroEngine 8, CryptoBehaviour 7, CompatVectors 7,
Discovery 5, ConnectionRtt 4, ProfileModel 3, Watermark 3, RealReceiverE2E 1,
PairingIntegration 1.

## Example: Shift+W macro

1. Edit layout → add/select a cell → **Macro**.
2. **Create Macro…**
3. Add **Hold** with `SHIFT` (default).
4. Add **Tap** with `W`, hold 40 ms.
5. Add **Release** with `SHIFT`.
6. Save Macro → Save Control → Save & Exit.
7. Tap the macro in gameplay. Tap it again while running to cancel; all held
   keys are released immediately.

## Known limitations

- **On-device validation pending.** The phone was not attached during this
  pass. The macro engine and model are JVM-tested end-to-end, but the editor UI,
  macro timing on hardware and evtest output have not been captured.
- A `RELEASE` step releases only a macro-held action with exactly the same key
  set. It cannot release a key held by a touch cell (by design, to preserve
  reference counts).
- Macros are finite sequences; there is deliberately no loop/condition
  construct. Repeats are capped at 10 and total runtime at 15 s.
- Macro execution stays phone-side; there is no receiver-side macro language or
  remote macro upload. This keeps the PC side free of automation logic.
- Sensor update rate is not shown yet because gyro sensors are Milestone 6.

## Exact commands to run

```bash
# PC receiver
cd pc-receiver
cargo build --release
./target/release/phonecontroller-receiver --pair

# Android build/install
cd android-app
./gradlew testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Manual test on hardware:

```bash
# Watch the receiver's virtual devices while running a macro.
sudo evtest /dev/input/eventN     # keyboard
# Expect SHIFT down, W down, W up, SHIFT up (and no stuck keys on cancel).
```
