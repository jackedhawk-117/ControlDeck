package com.phonecontroller.app.ui

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontroller.app.AppModel
import com.phonecontroller.app.input.AndroidGyroSource
import com.phonecontroller.app.input.ControlAction
import com.phonecontroller.app.input.GyroActivationMode
import com.phonecontroller.app.input.GyroCalibrationResult
import com.phonecontroller.app.input.GyroCellAction
import com.phonecontroller.app.input.GyroConfig
import com.phonecontroller.app.input.GyroMapper
import com.phonecontroller.app.input.GyroTelemetry
import com.phonecontroller.app.input.InputTracker
import com.phonecontroller.app.input.Keycodes
import com.phonecontroller.app.input.MacroEngine
import com.phonecontroller.app.input.Motion
import com.phonecontroller.app.input.MotionAccumulator
import com.phonecontroller.app.input.TouchpadConfig
import com.phonecontroller.app.input.TouchpadMapper
import com.phonecontroller.app.input.resolveGyroActive
import com.phonecontroller.app.net.ConnState
import com.phonecontroller.app.net.DeltaEvent
import com.phonecontroller.app.net.PhoneConnection
import com.phonecontroller.app.net.Protocol
import com.phonecontroller.app.net.Stats
import com.phonecontroller.app.profile.ButtonShape
import com.phonecontroller.app.profile.CellConfig
import com.phonecontroller.app.profile.CellType
import com.phonecontroller.app.profile.GameProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One gameplay control action used for UI callback dispatching.
 */
private data class RuntimeControl(
    val id: String,
    val label: String,
    val action: ControlAction,
    val toggle: Boolean = false,
)

/**
 * Full-screen Gameplay Mode screen (spec §3–5, §7 & §11 Milestone 4):
 * - Unconstrained freeform mobile gaming canvas
 * - Virtual analog joystick translating thumb displacement to WASD & sprint
 * - 120 Hz camera touchpad with Q16.16 deltas
 * - Full multi-touch support across all custom buttons simultaneously
 * - Clean profile switching and zero stuck-key safety on disconnect
 */
@Composable
fun PlayScreen(model: AppModel, conn: PhoneConnection) {
    val state = conn.state.collectAsState().value
    val stats = conn.stats.collectAsState().value
    val context = LocalContext.current
    val profileStore = remember(context) { com.phonecontroller.app.profile.ProfileStore(context) }

    var activeProfile by remember {
        val loaded = profileStore.getActiveProfile()
        mutableStateOf(loaded.copy(cells = loaded.normalizedCells()))
    }
    var isEditorMode by remember { mutableStateOf(false) }
    var showProfileManager by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showGyroSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    val tracker = remember { InputTracker() }
    val motion = remember { MotionAccumulator() }
    val gyroSource = remember(context) { AndroidGyroSource(context) }
    val gyroMapper = remember { GyroMapper(activeProfile.gyro) }

    // The pitch gesture mirrors in reverse landscape because the phone's short
    // axis then points toward the player. Track the live surface rotation and
    // keep the mapper's chirality in sync without recreating the activity.
    val view = LocalView.current
    var displayRotation by remember {
        mutableStateOf(view.display?.rotation ?: Surface.ROTATION_0)
    }
    DisposableEffect(view) {
        val display = view.display
        val manager = view.context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (display != null && displayId == display.displayId) {
                    displayRotation = display.rotation
                }
            }

            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    LaunchedEffect(gyroMapper, displayRotation) {
        gyroMapper.setDisplayRotation(displayRotation)
    }
    var pressedIds by remember { mutableStateOf(setOf<String>()) }
    var toggledIds by remember { mutableStateOf(setOf<String>()) }
    var runningMacros by remember { mutableStateOf(setOf<String>()) }
    var touchpadSaveJob by remember { mutableStateOf<Job?>(null) }
    var gyroSaveJob by remember { mutableStateOf<Job?>(null) }

    // Gyro arming state. HOLD and REGION are momentary; TOGGLE latches until
    // the profile changes or the session ends.
    var gyroHeld by remember { mutableStateOf(false) }
    var gyroToggled by remember { mutableStateOf(false) }
    var gyroRegionActive by remember { mutableStateOf(false) }
    // Mirrors the tracker's reference-counted RMB state for the HUD. The
    // injection decision is read live in the tick loop, not from this value.
    var adsHeld by remember { mutableStateOf(false) }
    var gyroCalibrationStatus by remember { mutableStateOf<String?>(null) }
    var pendingCalibration by remember { mutableStateOf<GyroCalibrationResult?>(null) }
    var gyroRatesHz by remember { mutableStateOf(0 to 0) }
    var gyroTelemetry by remember { mutableStateOf(GyroTelemetry()) }

    // Macros run on the Compose/UI dispatcher and feed the same tracker as
    // touch controls. A dropped connection therefore cancels and releases them.
    val macroEngine = remember(tracker, conn) {
        MacroEngine(
            tracker = tracker,
            emit = { events ->
                if (conn.state.value is ConnState.Paired) conn.enqueueDelta(events)
            },
            scope = model.scope,
            onRunningChanged = { runningMacros = it },
        )
    }

    val released = state !is ConnState.Paired

    // ---- Gyro aiming (Milestone 6) ----
    // Everything below runs phone-side; only the resulting mouse deltas reach
    // the receiver, so the wire protocol is unchanged.
    val gyroConfig = activeProfile.gyro
    // Stop the sensors for the grid editor and the touchpad/profile overlays;
    // the gyro tuning screen and stats HUD keep them running for calibration
    // and live rate readouts.
    val gyroSensorsWanted = gyroConfig.enabled && !released &&
        !isEditorMode && !showSettings && !showProfileManager
    // Gameplay only when no overlay owns the screen. Overlays release inputs
    // before opening, so ADS cannot stay latched behind one.
    val gyroPlayActive = gyroSensorsWanted && !showGyroSettings
    val gyroActive = gyroPlayActive && resolveGyroActive(
        mode = gyroConfig.activationMode,
        holdActive = gyroHeld,
        toggleActive = gyroToggled,
        regionActive = gyroRegionActive,
        adsHeld = adsHeld,
    )
    // The tuning overlay disarms *injection* but keeps the mapper processing
    // sensors so its live telemetry reflects the real gameplay pipeline.
    // ADS-only preview deliberately ignores ADS so the user can tune while
    // the aim button is not held.
    val gyroPreview = showGyroSettings && gyroSensorsWanted

    // Sensors run only while this profile wants them, saving battery in the
    // editor and on every profile that leaves gyro disabled.
    DisposableEffect(gyroSource, gyroSensorsWanted) {
        if (gyroSensorsWanted) gyroSource.start()
        onDispose { gyroSource.stop() }
    }

    LaunchedEffect(gyroConfig) {
        gyroMapper.setConfig(gyroConfig)
    }

    LaunchedEffect(activeProfile.id) {
        gyroHeld = false
        gyroToggled = false
        gyroRegionActive = false
        gyroCalibrationStatus = null
    }

    // Activation is re-evaluated on every tick. Composition does not recompose
    // when the tracker gains/loses RMB, so ADS must be read live inside the
    // loop; the lambda carries the freshest manual hold/toggle/region state.
    val gyroActivationNow by rememberUpdatedState(
        { ads: Boolean ->
            gyroPlayActive && resolveGyroActive(
                mode = gyroConfig.activationMode,
                holdActive = gyroHeld,
                toggleActive = gyroToggled,
                regionActive = gyroRegionActive,
                adsHeld = ads,
            )
        },
    )
    val gyroPreviewNow by rememberUpdatedState(gyroPreview)

    // 120 Hz gyro tick. Sensor callbacks only publish a mailbox; all mapping,
    // smoothing and Q16.16 encoding happen here on a single thread.
    LaunchedEffect(conn, gyroSensorsWanted) {
        if (!gyroSensorsWanted) return@LaunchedEffect
        val gyroValues = FloatArray(3)
        val quaternionValues = FloatArray(4)
        while (isActive) {
            delay(8)
            val nowNs = System.nanoTime()
            val adsNow = tracker.isMouseButtonHeld(Protocol.MOUSE_RIGHT)
            if (adsNow != adsHeld) adsHeld = adsNow
            val gyroTs = gyroSource.readGyro(gyroValues)
            val quatTs = gyroSource.readQuaternion(quaternionValues)
            val previewNow = gyroPreviewNow
            val activeNow = previewNow || gyroActivationNow(adsNow)
            gyroMapper.setActive(activeNow)
            val out = gyroMapper.update(
                gyro = gyroValues,
                gyroTsNs = gyroTs,
                quaternion = quaternionValues,
                quatTsNs = quatTs,
                nowNs = nowNs,
                adsActive = adsNow,
            )
            if (!previewNow && activeNow && (out.dxQ16 != 0 || out.dyQ16 != 0)) {
                // While the tuning overlay is open the mapper runs only as a
                // live preview; never inject those deltas into the game.
                motion.add(out)
            }
            gyroMapper.takeCalibrationResult()?.let { pendingCalibration = it }
        }
    }

    // Telemetry is sampled at UI rate, never per sensor sample, so the HUD and
    // tuning screen do not add recomposition work to the input path.
    LaunchedEffect(gyroSensorsWanted, showDiagnostics, showGyroSettings) {
        if (!gyroSensorsWanted || (!showDiagnostics && !showGyroSettings)) {
            gyroTelemetry = GyroTelemetry()
            return@LaunchedEffect
        }
        while (isActive) {
            gyroTelemetry = gyroMapper.telemetry()
            delay(100)
        }
    }

    // Diagnostics: samples per second as delivered by the sensor service.
    LaunchedEffect(gyroSensorsWanted) {
        if (!gyroSensorsWanted) {
            gyroRatesHz = 0 to 0
            return@LaunchedEffect
        }
        while (isActive) {
            delay(1000)
            gyroRatesHz = gyroSource.takeSampleRatesHz(System.nanoTime())
        }
    }

    // Calibration completes on the tick coroutine; persist the learned bias
    // from here so profile state is updated with the latest profile object.
    LaunchedEffect(pendingCalibration) {
        val result = pendingCalibration ?: return@LaunchedEffect
        pendingCalibration = null
        if (!result.accepted) {
            gyroCalibrationStatus = "Calibration rejected — keep the phone still and retry"
            return@LaunchedEffect
        }
        val base = activeProfile
        val updated = base.copy(
            gyro = base.gyro.copy(
                biasX = result.biasX,
                biasY = result.biasY,
                biasZ = result.biasZ,
            ),
        )
        activeProfile = updated
        gyroMapper.setConfig(updated.gyro)
        gyroSaveJob?.cancel()
        profileStore.saveProfile(updated)
        gyroCalibrationStatus = String.format(
            java.util.Locale.US,
            "Calibrated: %.3f / %.3f / %.3f rad/s",
            result.biasX,
            result.biasY,
            result.biasZ,
        )
    }

    // A macro job runs in the app scope, not the composition scope. Cancel it
    // when the gameplay screen is disposed so it cannot keep running (and
    // enqueueing to a closed connection) after Back/Disconnect.
    DisposableEffect(macroEngine) {
        onDispose { macroEngine.cancelAll() }
    }

    // Safety: clear local highlights/state after disconnect/watchdog
    LaunchedEffect(released) {
        if (released) {
            macroEngine.cancelAll()
            touchpadSaveJob?.cancel()
            gyroSaveJob?.cancel()
            tracker.clear()
            motion.clear()
            pressedIds = emptySet()
            toggledIds = emptySet()
            runningMacros = emptySet()
            gyroHeld = false
            gyroToggled = false
            gyroRegionActive = false
            adsHeld = false
            showSettings = false
            showProfileManager = false
            showGyroSettings = false
            isEditorMode = false
            showDiagnostics = false
        }
    }

    // 1 Hz INPUT_SNAPSHOT repairs lost UDP deltas and satisfies the watchdog
    LaunchedEffect(conn) {
        while (isActive) {
            delay(1000)
            if (conn.state.value !is ConnState.Paired) continue
            runCatching { conn.sendSnapshot(tracker.heldKeys(), tracker.mouseBits()) }
        }
    }

    // Touchpad sender: drained at ~120 Hz with Q16.16 deltas
    LaunchedEffect(conn) {
        while (isActive) {
            delay(8)
            if (conn.state.value !is ConnState.Paired) {
                motion.clear()
                continue
            }
            val delta = motion.drain() ?: continue
            conn.enqueueDelta(listOf(DeltaEvent.mouseRelQ16(delta.dxQ16, delta.dyQ16)))
        }
    }

    /**
     * Enqueue a delta synchronously. Because every press/release on the UI
     * thread goes through the same FIFO, a key-up can never overtake the
     * key-down that produced it (which would leave a stuck key on the PC).
     */
    fun send(events: List<DeltaEvent>) {
        if (events.isEmpty() || released) return
        conn.enqueueDelta(events)
    }

    /**
     * Release every locally held key/button and ask the receiver to reset as a
     * second safety net. Must run *before* an overlay replaces the gameplay
     * canvas: disposing the controls otherwise loses their onUp callbacks and
     * leaves keys held on the PC.
     */
    fun releaseAllInputs() {
        macroEngine.cancelAll()
        touchpadSaveJob?.cancel()
        gyroSaveJob?.cancel()
        val events = tracker.releaseAll()
        tracker.clear()
        motion.clear()
        // Opening an overlay, switching profiles or disconnecting must not
        // leave a stored neutral pose that could jump when play resumes.
        if (gyroMapper.isCalibrating) gyroCalibrationStatus = "Calibration cancelled"
        gyroMapper.cancelCalibration()
        gyroMapper.recenter()
        pressedIds = emptySet()
        toggledIds = emptySet()
        runningMacros = emptySet()
        gyroHeld = false
        gyroToggled = false
        gyroRegionActive = false
        adsHeld = false
        if (conn.state.value is ConnState.Paired) {
            if (events.isNotEmpty()) conn.enqueueDelta(events)
            model.scope.launch { runCatching { conn.sendReset() } }
        }
    }

    fun onDown(c: RuntimeControl) {
        if (released) return
        if (c.toggle) {
            if (c.id in toggledIds) {
                toggledIds = toggledIds - c.id
                send(tracker.release(c.id))
            } else {
                toggledIds = toggledIds + c.id
                send(tracker.press(c.id, c.action))
            }
        } else {
            if (c.id in pressedIds) return
            pressedIds = pressedIds + c.id
            send(tracker.press(c.id, c.action))
        }
    }

    fun onUp(c: RuntimeControl) {
        if (c.toggle || c.id !in pressedIds) return
        pressedIds = pressedIds - c.id
        send(tracker.release(c.id))
    }

    fun onJoystickState(cellId: String, joyState: JoystickState) {
        if (released) return
        val joyDirections = listOf(
            Triple("W", joyState.up, Keycodes.KEY_W),
            Triple("A", joyState.left, Keycodes.KEY_A),
            Triple("S", joyState.down, Keycodes.KEY_S),
            Triple("D", joyState.right, Keycodes.KEY_D),
            Triple("SHIFT", joyState.sprint, Keycodes.KEY_LEFTSHIFT),
        )
        val events = ArrayList<DeltaEvent>()
        for ((dirName, isDirActive, keyCode) in joyDirections) {
            val actionId = "joy_${cellId}_$dirName"
            val wasActive = tracker.isActive(actionId)
            if (isDirActive && !wasActive) {
                events += tracker.press(actionId, ControlAction(keys = listOf(keyCode)))
            } else if (!isDirActive && wasActive) {
                events += tracker.release(actionId)
            }
        }
        if (events.isNotEmpty()) {
            send(events)
        }
    }

    fun tapClick() {
        if (released) return
        val action = ControlAction(mouseButtons = listOf(Protocol.MOUSE_LEFT))
        val events = tracker.press(InputTracker.TAP_CLICK_ACTION, action) +
            tracker.release(InputTracker.TAP_CLICK_ACTION)
        send(events)
    }

    fun switchProfile(newProfile: GameProfile) {
        releaseAllInputs()
        val normalized = newProfile.copy(cells = newProfile.normalizedCells())
        activeProfile = normalized
        gyroMapper.setConfig(normalized.gyro)
        gyroHeld = false
        gyroToggled = false
        gyroRegionActive = false
        adsHeld = false
        gyroCalibrationStatus = null
        profileStore.setActiveProfileId(normalized.id)
        model.scope.launch { runCatching { conn.sendProfileInfo(normalized.name) } }
    }

    fun updateTouchpadConfig(next: TouchpadConfig) {
        val clean = next.sanitized()
        val updated = activeProfile.copy(touchpad = clean)
        activeProfile = updated
        // Persist after the slider settles instead of rewriting profiles_v1.json
        // on every pointer tick.
        touchpadSaveJob?.cancel()
        touchpadSaveJob = model.scope.launch {
            delay(400)
            profileStore.saveProfile(updated)
        }
    }

    fun updateGyroConfig(next: GyroConfig) {
        val clean = next.sanitized()
        val modeChanged = clean.activationMode != activeProfile.gyro.activationMode
        val updated = activeProfile.copy(gyro = clean)
        activeProfile = updated
        gyroMapper.setConfig(clean)
        if (!clean.enabled) {
            gyroMapper.cancelCalibration()
            gyroHeld = false
            gyroToggled = false
            gyroRegionActive = false
            adsHeld = false
        } else if (modeChanged) {
            // Do not carry a HOLD/TOGGLE/REGION latch into a different mode.
            gyroHeld = false
            gyroToggled = false
            gyroRegionActive = false
            gyroMapper.recenter()
        }
        gyroSaveJob?.cancel()
        gyroSaveJob = model.scope.launch {
            delay(400)
            profileStore.saveProfile(updated)
        }
    }

    fun closeGyroSettings() {
        gyroSaveJob?.cancel()
        profileStore.saveProfile(activeProfile)
        // The phone may have moved while tuning; re-prime so re-arming cannot
        // replay that pose change as a jump (matters when recenterOnRelease
        // is disabled).
        gyroMapper.recenter()
        showGyroSettings = false
    }

    // ---- Layout Editing Mode ----
    if (isEditorMode) {
        GridEditorScreen(
            initialProfile = activeProfile,
            onSave = { updated ->
                profileStore.saveProfile(updated)
                switchProfile(updated)
                isEditorMode = false
            },
            onCancel = { isEditorMode = false },
        )
        return
    }

    // ---- Touchpad Settings Overlay ----
    if (showSettings) {
        TouchpadSettingsScreen(
            config = activeProfile.touchpad,
            onChange = ::updateTouchpadConfig,
            onClose = {
                touchpadSaveJob?.cancel()
                profileStore.saveProfile(activeProfile)
                gyroMapper.recenter()
                showSettings = false
            },
        )
        return
    }

    // ---- Gyro Settings Overlay ----
    if (showGyroSettings) {
        GyroSettingsScreen(
            config = gyroConfig,
            gyroAvailable = gyroSource.hasGyro,
            orientationAvailable = gyroSource.hasOrientation,
            orientationLabel = gyroSource.orientationLabel,
            sensorRatesHz = gyroRatesHz,
            active = gyroActive,
            adsHeld = adsHeld,
            telemetry = gyroTelemetry,
            calibrationStatus = gyroCalibrationStatus,
            onChange = ::updateGyroConfig,
            onCalibrate = {
                gyroCalibrationStatus = "Calibrating — hold the phone still"
                gyroMapper.beginCalibration(System.nanoTime())
            },
            onRecenter = {
                gyroMapper.recenter()
                gyroCalibrationStatus = "Neutral pose stored; next sample is the baseline"
            },
            onClose = ::closeGyroSettings,
        )
        return
    }

    // ---- Profile Manager Dialog ----
    if (showProfileManager) {
        ProfileManagerDialog(
            profileStore = profileStore,
            currentProfileId = activeProfile.id,
            onSelectProfile = { selected ->
                switchProfile(selected)
                showProfileManager = false
            },
            onEditProfile = { selected ->
                releaseAllInputs()
                // Make the profile being edited active *before* the editor
                // opens, so the stored active id and in-memory profile cannot
                // diverge if the user closes the editor with Cancel.
                val normalized = selected.copy(cells = selected.normalizedCells())
                activeProfile = normalized
                profileStore.setActiveProfileId(normalized.id)
                isEditorMode = true
            },
            onDismiss = { showProfileManager = false },
        )
    }

    // ---- Fullscreen Mobile Gameplay Canvas ----
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .pointerInput(gyroConfig) {
                // Observe region touches in the Initial pass without consuming
                // them, so aiming and normal controls work simultaneously.
                if (!gyroConfig.enabled ||
                    gyroConfig.activationMode != GyroActivationMode.REGION
                ) {
                    gyroRegionActive = false
                } else {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val height = size.height.toFloat().coerceAtLeast(1f)
                            gyroRegionActive = event.changes.any { change ->
                                change.pressed && gyroConfig.containsRegion(
                                    (change.position.x / width).coerceIn(0f, 1f),
                                    (change.position.y / height).coerceIn(0f, 1f),
                                )
                            }
                        }
                    }
                }
            },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val canvasWidthPx = constraints.maxWidth.toFloat()
            val canvasHeightPx = constraints.maxHeight.toFloat()
            val density = LocalDensity.current

            // Background camera aiming surface: captures drag gestures on the right side
            // that do not land on any button
            val hasExplicitTouchpad = activeProfile.cells.any { it.type == CellType.TOUCHPAD }
            if (!hasExplicitTouchpad) {
                CameraTouchpadSurface(
                    enabled = !released,
                    config = activeProfile.touchpad,
                    onMotion = { motion.add(it) },
                    onTap = ::tapClick,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width((maxWidth * activeProfile.touchpad.widthFraction))
                        .align(Alignment.CenterEnd),
                )
            }

            // Touch-region activation rectangle: purely visual, never consumes
            // pointer input. Drawn above the fallback touchpad so it stays
            // visible wherever the region overlaps the camera surface.
            if (gyroConfig.enabled && gyroConfig.activationMode == GyroActivationMode.REGION) {
                val regionLeftPx = gyroConfig.regionXPct * canvasWidthPx
                val regionTopPx = gyroConfig.regionYPct * canvasHeightPx
                val regionWidthPx = gyroConfig.regionWidthPct * canvasWidthPx
                val regionHeightPx = gyroConfig.regionHeightPct * canvasHeightPx
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(regionLeftPx.roundToInt(), regionTopPx.roundToInt())
                        }
                        .size(
                            with(density) { regionWidthPx.toDp() },
                            with(density) { regionHeightPx.toDp() },
                        )
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                            RoundedCornerShape(10.dp),
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "GYRO ZONE",
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                    )
                }
            }

            // Render all canvas buttons, joysticks, and custom touchpads
            for (cell in activeProfile.cells) {
                if (cell.type == CellType.EMPTY) continue

                val widthPx = with(density) { cell.widthDp.dp.toPx() }
                val heightPx = with(density) { cell.heightDp.dp.toPx() }
                val centerX = cell.xPct * canvasWidthPx
                val centerY = cell.yPct * canvasHeightPx
                val leftPx = (centerX - widthPx / 2f)
                    .coerceIn(0f, (canvasWidthPx - widthPx).coerceAtLeast(0f))
                val topPx = (centerY - heightPx / 2f)
                    .coerceIn(0f, (canvasHeightPx - heightPx).coerceAtLeast(0f))

                val clipShape: Shape = when (cell.shape) {
                    ButtonShape.CIRCLE -> CircleShape
                    ButtonShape.PILL -> RoundedCornerShape(cell.heightDp.dp / 2)
                    ButtonShape.ROUNDED_RECT -> RoundedCornerShape(activeProfile.cornerRadiusDp.dp)
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                        .size(cell.widthDp.dp, cell.heightDp.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (cell.type) {
                        CellType.JOYSTICK -> {
                            VirtualJoystick(
                                size = cell.widthDp.dp,
                                enabled = !released,
                                sprintEnabled = cell.joystickSprint,
                                opacity = cell.opacity,
                                onStateChange = { joyState -> onJoystickState(cell.id, joyState) },
                            )
                        }

                        CellType.TOUCHPAD -> {
                            CameraTouchpadSurface(
                                enabled = !released,
                                config = activeProfile.touchpad,
                                onMotion = { motion.add(it) },
                                onTap = ::tapClick,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(clipShape),
                            )
                        }

                        CellType.GYRO -> {
                            val activates = cell.gyroAction == GyroCellAction.ACTIVATE
                            val held = if (activates) {
                                when (gyroConfig.activationMode) {
                                    GyroActivationMode.HOLD -> gyroHeld
                                    GyroActivationMode.TOGGLE -> gyroToggled
                                    GyroActivationMode.ALWAYS -> gyroActive
                                    GyroActivationMode.REGION -> gyroRegionActive
                                    GyroActivationMode.ADS_ONLY -> gyroActive
                                }
                            } else {
                                cell.id in pressedIds
                            }
                            CanvasButton(
                                label = cell.label,
                                held = held,
                                enabled = !released && gyroConfig.enabled,
                                shape = clipShape,
                                opacity = cell.opacity,
                                tint = cell.customColor,
                                modifier = Modifier.fillMaxSize(),
                                onDown = {
                                    if (!activates) {
                                        gyroMapper.recenter()
                                        pressedIds = pressedIds + cell.id
                                        gyroCalibrationStatus = "Camera recentered"
                                    } else {
                                        when (gyroConfig.activationMode) {
                                            GyroActivationMode.HOLD -> gyroHeld = true
                                            GyroActivationMode.TOGGLE ->
                                                gyroToggled = !gyroToggled
                                            // ALWAYS and REGION arm themselves.
                                            else -> Unit
                                        }
                                    }
                                },
                                onUp = {
                                    if (!activates) {
                                        pressedIds = pressedIds - cell.id
                                    } else if (gyroConfig.activationMode == GyroActivationMode.HOLD) {
                                        gyroHeld = false
                                    }
                                },
                            )
                        }

                        CellType.MACRO -> {
                            val running = cell.id in runningMacros
                            CanvasButton(
                                label = cell.label,
                                held = running,
                                enabled = !released,
                                shape = clipShape,
                                opacity = cell.opacity,
                                tint = cell.customColor,
                                modifier = Modifier.fillMaxSize(),
                                // Tap starts; tap while running cancels immediately.
                                onDown = {
                                    if (running) {
                                        macroEngine.cancel(cell.id)
                                    } else {
                                        macroEngine.start(cell.id, cell.macro)
                                    }
                                },
                                onUp = {},
                            )
                        }

                        else -> {
                            val action = cell.toControlAction()
                            if (action != null) {
                                val ctrl = RuntimeControl(
                                    id = cell.id,
                                    label = cell.label,
                                    action = action,
                                    toggle = cell.isToggle,
                                )
                                val held = ctrl.id in pressedIds || ctrl.id in toggledIds
                                CanvasButton(
                                    label = ctrl.label,
                                    held = held,
                                    enabled = !released,
                                    shape = clipShape,
                                    opacity = cell.opacity,
                                    tint = cell.customColor,
                                    modifier = Modifier.fillMaxSize(),
                                    onDown = { onDown(ctrl) },
                                    onUp = { onUp(ctrl) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Compact Top Status & Control Bar ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        conn.pcName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            releaseAllInputs()
                            showProfileManager = true
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp),
                    ) {
                        Text("🎮 ${activeProfile.name} ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        if (released) (state as? ConnState.Disconnected)?.reason ?: "disconnected"
                        else "RTT ${conn.rttMs}ms · ${stats?.eventsPerSec ?: 0}ev/s",
                        color = if (released) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            releaseAllInputs()
                            isEditorMode = true
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text("✏️ Edit Layout", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            releaseAllInputs()
                            showSettings = true
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text("Touchpad", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            releaseAllInputs()
                            showGyroSettings = true
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(
                            when {
                                gyroActive -> "Gyro ●"
                                gyroConfig.enabled &&
                                    gyroConfig.activationMode == GyroActivationMode.ADS_ONLY ->
                                    "Gyro ADS"
                                else -> "Gyro"
                            },
                            fontSize = 11.sp,
                            color = if (gyroActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    OutlinedButton(
                        onClick = { showDiagnostics = !showDiagnostics },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(if (showDiagnostics) "Hide Stats" else "Stats", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { model.disconnect() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(if (released) "Back" else "Disconnect", fontSize = 11.sp)
                    }
                }
            }
        }

        if (showDiagnostics) {
            DiagnosticsPanel(
                pcName = conn.pcName,
                state = state,
                stats = stats,
                rttMs = conn.rttMs,
                heldKeys = tracker.heldKeys(),
                mouseBits = tracker.mouseBits(),
                runningMacros = runningMacros.size,
                gyroEnabled = gyroConfig.enabled,
                gyroActive = gyroActive,
                gyroMode = gyroConfig.activationMode,
                adsHeld = adsHeld,
                gyroRatesHz = gyroRatesHz,
                gyroSource = gyroConfig.sensorSource.name,
                gyroTelemetry = gyroTelemetry,
                onClose = { showDiagnostics = false },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }
    }
}

/**
 * Compact, always-visible-while-open diagnostics overlay. Values come from
 * the receiver's 1 Hz STATS packets, the TCP RTT probe and local tracker state.
 */
@Composable
private fun DiagnosticsPanel(
    pcName: String,
    state: ConnState,
    stats: Stats?,
    rttMs: Int,
    heldKeys: IntArray,
    mouseBits: Int,
    runningMacros: Int,
    gyroEnabled: Boolean,
    gyroActive: Boolean,
    gyroMode: GyroActivationMode,
    adsHeld: Boolean,
    gyroRatesHz: Pair<Int, Int>,
    gyroSource: String,
    gyroTelemetry: GyroTelemetry,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastDrops by remember { mutableStateOf<Long?>(null) }
    var dropsPerSecond by remember { mutableStateOf(0L) }
    LaunchedEffect(stats?.packetsDroppedTotal) {
        val total = stats?.packetsDroppedTotal ?: return@LaunchedEffect
        lastDrops?.let { previous ->
            if (total >= previous) dropsPerSecond = total - previous
        }
        lastDrops = total
        // Stats may stop changing when no drops occur; decay the rate instead
        // of leaving a one-off spike displayed forever.
        delay(1_500)
        dropsPerSecond = 0
    }

    val connected = state is ConnState.Paired
    val quality = when {
        !connected -> "offline"
        rttMs <= 0 -> "measuring"
        rttMs > 100 || dropsPerSecond >= 5 -> "Poor"
        rttMs > 40 || dropsPerSecond >= 1 -> "Fair"
        else -> "Good"
    }
    val qualityColor = when (quality) {
        "Good" -> Color(0xFF66BB6A)
        "Fair" -> Color(0xFFFFB74D)
        "Poor" -> Color(0xFFEF5350)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val active = buildList {
        for (key in heldKeys) add(Keycodes.labelFor(key))
        if (mouseBits and 0x01 != 0) add("LMB")
        if (mouseBits and 0x02 != 0) add("RMB")
        if (mouseBits and 0x04 != 0) add("MMB")
        if (mouseBits and 0x08 != 0) add("BACK")
        if (mouseBits and 0x10 != 0) add("FWD")
    }
    val activeText = if (active.isEmpty()) "none" else active.joinToString(" + ")

    Card(
        modifier = modifier.widthIn(max = 290.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Diagnostics",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(
                    onClick = onClose,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(22.dp),
                ) {
                    Text("✕", fontSize = 10.sp)
                }
            }
            DiagRow("PC", pcName)
            DiagRow(
                "Status",
                if (connected) {
                    "paired"
                } else {
                    (state as? ConnState.Disconnected)?.reason ?: "disconnected"
                },
            )
            DiagRow("Quality", quality, qualityColor)
            DiagRow("RTT", if (rttMs > 0) "$rttMs ms" else "—")
            DiagRow("Input rate", "${stats?.eventsPerSec ?: 0} ev/s")
            DiagRow(
                "Drops",
                "${stats?.packetsDroppedTotal ?: 0} total" +
                    if (dropsPerSecond > 0) " · +$dropsPerSecond/s" else "",
            )
            DiagRow("Active keys", activeText)
            DiagRow("Macros", if (runningMacros > 0) "$runningMacros running" else "none")
            DiagRow(
                "Gyro",
                when {
                    !gyroEnabled -> "off in profile"
                    gyroActive -> "aiming · $gyroSource"
                    gyroMode == GyroActivationMode.ADS_ONLY && !adsHeld ->
                        "inactive · ADS not held"
                    else -> "armed · $gyroSource"
                },
            )
            DiagRow(
                "Gyro mode",
                when (gyroMode) {
                    GyroActivationMode.ALWAYS -> "always on"
                    GyroActivationMode.HOLD -> "hold button"
                    GyroActivationMode.TOGGLE -> "toggle button"
                    GyroActivationMode.REGION -> "touch region"
                    GyroActivationMode.ADS_ONLY -> "ADS only"
                },
            )
            DiagRow("ADS", if (adsHeld) "RMB held" else "not held")
            DiagRow(
                "Sensor rate",
                if (gyroEnabled) {
                    "${gyroRatesHz.first} gyro / ${gyroRatesHz.second} ori Hz"
                } else {
                    "sensors stopped"
                },
            )
            DiagRow(
                "Gyro rate",
                when {
                    !gyroEnabled -> "off in profile"
                    !gyroActive -> "idle"
                    gyroTelemetry.intervalMs <= 0f -> "waiting"
                    else -> String.format(
                        java.util.Locale.US,
                        "%.1f / %.1f °/s",
                        gyroTelemetry.yawRateDps,
                        gyroTelemetry.pitchRateDps,
                    )
                },
            )
            DiagRow(
                "Gyro out",
                when {
                    !gyroEnabled || !gyroActive -> "—"
                    gyroTelemetry.deadzoneActive -> "deadzone"
                    else -> String.format(
                        java.util.Locale.US,
                        "%.1f / %.1f px",
                        gyroTelemetry.outputDxPx,
                        gyroTelemetry.outputDyPx,
                    )
                },
            )
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2f),
        )
    }
}
@Composable
private fun CameraTouchpadSurface(
    enabled: Boolean,
    config: TouchpadConfig,
    onMotion: (Motion) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    var touching by remember { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnMotion by rememberUpdatedState(onMotion)
    val tapSlopPx = with(LocalDensity.current) { 14.dp.toPx() }

    val bg = if (touching) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
    }

    Box(
        modifier = modifier
            .background(bg)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(config, tapSlopPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val pointerId = down.id
                        val mapper = TouchpadMapper(config)
                        var sampleTime = down.uptimeMillis
                        var travelled = 0f
                        var endedNormally = false
                        touching = true
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) {
                                    endedNormally = true
                                    break
                                }
                                val now = change.uptimeMillis
                                val dt = (now - sampleTime).coerceIn(1L, 50L)
                                sampleTime = now
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y
                                if (dx != 0f || dy != 0f) {
                                    val delta = mapper.onMove(dx, dy, dt)
                                    if (!delta.isZero()) currentOnMotion(delta)
                                    travelled += hypot(dx, dy)
                                    change.consume()
                                }
                            }
                        } finally {
                            touching = false
                            val duration = sampleTime - down.uptimeMillis
                            if (endedNormally && config.tapToClick &&
                                travelled <= tapSlopPx && duration <= 250
                            ) {
                                currentOnTap()
                            }
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (enabled) "CAMERA AIM" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontSize = 14.sp,
        )
    }
}

/**
 * Freeform canvas gaming button with independent multi-touch pointer tracking.
 */
@Composable
private fun CanvasButton(
    label: String,
    held: Boolean,
    enabled: Boolean,
    shape: Shape,
    opacity: Float,
    tint: Long?,
    modifier: Modifier,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnUp by rememberUpdatedState(onUp)

    val custom = tint?.let { Color(it) }
    val bg = when {
        !enabled -> (custom ?: MaterialTheme.colorScheme.surface).copy(alpha = 0.35f * opacity)
        held -> (custom ?: MaterialTheme.colorScheme.primaryContainer).copy(alpha = 0.95f * opacity)
        else -> (custom ?: MaterialTheme.colorScheme.surfaceVariant).copy(alpha = 0.85f * opacity)
    }
    val border = when {
        held -> custom ?: MaterialTheme.colorScheme.primary
        else -> custom ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f * opacity)
    }
    val textColor = when {
        custom == null && held -> MaterialTheme.colorScheme.onPrimaryContainer
        custom == null -> MaterialTheme.colorScheme.onSurface
        custom.luminance() > 0.5f -> Color(0xFF101418)
        else -> Color.White
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .border(1.5.dp, border, shape)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(label) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        first.consume()
                        // Track every finger on this control. Releasing on the
                        // first finger up would drop the action while another
                        // finger is still holding the same button.
                        val pointers = mutableSetOf(first.id)
                        currentOnDown()
                        try {
                            while (pointers.isNotEmpty()) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    if (!change.pressed) {
                                        if (pointers.remove(change.id)) change.consume()
                                    } else if (change.id !in pointers) {
                                        pointers.add(change.id)
                                        change.consume()
                                    }
                                }
                            }
                        } finally {
                            currentOnUp()
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor,
            fontSize = if (label.length > 5) 11.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TouchpadSettingsScreen(
    config: TouchpadConfig,
    onChange: (TouchpadConfig) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Touchpad tuning",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                )
                Text(
                    "Applied live; movement stays on-device until sent.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Button(onClick = onClose) { Text("Done") }
        }

        TuningSlider(
            label = "Horizontal sensitivity",
            valueLabel = "×${formatOne(config.sensitivityX)}",
            value = config.sensitivityX,
            range = TouchpadConfig.MIN_SENSITIVITY..TouchpadConfig.MAX_SENSITIVITY,
            onChange = { onChange(config.copy(sensitivityX = it)) },
        )
        TuningSlider(
            label = "Vertical sensitivity",
            valueLabel = "×${formatOne(config.sensitivityY)}",
            value = config.sensitivityY,
            range = TouchpadConfig.MIN_SENSITIVITY..TouchpadConfig.MAX_SENSITIVITY,
            onChange = { onChange(config.copy(sensitivityY = it)) },
        )
        TuningSlider(
            label = "Acceleration",
            valueLabel = formatOne(config.acceleration),
            value = config.acceleration,
            range = 0f..2f,
            onChange = { onChange(config.copy(acceleration = it)) },
        )
        TuningSlider(
            label = "Smoothing",
            valueLabel = formatOne(config.smoothing),
            value = config.smoothing,
            range = 0f..0.9f,
            onChange = { onChange(config.copy(smoothing = it)) },
        )
        TuningSlider(
            label = "Touchpad width",
            valueLabel = "${(config.widthFraction * 100).roundToInt()}%",
            value = config.widthFraction,
            range = TouchpadConfig.MIN_WIDTH..TouchpadConfig.MAX_WIDTH,
            onChange = { onChange(config.copy(widthFraction = it)) },
        )
        ToggleRow(
            label = "Invert horizontal",
            checked = config.invertX,
            onChange = { onChange(config.copy(invertX = it)) },
        )
        ToggleRow(
            label = "Invert vertical",
            checked = config.invertY,
            onChange = { onChange(config.copy(invertY = it)) },
        )
        ToggleRow(
            label = "Tap touchpad = left click",
            checked = config.tapToClick,
            onChange = { onChange(config.copy(tapToClick = it)) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onChange(TouchpadConfig()) }) {
                Text("Reset defaults")
            }
        }
    }
}

@Composable
internal fun TuningSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            Text(
                valueLabel,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatOne(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)
