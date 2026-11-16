package com.phonecontroller.app.ui

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontroller.app.AppModel
import com.phonecontroller.app.input.ControlAction
import com.phonecontroller.app.input.InputTracker
import com.phonecontroller.app.input.Keycodes
import com.phonecontroller.app.input.Motion
import com.phonecontroller.app.input.MotionAccumulator
import com.phonecontroller.app.input.TouchpadConfig
import com.phonecontroller.app.input.TouchpadMapper
import com.phonecontroller.app.net.ConnState
import com.phonecontroller.app.net.DeltaEvent
import com.phonecontroller.app.net.PhoneConnection
import com.phonecontroller.app.net.Protocol
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

    val tracker = remember { InputTracker() }
    val motion = remember { MotionAccumulator() }
    var pressedIds by remember { mutableStateOf(setOf<String>()) }
    var toggledIds by remember { mutableStateOf(setOf<String>()) }
    var touchpadSaveJob by remember { mutableStateOf<Job?>(null) }

    val released = state !is ConnState.Paired

    // Safety: clear local highlights/state after disconnect/watchdog
    LaunchedEffect(released) {
        if (released) {
            touchpadSaveJob?.cancel()
            tracker.clear()
            motion.clear()
            pressedIds = emptySet()
            toggledIds = emptySet()
            showSettings = false
            showProfileManager = false
            isEditorMode = false
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
        val events = tracker.releaseAll()
        tracker.clear()
        motion.clear()
        pressedIds = emptySet()
        toggledIds = emptySet()
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
                showSettings = false
            },
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
            .safeDrawingPadding(),
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
    }
}

/**
 * High-precision swipe camera touchpad surface.
 */
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
private fun TuningSlider(
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
