package com.phonecontroller.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontroller.app.input.Keycodes
import com.phonecontroller.app.profile.ButtonShape
import com.phonecontroller.app.profile.CellConfig
import com.phonecontroller.app.profile.CellType
import com.phonecontroller.app.profile.DefaultProfiles
import com.phonecontroller.app.profile.GameProfile
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Full-screen, unconstrained mobile-gaming style layout editor (spec §3 & §11 Milestone 4).
 *
 * Provides:
 * - Freeform screen canvas where buttons and analog controls can be dragged anywhere.
 * - Normalized coordinate tracking (xPct, yPct) for multi-aspect ratio responsiveness.
 * - Add/Duplicate/Delete controls, virtual analog WASD joysticks, and camera touchpads.
 * - Visual selection bounding box, quick-resize, shape toggling, and full modal configuration.
 * - Multi-level Undo & Reset to factory presets.
 */
@Composable
fun GridEditorScreen(
    initialProfile: GameProfile,
    onSave: (GameProfile) -> Unit,
    onCancel: () -> Unit,
) {
    var profile by remember { mutableStateOf(initialProfile.copy(cells = initialProfile.normalizedCells())) }
    val history = remember { ArrayDeque<GameProfile>() }
    var selectedCellId by remember { mutableStateOf<String?>(null) }
    var editingCell by remember { mutableStateOf<CellConfig?>(null) }

    fun pushHistory(next: GameProfile) {
        history.addLast(profile)
        if (history.size > 25) history.removeFirst()
        profile = next
    }

    /** Record the pre-drag state once per gesture (undo granularity). */
    fun recordHistory() {
        history.addLast(profile)
        if (history.size > 25) history.removeFirst()
    }

    fun undo() {
        if (history.isNotEmpty()) {
            profile = history.removeLast()
            if (profile.cells.none { it.id == selectedCellId }) {
                selectedCellId = null
            }
        }
    }

    fun resetDefaults() {
        val default = when (profile.id) {
            DefaultProfiles.FPS_PROFILE_ID -> DefaultProfiles.fpsProfile()
            DefaultProfiles.THIRD_PERSON_PROFILE_ID -> DefaultProfiles.thirdPersonProfile()
            DefaultProfiles.RACING_PROFILE_ID -> DefaultProfiles.racingProfile()
            DefaultProfiles.STRATEGY_PROFILE_ID -> DefaultProfiles.strategyProfile()
            else -> DefaultProfiles.generalProfile()
        }
        pushHistory(default.copy(id = profile.id, name = profile.name))
        selectedCellId = null
    }

    fun addNewControl(type: CellType) {
        val newCell = when (type) {
            CellType.JOYSTICK -> CellConfig(
                id = UUID.randomUUID().toString(),
                label = "WASD",
                type = CellType.JOYSTICK,
                xPct = 0.50f,
                yPct = 0.50f,
                widthDp = 130,
                heightDp = 130,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            )
            CellType.TOUCHPAD -> CellConfig(
                id = UUID.randomUUID().toString(),
                label = "AIM TOUCHPAD",
                type = CellType.TOUCHPAD,
                xPct = 0.50f,
                yPct = 0.50f,
                widthDp = 200,
                heightDp = 160,
                shape = ButtonShape.ROUNDED_RECT,
                opacity = 0.65f,
            )
            else -> CellConfig(
                id = UUID.randomUUID().toString(),
                label = "KEY",
                type = CellType.KEY,
                keys = listOf(Keycodes.KEY_SPACE),
                xPct = 0.50f,
                yPct = 0.50f,
                widthDp = 68,
                heightDp = 68,
                shape = ButtonShape.CIRCLE,
                opacity = 0.85f,
            )
        }
        pushHistory(profile.copy(cells = profile.cells + newCell))
        selectedCellId = newCell.id
    }

    fun duplicateSelected() {
        val current = profile.cells.firstOrNull { it.id == selectedCellId } ?: return
        val copy = current.copy(
            id = UUID.randomUUID().toString(),
            xPct = (current.xPct + 0.05f).coerceAtMost(0.92f),
            yPct = (current.yPct + 0.05f).coerceAtMost(0.92f),
        )
        pushHistory(profile.copy(cells = profile.cells + copy))
        selectedCellId = copy.id
    }

    fun deleteSelected() {
        val id = selectedCellId ?: return
        pushHistory(profile.withCellRemoved(id))
        selectedCellId = null
    }

    val selectedCell = profile.cells.firstOrNull { it.id == selectedCellId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        // ---- Canvas Area ----
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        selectedCellId = null
                    }
                },
        ) {
            val canvasWidthPx = constraints.maxWidth.toFloat()
            val canvasHeightPx = constraints.maxHeight.toFloat()
            val density = LocalDensity.current

            // Subtle background grid dots
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 40.dp.toPx()
                var x = step
                while (x < size.width) {
                    var y = step
                    while (y < size.height) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.08f),
                            radius = 1.5.dp.toPx(),
                            center = Offset(x, y),
                        )
                        y += step
                    }
                    x += step
                }
            }

            // Render all canvas cells
            for (cell in profile.cells) {
                val isSelected = cell.id == selectedCellId
                val widthPx = with(density) { cell.widthDp.dp.toPx() }
                val heightPx = with(density) { cell.heightDp.dp.toPx() }

                val centerX = cell.xPct * canvasWidthPx
                val centerY = cell.yPct * canvasHeightPx
                val leftPx = (centerX - widthPx / 2f)
                    .coerceIn(0f, (canvasWidthPx - widthPx).coerceAtLeast(0f))
                val topPx = (centerY - heightPx / 2f)
                    .coerceIn(0f, (canvasHeightPx - heightPx).coerceAtLeast(0f))

                val clipShape = when (cell.shape) {
                    ButtonShape.CIRCLE -> CircleShape
                    ButtonShape.PILL -> RoundedCornerShape(cell.heightDp.dp / 2)
                    ButtonShape.ROUNDED_RECT -> RoundedCornerShape(profile.cornerRadiusDp.dp)
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                        .size(cell.widthDp.dp, cell.heightDp.dp)
                        .pointerInput(cell.id, canvasWidthPx, canvasHeightPx) {
                            detectTapGestures {
                                selectedCellId = cell.id
                            }
                        }
                        .pointerInput(cell.id, canvasWidthPx, canvasHeightPx) {
                            detectDragGestures(
                                onDragStart = {
                                    selectedCellId = cell.id
                                    recordHistory()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    // Read the *current* state each event: the
                                    // gesture coroutine's captured `cell` is the
                                    // position at gesture start, so using it
                                    // would make the control snap back instead
                                    // of accumulating the drag.
                                    val current = profile.cells.firstOrNull { it.id == cell.id }
                                    if (current != null) {
                                        val halfW = with(density) { current.widthDp.dp.toPx() } / 2f
                                        val halfH = with(density) { current.heightDp.dp.toPx() } / 2f
                                        val minX = (halfW / canvasWidthPx).coerceAtMost(0.5f)
                                        val minY = (halfH / canvasHeightPx).coerceAtMost(0.5f)
                                        val newXPct = (current.xPct + dragAmount.x / canvasWidthPx)
                                            .coerceIn(minX, 1f - minX)
                                        val newYPct = (current.yPct + dragAmount.y / canvasHeightPx)
                                            .coerceIn(minY, 1f - minY)
                                        profile = profile.withCellUpdated(
                                            current.copy(xPct = newXPct, yPct = newYPct)
                                        )
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Element visual
                    when (cell.type) {
                        CellType.JOYSTICK -> {
                            JoystickPreview(
                                sizeDp = cell.widthDp,
                                opacity = cell.opacity,
                                isSelected = isSelected,
                            )
                        }

                        CellType.TOUCHPAD -> {
                            TouchpadPreview(
                                label = cell.label,
                                isSelected = isSelected,
                                opacity = cell.opacity,
                                shape = clipShape,
                            )
                        }

                        else -> {
                            ButtonPreview(
                                cell = cell,
                                isSelected = isSelected,
                                shape = clipShape,
                            )
                        }
                    }

                    // Selected highlight frame
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(2.dp, MaterialTheme.colorScheme.primary, clipShape),
                        )
                    }
                }
            }
        }

        // ---- Top Header Bar ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        profile.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Drag controls anywhere · Tap to configure",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { addNewControl(CellType.KEY) },
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("+ Button", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { addNewControl(CellType.JOYSTICK) },
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("+ Joystick", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { addNewControl(CellType.TOUCHPAD) },
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("+ Touchpad", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = ::undo,
                        enabled = history.isNotEmpty(),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("Undo", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = ::resetDefaults,
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("Reset", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("Cancel", fontSize = 11.sp)
                    }
                    Button(
                        onClick = { onSave(profile) },
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("Save & Exit", fontSize = 11.sp)
                    }
                }
            }
        }

        // ---- Bottom Inspector Bar (Shown when an element is selected) ----
        selectedCell?.let { cell ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            cell.label.ifBlank { cell.type.name },
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${cell.type.name} · ${cell.widthDp}×${cell.heightDp}dp",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Quick shape toggle
                        if (cell.type != CellType.JOYSTICK && cell.type != CellType.TOUCHPAD) {
                            val nextShape = when (cell.shape) {
                                ButtonShape.ROUNDED_RECT -> ButtonShape.CIRCLE
                                ButtonShape.CIRCLE -> ButtonShape.PILL
                                ButtonShape.PILL -> ButtonShape.ROUNDED_RECT
                            }
                            OutlinedButton(
                                onClick = {
                                    pushHistory(
                                        profile.withCellUpdated(
                                            cell.copy(
                                                shape = nextShape,
                                                heightDp = if (nextShape == ButtonShape.CIRCLE) cell.widthDp else cell.heightDp,
                                            )
                                        )
                                    )
                                },
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text(
                                    when (cell.shape) {
                                        ButtonShape.ROUNDED_RECT -> "Shape: Rect"
                                        ButtonShape.CIRCLE -> "Shape: Circle"
                                        ButtonShape.PILL -> "Shape: Pill"
                                    },
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        // Quick size - / +
                        OutlinedButton(
                            onClick = {
                                val newW = (cell.widthDp - 10).coerceAtLeast(40)
                                val newH = if (cell.shape == ButtonShape.CIRCLE) newW else (cell.heightDp - 10).coerceAtLeast(40)
                                pushHistory(profile.withCellUpdated(cell.copy(widthDp = newW, heightDp = newH)))
                            },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("- Size", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val newW = (cell.widthDp + 10).coerceAtMost(200)
                                val newH = if (cell.shape == ButtonShape.CIRCLE) newW else (cell.heightDp + 10).coerceAtMost(200)
                                pushHistory(profile.withCellUpdated(cell.copy(widthDp = newW, heightDp = newH)))
                            },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("+ Size", fontSize = 11.sp)
                        }

                        // Configure button
                        Button(
                            onClick = { editingCell = cell },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("⚙️ Configure", fontSize = 11.sp)
                        }

                        // Duplicate
                        OutlinedButton(
                            onClick = ::duplicateSelected,
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("Duplicate", fontSize = 11.sp)
                        }

                        // Delete
                        Button(
                            onClick = ::deleteSelected,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("🗑️ Delete", fontSize = 11.sp)
                        }

                        // Close inspector
                        OutlinedButton(
                            onClick = { selectedCellId = null },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("✕", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    // Modal Cell Editor Dialog
    editingCell?.let { cell ->
        CellEditorDialog(
            cell = cell,
            onSave = { updated ->
                pushHistory(profile.withCellUpdated(updated))
                editingCell = null
            },
            onDismiss = { editingCell = null },
        )
    }
}

@Composable
private fun JoystickPreview(
    sizeDp: Int,
    opacity: Float,
    isSelected: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f * opacity))
            .border(
                2.dp,
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f * opacity),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Inner knob preview
        Box(
            modifier = Modifier
                .size((sizeDp * 0.38f).dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f * opacity))
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.9f * opacity), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "WASD",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TouchpadPreview(
    label: String,
    isSelected: Boolean,
    opacity: Float,
    shape: androidx.compose.ui.graphics.Shape,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f * opacity))
            .border(
                2.dp,
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f * opacity),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "🖱️ $label",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
            Text(
                "Camera Drag Surface",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ButtonPreview(
    cell: CellConfig,
    isSelected: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
) {
    val custom = cell.customColor?.let { Color(it) }
    val bg = (custom ?: MaterialTheme.colorScheme.surfaceVariant).copy(alpha = cell.opacity)
    val border = if (isSelected) MaterialTheme.colorScheme.primary
    else custom ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val textColor = when {
        custom == null -> MaterialTheme.colorScheme.onSurface
        custom.luminance() > 0.5f -> Color(0xFF101418)
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(bg)
            .border(1.5.dp, border, shape)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                cell.label,
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = if (cell.label.length > 5) 11.sp else 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (cell.isToggle) {
                Text(
                    "TOGGLE",
                    color = if (custom != null) textColor else MaterialTheme.colorScheme.primary,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}
