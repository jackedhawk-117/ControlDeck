# Milestone 4 report — Customizable Canvas Editor, Full Keyboard Mapping & Game Profiles

**Status: complete.**

Milestone 4 introduces an unconstrained, mobile-gaming style screen canvas (inspired by mobile shooter layout editors like PUBG / COD Mobile), full-keyboard keycode mapping (>100 standard PC keys), virtual analog WASD joystick with 8-way directional chording and sprint threshold, strict separation between layout-editing mode and gameplay mode, per-profile persistence using a portable, versioned JSON format, a full in-app profile manager (create, duplicate, import, export, switch), bundled default profiles for multiple gaming genres, and automatic receiver-side `RESET` and `PROFILE_INFO` synchronization upon profile switching.

---

## What was implemented

### 1. Unconstrained Mobile-Gaming Canvas & Freeform Editor

- **Screen Canvas Architecture**:
  - The entire phone screen is an unconstrained canvas where buttons and analog controls can be positioned anywhere.
  - Normalized coordinates (`xPct`, `yPct` from 0.0 to 1.0) ensure identical layout geometry across different smartphone screen sizes and aspect ratios (16:9, 19.5:9, 20:9).
  - Backward compatibility: legacy profiles with zero coordinates automatically calculate distributed positions via `normalizedCells()`.

- **Layout-Editing Mode (`GridEditorScreen.kt`)**:
  - Dedicated full-screen drag-and-drop editor.
  - Controls can be dragged directly on the screen in real-time with finger tracking.
  - Visual selection state with active highlight bounding box.
  - Quick action floating bar: Add Button, Add WASD Joystick, Add Touchpad, Undo (up to 25 states), Reset to Defaults, Cancel, and Save & Exit.
  - Floating Inspector bar when a control is selected:
    - Quick shape toggling: Rounded Rectangle, Circle, Pill.
    - Quick resize buttons: `- Size` / `+ Size`.
    - Duplicate button (creates an offset clone).
    - Delete button.
    - Configure button (opens full modal).

- **Button Shape & Dimension Customization**:
  - Shapes: `ROUNDED_RECT`, `CIRCLE`, `PILL`.
  - Independent width and height controls in dp (40dp to 320dp for buttons;
    touchpad cells expose 60dp to 320dp dimensions).
  - Opacity slider (20% to 100%).
  - Tint palette (`customColor`): theme-default plus eight accent colors,
    applied in both gameplay and the editor preview with luminance-aware text.

### 2. Full Standard PC Keyboard Mapping & Searchable Picker

- **Comprehensive Canonical Keycodes (`Keycodes.kt`)**:
  - Expanded to over 100 standard PC keyboard keys with canonical Linux evdev codes:
    - Letters A–Z.
    - Digits 0–9 (main row).
    - Full numeric keypad (Numpad 0–9, Enter, +, -, *, /, Dec, NumLock).
    - Navigation cluster (Arrows, Home, End, PgUp, PgDn, Insert, Delete).
    - Full punctuation & symbol keys (`~`, `-`, `=`, `[`, `]`, `\`, `;`, `'`, `,`, `.`, `/`).
    - Function keys F1–F12.
    - Modifiers & system keys (Left/Right Shift, Ctrl, Alt, Meta/Win, CapsLock, Tab, Esc, Enter, Bksp, PrtSc, ScrollLock, Pause).
    - Media controls (Volume Up/Down/Mute, Play/Pause, Next, Prev).

- **Searchable Key Picker (`CellEditorDialog.kt`)**:
  - Live search input: users can type any query (e.g., "Enter", "Numpad 5", "F11", "Shift") to instantly filter all keys.
  - Horizontal scrolling category filter chips: "All", "Letters", "Movement", "Actions", "Numbers", "Numpad", "Function", "Modifiers", "Navigation", "Symbols", "System & Media".
  - Scrollable grid displaying keys with clear labels and descriptive tooltips.
  - Combination editor supports six common presets **and** an arbitrary searchable
    key picker (up to four keys), with removable chips and reverse-order release.

### 3. Virtual Analog Joystick (WASD + Sprint)

- **`VirtualJoystick.kt`**:
  - High-performance, touch-tracked analog thumbstick with circular base and moving thumb knob.
  - Deadzone detection (<18% radius) ignores micro-jitter and centers cleanly.
  - 8-directional sectors: clean diagonal chording (W+A, W+D, S+A, S+D) matching PC keyboard input.
  - Outer sprint zone (>85% forward displacement) activates sprint (`KEY_LEFTSHIFT`) **only when enabled for that joystick**. Each joystick has its own `joystickSprint` flag (default on); the editor exposes a “Sprint at Full Deflection” switch, and the runtime suppresses Shift entirely when off.
  - Independent pointer tracking: one thumb can steer the joystick while other fingers tap, hold, or drag other controls.
  - Springs back to center immediately on release, emitting all corresponding keyUp deltas.

### 4. Game Profile Architecture & Portable JSON Format

- **`ProfileModel.kt`**:
  - `GameProfile`: Encapsulates freeform cells, button spacing, corner radius, opacity, touchpad configuration, schema version, and cell definitions.
  - `CellConfig`: Stores `xPct`, `yPct`, `widthDp`, `heightDp`, `shape`, `opacity`, custom label, action type (`KEY`, `KEY_COMBO`, `MOUSE_BUTTON`, `JOYSTICK`, `TOUCHPAD`, `EMPTY`), evdev key list, mouse button list, latching toggle flag, per-joystick sprint flag, and optional tint.
  - `toControlAction()`: Transforms user-configured cell definitions into runtime `ControlAction` instances.

- **`ProfileJson.kt`**:
  - Lightweight recursive-descent JSON parser and AST serializer with zero external dependencies.
  - Full roundtrip serialization for individual profiles and profile lists (`serialize`, `serializeList`, `parse`, `parseList`).
  - `joystickSprint` is serialized per cell and defaults to `true` when absent, so profiles written before the option existed keep sprint enabled.

- **`DefaultProfiles.kt`**:
  Pre-configured, ergonomic mobile canvas layouts:
  1. **First-Person Shooter (FPS)**: WASD Virtual Joystick, Sprint (`Shift`), Crouch (`C`), Prone (`Z`), Reload (`R`), Jump (`Space`), Fire (`LMB`), Aim (`RMB` toggle), Interact (`E`), Melee (`F`), MMB.
  2. **Third-Person Action**: WASD Joystick, Dodge/Roll (`Space`), Attack (`LMB`), Heavy (`RMB`), Lock-On (`Q`), Use (`E`), Heal (`1`), Inventory (`Tab`).
  3. **Driving & Racing**: Gas (`W`), Brake/Reverse (`S`), Steer Left/Right (`A`/`D`), Handbrake (`Space`), Nitro (`Shift`), Rear View (`B`), Reset (`R`).
  4. **Strategy / RTS**: Control Groups 1–4, Attack Move (`A`), Stop (`S`), Build (`B`), Queue (`Shift`), Select (`LMB`), Command (`RMB`).
  5. **General WASD + Mouse**: Versatile baseline layout with analog stick and primary controls.

- **`ProfileStore.kt`**:
  - Persistent storage manager using `profiles_v1.json` in internal storage.
  - Supports creating, duplicating, importing, exporting (clipboard), and resetting profiles.

### 5. Multitouch Gameplay Mode Screen (`PlayScreen.kt`)

- **Full-Screen Canvas**:
  - Renders all controls at their exact normalized coordinates.
  - Supports hybrid camera aiming: touches on empty space on the right side smoothly drive the camera touchpad without interfering with buttons.
  - Each button uses independent pointer tracking; a control is released only when the last finger on it lifts, even if another finger still holds the same control.
  - Compact HUD top bar displays connection status, RTT, event rate, and quick access to Edit Layout, Touchpad Settings, and Profile Manager.
  - Entering the editor, touchpad settings, or profile manager first releases all held keys/buttons and sends a receiver RESET, so replacing the gameplay canvas can never leave a stuck input.

---

## Post-review hardening (2026-11-16)

The user-contributed profile/editor/joystick layer was audited after Milestone 4
and the following correctness issues were fixed:

| Issue | Fix |
|---|---|
| Held inputs could remain stuck when an overlay replaced the canvas | `releaseAllInputs()` runs before every mode transition; `onUp` no longer depends on editor state |
| A key-up launched in a new coroutine could overtake its key-down | `PhoneConnection.enqueueDelta()` is synchronous on one UNLIMITED UDP FIFO; snapshots share the same ordering |
| Dragging a control snapped toward its start position and created one undo entry per pointer event | Drag reads the current cell from state each event and records history once per gesture |
| Imported JSON could inject oversized dimensions, NaN/Infinity, invalid keycodes, duplicate IDs, or trailing garbage | `ProfileJson` clamps every field, filters codes, regenerates duplicate/blank IDs, bounds text, and rejects trailing data; `TouchpadConfig.sanitized()` handles non-finite values |
| A crash during profile save could truncate `profiles_v1.json`; a corrupt file was silently overwritten | Temp-file + atomic rename, with corrupt-file backup and logging |
| Editing a non-active profile desynchronized the stored active ID | The selected profile is made active before the editor opens |
| Touchpad sliders rewrote the profile file on every tick | 400 ms debounce plus flush on close |
| A second finger on the same button was ignored; lifting the first released the input | `CanvasButton` tracks a pointer set and releases on the last finger |

---

## On-device validation and drag-slot fix (2026-11-16, second session)

Fresh APK installed on `ZA223M8MVQ` and paired with the receiver (session
`4040182976`, 6 ms TCP control-plane RTT). The receiver's uinput device was
captured with `evtest`:

- `BTN_LEFT` down/up for a 1.2 s FIRE hold; `BTN_RIGHT` and `BTN_MIDDLE` each
  produced two complete down/up pairs.
- Joystick deflections from the true centre produced exactly `KEY_W`, `KEY_S`,
  `KEY_A`, `KEY_D` in turn; full forward deflection added `KEY_LEFTSHIFT`.
- Touchpad swipes produced `REL_X`/`REL_Y` with the correct signs in both
  directions.
- Holding W and tapping `✏️ Edit Layout` released `KEY_W`, opened the editor, and
  made the receiver log `RESET: releasing held inputs`.

That pass exposed a drag defect not covered by JVM tests: `withCellUpdated()`
removed the edited cell and appended it to the end of the list. Because the
editor renders cells positionally, the dragged cell changed Composition slot
after the first `onDrag`, disposing its pointer coroutine; a 150 px swipe moved
the control only ~20 px. The fix replaces the cell in place (unknown ids are
still appended), with new `ProfileModelTest` coverage:

- in-place update preserves id order,
- 20 consecutive drag updates keep the composition slot stable,
- unknown ids are appended rather than dropped.

Re-tested on device after the fix: a 150 px drag applies ≈133 px (touch slop is
consumed), Undo restores the previous bounds exactly, and Cancel discards the
edit. A later pass verified Save & Exit plus cold-restart persistence (edited
coordinates and the sprint flag reloaded from `profiles_v1.json`), and the
per-joystick sprint switch end-to-end: disabled → full forward deflection
produced `KEY_W` only; enabled → `KEY_W` + `KEY_LEFTSHIFT`. The phone also
detected the PC while the PC was joined to the phone's Wi-Fi hotspot after the
multi-interface discovery change (Milestone 2 update).

---


## Files created or modified

```text
# UI Layer
android-app/app/src/main/java/com/phonecontroller/app/ui/VirtualJoystick.kt
android-app/app/src/main/java/com/phonecontroller/app/ui/GridEditorScreen.kt
android-app/app/src/main/java/com/phonecontroller/app/ui/CellEditorDialog.kt
android-app/app/src/main/java/com/phonecontroller/app/ui/PlayScreen.kt
android-app/app/src/main/java/com/phonecontroller/app/ui/ProfileManagerDialog.kt
android-app/app/src/main/java/com/phonecontroller/app/ui/Theme.kt

# Input & Keycodes
android-app/app/src/main/java/com/phonecontroller/app/input/Keycodes.kt
android-app/app/src/main/java/com/phonecontroller/app/input/InputTracker.kt
android-app/app/src/main/java/com/phonecontroller/app/input/TouchpadMapper.kt

# Networking
android-app/app/src/main/java/com/phonecontroller/app/net/Connection.kt

# Profile & Model Layer
android-app/app/src/main/java/com/phonecontroller/app/profile/ProfileModel.kt
android-app/app/src/main/java/com/phonecontroller/app/profile/ProfileJson.kt
android-app/app/src/main/java/com/phonecontroller/app/profile/DefaultProfiles.kt
android-app/app/src/main/java/com/phonecontroller/app/profile/ProfileStore.kt

# Tests
android-app/app/src/test/java/com/phonecontroller/app/profile/ProfileJsonTest.kt
android-app/app/src/test/java/com/phonecontroller/app/profile/ProfileModelTest.kt
android-app/app/src/test/java/com/phonecontroller/app/net/DiscoveryTest.kt
android-app/app/src/test/java/com/phonecontroller/app/input/TouchpadMapperTest.kt
```

---

## Build and test results

Date: 2026-11-16 (hardening review).

```text
# Android JVM Unit Tests + APK
cd android-app && ./gradlew testDebugUnitTest assembleDebug lintDebug
  BUILD SUCCESSFUL
  77 tests, 0 failures across 12 classes (InputTracker, TouchpadMapper,
  CompatVectors, ConnectionRtt, CryptoBehaviour, PairingIntegration,
  ProtocolCodec, RealReceiverE2E, Watermark, ProfileJson, ProfileModel,
  Discovery)
  lintDebug: 0 errors (remaining warnings are dependency/target-version notices)

# Rust Workspace
cargo test --workspace --no-fail-fast
  56 passed; 0 failed (input-core: 13, input-linux: 5, input-windows: 4,
  pc-protocol: 22, pc-receiver integration: 12)
cargo clippy --workspace --all-targets -- -D warnings
  0 warnings
```

Device note: mouse buttons, joystick axes/sprint, touchpad motion,
release-on-mode-change, editor drag/undo/cancel, Save & Exit + relaunch
persistence, and hotspot discovery are now device-verified. Still pending:
profile switching, touchpad-settings persistence, hotspot input/latency
capture, true two-finger chording, reconnection drills and soak testing.
Windows `SendInput` remains runtime-blocked (no Windows machine).

---

## Exact commands to run

```bash
# Start PC receiver:
cd pc-receiver
./target/release/phonecontroller-receiver --pair

# Run Android unit tests:
cd android-app
./gradlew testDebugUnitTest

# Build and install to phone:
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.phonecontroller.app/.MainActivity
```
