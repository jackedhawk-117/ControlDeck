# Project: Custom Smartphone-to-PC Gaming Input Mapper

## Role

Act as a senior software architect, Android developer, Rust systems engineer, UI/UX designer, and cross-platform input systems engineer.

Your task is to design and build a complete, extensible application that turns an Android smartphone into a customizable wireless input device for PC games.

The application must work with:

- Windows PC games.
- Linux native games.
- Linux games running through Steam Proton.
- Games that support keyboard and mouse but do not support conventional game controllers.

Do not build a conventional Xbox or PlayStation controller emulator. The primary purpose is to translate smartphone touchscreen and motion inputs into PC keyboard and mouse events.

The project must be maintainable, modular, secure, low-latency, and suitable for long-term development.

---

# 1. Core Architecture

The system consists of two applications.

### A. Android application

Responsible for:

- Rendering a customizable touchscreen control surface.
- Processing multitouch input.
- Reading gyroscope and orientation sensors.
- Managing game-specific profiles.
- Sending input events to the PC.
- Discovering and connecting to PC receivers.
- Providing configuration and calibration interfaces.

### B. PC companion application

Responsible for:

- Receiving input events from Android.
- Managing connected devices.
- Translating input packets into operating-system input events.
- Managing keyboard and mouse states.
- Executing macros.
- Handling disconnections and releasing stuck inputs.
- Providing a configuration and connection interface.

The Android application and PC receiver must communicate through a documented, versioned protocol.

---

# 2. Technology Stack

Use the following architecture unless investigation reveals a concrete technical limitation. Document any proposed deviations before implementing them.

## Android

- Kotlin.
- Jetpack Compose.
- Material 3.
- Android SensorManager.
- TYPE_GYROSCOPE.
- TYPE_ROTATION_VECTOR where appropriate.
- Kotlin Coroutines.
- UDP for low-latency input transmission.
- TCP for reliable configuration and control operations.

## PC receiver

- Rust.
- Tokio for asynchronous networking.
- Serde for serialization.
- Modular platform-specific input backends.

### Linux backend

Use Linux uinput to create virtual keyboard and mouse devices.

Requirements:

- Support CachyOS and Arch Linux.
- Support other mainstream Linux distributions where possible.
- Work under Wayland and X11 where supported by the input-device permissions and compositor.
- Provide clear instructions for uinput permissions.
- Avoid requiring the application to run permanently as root.
- Test keyboard and mouse events independently.
- Ensure Steam and Proton games can receive the injected events.

### Windows backend

Initially use the Windows SendInput API for keyboard and mouse event injection.

Requirements:

- Support Windows 10 and Windows 11.
- Handle keyboard modifiers correctly.
- Support mouse buttons, relative movement and scrolling.
- Handle input release on disconnect.
- Document limitations involving elevated applications, exclusive input and anti-cheat systems.

Keep the Linux and Windows backends separate behind a common Rust input abstraction.

Do not introduce virtual gamepad drivers or Xbox/PlayStation emulation.

---

# 3. Customizable Touchscreen Grid

The central feature is a configurable touchscreen grid.

Users must be able to configure the number of rows and columns.

Each cell must independently support different input types.

### Supported cell types

1. Keyboard key.
2. Keyboard key combination.
3. Mouse left click.
4. Mouse right click.
5. Mouse middle click.
6. Mouse wheel up/down.
7. Mouse movement region.
8. Virtual analog joystick.
9. Macro execution.
10. Toggle input.
11. Hold input.
12. Gyroscope activation.
13. Custom action.

Examples:

- W/A/S/D for movement.
- Space for jumping.
- Shift for sprinting.
- E for interaction.
- R for reloading.
- Left mouse button for firing.
- Right mouse button for aiming.

### Grid editor

Implement:

- Configurable rows and columns.
- Cell assignment.
- Cell resizing where supported.
- Custom labels.
- Adjustable opacity.
- Adjustable colors.
- Adjustable spacing.
- Button shape customization.
- Button positioning.
- Grid visibility controls.
- Per-profile layouts.
- Reset and undo functionality.

The application must have a layout-editing mode and a separate gameplay mode.

Gameplay mode should prioritize usable screen space and prevent accidental editing.

---

# 4. Multitouch Support

Multitouch is essential.

The user must be able to:

- Hold several keyboard buttons simultaneously.
- Move a joystick while holding other buttons.
- Aim using the gyroscope while firing.
- Use touchscreen camera movement while pressing other controls.
- Execute multiple independent actions concurrently.

Track individual pointer IDs.

Never allow one finger's release to accidentally release an input still held by another finger.

Implement reliable input-state tracking.

---

# 5. Mouse and Camera Controls

Support two complementary camera control methods.

## A. Touchpad camera movement

A designated touchscreen region acts like a laptop touchpad.

Requirements:

- Relative mouse movement.
- Adjustable X/Y sensitivity.
- Configurable acceleration.
- Smoothing.
- Optional inversion.
- Adjustable touchpad dimensions.
- Optional mouse-button activation.

The user must be able to move the camera while holding other touchscreen controls.

## B. Gyroscope camera movement

Implement console-style gyro aiming inspired by Nintendo Switch and PlayStation DualSense motion aiming.

This is a major feature, not an optional afterthought.

### Sensor processing

Use Android's gyroscope and orientation sensors.

The system must distinguish between:

- Yaw: horizontal rotation.
- Pitch: vertical rotation.
- Roll: rotation around the phone's forward axis.

Default configuration:

- Yaw controls horizontal camera movement.
- Pitch controls vertical camera movement.
- Roll is disabled by default.

Implement quaternion-based orientation tracking where appropriate.

Do not simply map raw accelerometer values to mouse movement.

### Gyro modes

1. Always active.
2. Active while holding an assigned touchscreen button.
3. Toggle activation.
4. Active only within a configured touchscreen region.

### Gyro configuration

Each game profile must support:

- Horizontal sensitivity.
- Vertical sensitivity.
- Axis inversion.
- Deadzone.
- Smoothing.
- Sensitivity scaling.
- Activation mode.
- Activation button.
- Recenter button.
- Calibration.
- Axis enable/disable.
- Optional sensitivity profiles for normal aiming and ADS.

### Recentring

Implement a dedicated recenter action.

The user must be able to establish a comfortable neutral phone orientation.

Automatic recentering when releasing the activation button should be configurable.

Avoid unwanted camera movement after activation and deactivation.

### Hybrid aiming

Support simultaneous gyro and touchpad camera input.

For example:

- Touchpad performs large camera movements.
- Gyroscope performs fine aiming adjustments.

Both sources must contribute to the same mouse movement pipeline without interfering with each other.

---

# 6. Input Protocol and Networking

Design a lightweight, versioned input protocol.

Requirements:

- UDP for frequent input updates.
- TCP for configuration, pairing and reliable control messages.
- Sequence numbers.
- Session identifiers.
- Connection heartbeat.
- Disconnect detection.
- Packet validation.
- Input-state synchronization.
- Full input-state reset on disconnect.
- Reconnection handling.
- Network latency monitoring.

Prefer compact binary encoding for frequent input packets.

JSON may be used for configuration and debugging.

Document every packet type and its fields.

Do not transmit unnecessary sensor or UI data.

### Device discovery and pairing

Implement:

- Automatic PC discovery on the local network.
- Manual IP connection.
- QR-code or PIN-based pairing.
- Multiple PC receiver support.
- Clear connection status.
- Basic authentication.
- Session isolation.

Never assume that the local network is trusted.

---

# 7. Game Profiles

Allow users to create, edit, duplicate, import and export game profiles.

Each profile must store:

- Grid dimensions.
- Cell positions.
- Cell assignments.
- Key combinations.
- Mouse settings.
- Joystick settings.
- Gyroscope settings.
- Touchpad configuration.
- Macros.
- Button appearance.
- Activation conditions.

Profiles must use a versioned, portable format such as JSON.

Allow profile switching without restarting the receiver.

Provide example profiles for:

- First-person shooters.
- Third-person action games.
- Open-world games.
- Racing games.
- Strategy games.
- General keyboard-and-mouse games.

Do not hardcode game-specific functionality into the core input system.

---

# 8. Macros and Advanced Actions

Implement an extensible action system.

Support:

- Sequential keyboard inputs.
- Multiple simultaneous inputs.
- Configurable delays.
- Repeated actions.
- Hold and release.
- Toggle actions.

Implement safety limits to prevent accidental infinite input loops.

Macros must be cancellable immediately.

Do not implement automated gameplay, cheating or anti-cheat bypass mechanisms.

---

# 9. UI/UX Design

The interface should feel like a dedicated gaming peripheral rather than a generic utility application.

Visual direction:

- Dark interface.
- Minimalist design.
- Modern gaming aesthetic.
- High contrast.
- Clean typography.
- Smooth transitions.
- Configurable accent colors.

Primary screens:

1. Home / connection screen.
2. PC discovery and pairing.
3. Controller gameplay screen.
4. Grid editor.
5. Cell configuration.
6. Gyroscope configuration.
7. Touchpad configuration.
8. Game profile manager.
9. Macro editor.
10. Connection diagnostics.

The gameplay screen should support landscape orientation.

Make controls sufficiently large for comfortable use.

Avoid unnecessary visual clutter.

---

# 10. Performance and Reliability

Prioritize:

- Low input latency.
- Stable networking.
- Efficient sensor processing.
- Minimal battery consumption.
- Low CPU usage.
- Accurate multitouch.
- Correct keyboard state management.
- Reliable disconnection handling.

The input pipeline must avoid unnecessary allocations and serialization overhead.

Use a consistent coordinate and sensitivity model.

Provide diagnostics showing:

- Connection latency.
- Packet loss.
- Connection quality.
- Input event rate.
- Sensor update rate.
- Active input states.

All held keys and mouse buttons must be released when:

- The phone disconnects.
- The application loses its session.
- The user explicitly disconnects.
- A profile is switched.
- A safety timeout is reached.

---

# 11. Development Methodology

Do not attempt to generate the entire application in one unstructured coding operation.

Work in milestones.

### Milestone 1: Architecture and repository setup

- Inspect the existing repository.
- Document the architecture.
- Establish project structure.
- Define protocol schemas.
- Define the Rust input abstraction.
- Establish build instructions.

### Milestone 2: Basic communication

- Build the Android application shell.
- Build the Rust receiver.
- Establish phone-to-PC communication.
- Implement discovery and manual connection.
- Display connection status.

### Milestone 3: Keyboard and mouse

- Implement basic touchscreen buttons.
- Implement keyboard events.
- Implement mouse buttons.
- Implement relative mouse movement.
- Test Linux and Windows backends.

### Milestone 4: Customizable grid

- Build the grid editor.
- Implement cell assignment.
- Implement profile serialization.
- Implement gameplay mode.

### Milestone 5: Advanced controls

- Implement multitouch.
- Implement virtual joystick.
- Implement macros.
- Implement toggle and hold actions.

### Milestone 6: Gyroscope

- Implement sensor collection.
- Implement orientation estimation.
- Implement calibration and recentering.
- Implement gyro-to-mouse translation.
- Implement activation modes.
- Implement touchpad and gyro hybrid aiming.

### Milestone 7: Cross-platform validation

Test:

- Windows 10 and 11.
- Linux Wayland.
- Linux X11 where practical.
- Native Linux games.
- Steam Proton games.
- Multiple simultaneous touch inputs.
- Network interruption.
- Reconnection.
- Long gaming sessions.

### Milestone 8: Polish

- Improve UI.
- Add diagnostics.
- Improve profile management.
- Optimize performance.
- Document installation and troubleshooting.

---

# 12. Engineering Rules

1. Do not create a superficial mockup and claim the application is functional.
2. Do not leave critical functionality as TODOs.
3. Do not silently replace one technology with another.
4. Do not introduce unnecessary dependencies.
5. Keep platform-specific code isolated.
6. Write reusable and testable modules.
7. Include unit tests for input mapping and protocol processing.
8. Include integration tests for networking and input backends where possible.
9. Document Linux permissions and Windows requirements.
10. Do not assume a feature works without testing it.
11. Never leave stuck keyboard or mouse inputs after an error.
12. Maintain an implementation checklist and update it as work progresses.
13. Keep architectural decisions documented.
14. Build and test after each milestone.
15. Preserve existing working code when extending the project.

If a requirement is technically difficult, investigate it and explain the limitation before choosing an alternative.

---

# 13. First Task

Before implementing the application:

1. Inspect the repository and available development environment.
2. Research the relevant Android sensor APIs and Linux/Windows input injection APIs.
3. Verify the proposed networking and input architecture.
4. Identify technical risks, particularly Linux Wayland input permissions and gyro-to-mouse conversion.
5. Produce an architecture document.
6. Produce a directory structure.
7. Produce a milestone checklist.
8. Identify the smallest functional end-to-end prototype.

Then begin implementation with Milestone 1 and Milestone 2.

Do not stop after producing documentation. Continue with actual implementation, compilation and testing.

At the end of each milestone, report:

- What was implemented.
- Files created or modified.
- Build/test results.
- Known limitations.
- Remaining tasks.
- Exact commands to run the applications.

The final objective is a fully functional Android-to-PC programmable input system, not merely a demonstration UI.
