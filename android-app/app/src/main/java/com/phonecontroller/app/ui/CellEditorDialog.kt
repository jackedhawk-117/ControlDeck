package com.phonecontroller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.phonecontroller.app.input.Keycodes
import com.phonecontroller.app.net.Protocol
import com.phonecontroller.app.profile.ButtonShape
import com.phonecontroller.app.profile.CellConfig
import com.phonecontroller.app.profile.CellType
import kotlin.math.roundToInt

/**
 * Mobile-gaming style control editor modal.
 *
 * Supports:
 * - Searchable key picker across all 100+ standard PC keyboard keys
 * - Key combo assignment
 * - Mouse button mapping (LMB, RMB, MMB, Back, Forward)
 * - Virtual analog joystick (WASD)
 * - Freeform touchpad region
 * - Button shape customization (Rounded Rectangle, Circle, Pill)
 * - Dimensions (Width & Height in dp)
 * - Opacity & Latching toggle
 */
@Composable
fun CellEditorDialog(
    cell: CellConfig,
    onSave: (CellConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(cell.type) }
    var label by remember { mutableStateOf(cell.label) }
    var isToggle by remember { mutableStateOf(cell.isToggle) }
    var joystickSprint by remember { mutableStateOf(cell.joystickSprint) }
    var selectedKeys by remember { mutableStateOf(cell.keys) }
    var selectedMouseButtons by remember { mutableStateOf(cell.mouseButtons) }
    var selectedShape by remember { mutableStateOf(cell.shape) }
    var widthDp by remember { mutableIntStateOf(cell.widthDp.coerceIn(40, 320)) }
    var heightDp by remember { mutableIntStateOf(cell.heightDp.coerceIn(40, 320)) }
    var opacity by remember { mutableFloatStateOf(cell.opacity.coerceIn(0.2f, 1.0f)) }
    var customColor by remember { mutableStateOf(cell.customColor) }

    // Key search & category filtering
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Configure Control",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        selectedType.name,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // Control Type Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val types = listOf(
                        CellType.KEY to "Key",
                        CellType.KEY_COMBO to "Combo",
                        CellType.MOUSE_BUTTON to "Mouse",
                        CellType.JOYSTICK to "Joystick",
                        CellType.TOUCHPAD to "Touchpad",
                    )
                    for ((t, name) in types) {
                        val active = selectedType == t
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    selectedType = t
                                    when (t) {
                                        CellType.KEY -> {
                                            if (selectedKeys.isEmpty()) selectedKeys = listOf(Keycodes.KEY_W)
                                            if (label.isBlank() || label == "JOYSTICK" || label == "TOUCHPAD") label = "W"
                                        }
                                        CellType.KEY_COMBO -> {
                                            if (selectedKeys.isEmpty()) selectedKeys = listOf(Keycodes.KEY_LEFTSHIFT, Keycodes.KEY_W)
                                            if (label.isBlank() || label == "JOYSTICK" || label == "TOUCHPAD") label = "SPRINT"
                                        }
                                        CellType.MOUSE_BUTTON -> {
                                            if (selectedMouseButtons.isEmpty()) selectedMouseButtons = listOf(Protocol.MOUSE_LEFT)
                                            if (label.isBlank() || label == "JOYSTICK" || label == "TOUCHPAD") label = "LMB"
                                        }
                                        CellType.JOYSTICK -> {
                                            label = "WASD"
                                            selectedShape = ButtonShape.CIRCLE
                                            widthDp = 130
                                            heightDp = 130
                                        }
                                        CellType.TOUCHPAD -> {
                                            label = "AIM TOUCHPAD"
                                            selectedShape = ButtonShape.ROUNDED_RECT
                                            widthDp = 200
                                            heightDp = 160
                                        }
                                        else -> {}
                                    }
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Label Input
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Display Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )

                // Shape & Dimensions Settings
                if (selectedType != CellType.TOUCHPAD) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Button Shape",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val shapes = listOf(
                                ButtonShape.ROUNDED_RECT to "Rounded Rect",
                                ButtonShape.CIRCLE to "Circle",
                                ButtonShape.PILL to "Pill",
                            )
                            for ((s, sName) in shapes) {
                                val active = selectedShape == s
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(
                                            if (active) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .border(
                                            1.dp,
                                            if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable {
                                            selectedShape = s
                                            if (s == ButtonShape.CIRCLE) {
                                                heightDp = widthDp
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        sName,
                                        fontSize = 11.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // Size controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (selectedShape == ButtonShape.CIRCLE) "Diameter: ${widthDp}dp"
                                else "Size: ${widthDp}dp × ${heightDp}dp",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val presets = listOf(50, 70, 90, 130)
                                for (p in presets) {
                                    Box(
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                                            .clickable {
                                                widthDp = p
                                                heightDp = if (selectedShape == ButtonShape.CIRCLE) p else (p * 0.8f).roundToInt()
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text("${p}dp", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        Slider(
                            value = widthDp.toFloat(),
                            onValueChange = {
                                widthDp = it.roundToInt()
                                if (selectedShape == ButtonShape.CIRCLE) heightDp = widthDp
                            },
                            valueRange = 40f..200f,
                        )

                        if (selectedShape != ButtonShape.CIRCLE) {
                            Slider(
                                value = heightDp.toFloat(),
                                onValueChange = { heightDp = it.roundToInt() },
                                valueRange = 40f..200f,
                            )
                        }
                    }
                }

                // Touchpads have no shape presets but do need explicit size
                // controls (the freeform canvas takes both dimensions).
                if (selectedType == CellType.TOUCHPAD) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Touchpad Size: ${widthDp}dp × ${heightDp}dp",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Slider(
                            value = widthDp.toFloat(),
                            onValueChange = { widthDp = it.roundToInt() },
                            valueRange = 60f..320f,
                        )
                        Slider(
                            value = heightDp.toFloat(),
                            onValueChange = { heightDp = it.roundToInt() },
                            valueRange = 60f..320f,
                        )
                    }
                }

                // Opacity Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Opacity: ${(opacity * 100).roundToInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 0.2f..1.0f,
                )

                // Tint palette. null = follow the app theme.
                Text(
                    "Button Color",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val palette = listOf(
                        null,
                        0xFFE53935, // red
                        0xFFFB8C00, // orange
                        0xFFFDD835, // yellow
                        0xFF43A047, // green
                        0xFF00ACC1, // cyan
                        0xFF1E88E5, // blue
                        0xFF8E24AA, // purple
                        0xFFD81B60, // pink
                    )
                    for (color in palette) {
                        val selected = customColor == color
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    if (color == null) MaterialTheme.colorScheme.surfaceVariant
                                    else Color(color)
                                )
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable { customColor = color },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color == null) {
                                Text(
                                    "A",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Toggle Switch (for single key / combo / mouse)
                if (selectedType == CellType.KEY || selectedType == CellType.KEY_COMBO || selectedType == CellType.MOUSE_BUTTON) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Toggle / Latching Input",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Tap once to hold, tap again to release",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = isToggle, onCheckedChange = { isToggle = it })
                    }
                }

                // Specific Type Configuration
                when (selectedType) {
                    CellType.KEY -> {
                        KeyPickerSection(
                            currentKey = selectedKeys.firstOrNull() ?: Keycodes.KEY_W,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            selectedCategory = selectedCategory,
                            onCategoryChange = { selectedCategory = it },
                            onKeySelected = { key ->
                                selectedKeys = listOf(key.code)
                                if (label.isBlank() || label == Keycodes.find(selectedKeys.first())?.label) {
                                    label = key.label
                                }
                            },
                        )
                    }

                    CellType.KEY_COMBO -> {
                        KeyComboSection(
                            selectedKeys = selectedKeys,
                            onChange = { keys ->
                                selectedKeys = keys
                                if (label.isBlank() || label.startsWith("COMBO")) {
                                    label = keys.joinToString("+") { Keycodes.labelFor(it) }
                                }
                            },
                        )
                    }

                    CellType.MOUSE_BUTTON -> {
                        MouseButtonSection(
                            selectedButton = selectedMouseButtons.firstOrNull() ?: Protocol.MOUSE_LEFT,
                            onSelect = { button, btnLabel ->
                                selectedMouseButtons = listOf(button)
                                if (label.isBlank() || listOf("LMB", "RMB", "MMB", "BACK", "FWD").contains(label)) {
                                    label = btnLabel
                                }
                            },
                        )
                    }

                    CellType.JOYSTICK -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "🕹️ Virtual Analog Joystick (WASD)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Translates thumb drag to W, A, S, D keyboard inputs. Supports 8-way diagonal chording (W+A, W+D, S+A, S+D) and deadzone centering.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Sprint at Full Deflection",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        if (joystickSprint) {
                                            "Push past 85% forward to hold Shift"
                                        } else {
                                            "Sprint chord disabled; joystick sends WASD only"
                                        },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = joystickSprint,
                                    onCheckedChange = { joystickSprint = it },
                                )
                            }
                        }
                    }

                    CellType.TOUCHPAD -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "🖱️ Freeform Camera Touchpad Region",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Swiping across this area moves the PC mouse camera at 120Hz with Q16.16 sub-pixel precision. Sensitivity and acceleration can be tuned in Touchpad Settings.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> {}
                }

                // Action Buttons (Cancel / Save)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val cleanLabel = if (label.isBlank()) {
                                when (selectedType) {
                                    CellType.KEY -> selectedKeys.firstOrNull()?.let { Keycodes.labelFor(it) } ?: "KEY"
                                    CellType.KEY_COMBO -> selectedKeys.joinToString("+") { Keycodes.labelFor(it) }
                                    CellType.MOUSE_BUTTON -> when (selectedMouseButtons.firstOrNull()) {
                                        Protocol.MOUSE_LEFT -> "LMB"
                                        Protocol.MOUSE_RIGHT -> "RMB"
                                        Protocol.MOUSE_MIDDLE -> "MMB"
                                        Protocol.MOUSE_BACK -> "BACK"
                                        Protocol.MOUSE_FORWARD -> "FWD"
                                        else -> "MOUSE"
                                    }
                                    CellType.JOYSTICK -> "WASD"
                                    CellType.TOUCHPAD -> "TOUCHPAD"
                                    else -> ""
                                }
                            } else {
                                label
                            }

                            onSave(
                                cell.copy(
                                    type = selectedType,
                                    label = cleanLabel,
                                    keys = if (selectedType == CellType.KEY || selectedType == CellType.KEY_COMBO) selectedKeys else emptyList(),
                                    mouseButtons = if (selectedType == CellType.MOUSE_BUTTON) selectedMouseButtons else emptyList(),
                                    isToggle = isToggle,
                                    joystickSprint = joystickSprint,
                                    shape = selectedShape,
                                    widthDp = widthDp,
                                    heightDp = heightDp,
                                    opacity = opacity,
                                    customColor = customColor,
                                ),
                            )
                        },
                    ) {
                        Text("Save Control")
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyPickerSection(
    currentKey: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    onKeySelected: (Keycodes.KeyInfo) -> Unit,
    header: String = "Selected Key",
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$header: ${Keycodes.labelFor(currentKey)}",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
        )

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search all keys (e.g. W, Enter, F5, Numpad)...", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        // Category Filter Chips
        val categories = listOf(
            "All", "Letters", "Movement", "Actions", "Numbers",
            "Numpad", "Function", "Modifiers", "Navigation", "Symbols", "System & Media",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (cat in categories) {
                    val active = cat == selectedCategory
                    Box(
                        modifier = Modifier
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp),
                            )
                            .border(
                                1.dp,
                                if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { onCategoryChange(cat) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            cat,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Filter keys by category and search query
        val filteredKeys = Keycodes.ALL_KEYS.filter { key ->
            val matchesCategory = selectedCategory == "All" || key.category.equals(selectedCategory, ignoreCase = true)
            val matchesSearch = searchQuery.isBlank() ||
                key.label.contains(searchQuery, ignoreCase = true) ||
                key.description.contains(searchQuery, ignoreCase = true) ||
                key.code.toString().contains(searchQuery)
            matchesCategory && matchesSearch
        }

        if (filteredKeys.isEmpty()) {
            Text(
                "No keys found matching '$searchQuery'",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            // Display keys in a responsive grid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val chunked = filteredKeys.chunked(4)
                for (row in chunked) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (k in row) {
                            val active = k.code == currentKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .border(
                                        if (active) 1.5.dp else 0.dp,
                                        if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onKeySelected(k) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        k.label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        k.description,
                                        fontSize = 8.sp,
                                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        if (row.size < 4) {
                            for (i in 0 until (4 - row.size)) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyComboSection(
    selectedKeys: List<Int>,
    onChange: (List<Int>) -> Unit,
) {
    var comboSearch by remember { mutableStateOf("") }
    var comboCategory by remember { mutableStateOf("All") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Keys held together (max 4), released in reverse order:",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val comboText = if (selectedKeys.isEmpty()) "None selected"
            else selectedKeys.joinToString(" + ") { Keycodes.labelFor(it) }
            Text(
                comboText,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }

        if (selectedKeys.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (key in selectedKeys) {
                    OutlinedButton(
                        onClick = { onChange(selectedKeys - key) },
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Text("${Keycodes.labelFor(key)} ✕", fontSize = 10.sp)
                    }
                }
            }
        }

        // Quick Presets
        val presets = listOf(
            "Shift + W" to listOf(Keycodes.KEY_LEFTSHIFT, Keycodes.KEY_W),
            "Ctrl + Space" to listOf(Keycodes.KEY_LEFTCTRL, Keycodes.KEY_SPACE),
            "Alt + F4" to listOf(Keycodes.KEY_LEFTALT, Keycodes.KEY_F4),
            "Ctrl + Shift" to listOf(Keycodes.KEY_LEFTCTRL, Keycodes.KEY_LEFTSHIFT),
            "Ctrl + C" to listOf(Keycodes.KEY_LEFTCTRL, Keycodes.KEY_C),
            "Ctrl + V" to listOf(Keycodes.KEY_LEFTCTRL, Keycodes.KEY_V),
        )

        Text("Common Presets:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val chunkedPresets = presets.chunked(3)
        for (chunk in chunkedPresets) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for ((name, keys) in chunk) {
                    OutlinedButton(
                        onClick = { onChange(keys) },
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Text(name, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }

        Text(
            "Add any key to the combination:",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyPickerSection(
            currentKey = selectedKeys.lastOrNull() ?: Keycodes.KEY_W,
            searchQuery = comboSearch,
            onSearchQueryChange = { comboSearch = it },
            selectedCategory = comboCategory,
            onCategoryChange = { comboCategory = it },
            onKeySelected = { key ->
                if (key.code !in selectedKeys && selectedKeys.size < 4) {
                    onChange(selectedKeys + key.code)
                }
            },
            header = "Last key (tap to add)",
        )
    }
}

@Composable
private fun MouseButtonSection(
    selectedButton: Int,
    onSelect: (Int, String) -> Unit,
) {
    val buttons = listOf(
        Triple(Protocol.MOUSE_LEFT, "LMB", "Left Mouse Button (Primary Fire)"),
        Triple(Protocol.MOUSE_RIGHT, "RMB", "Right Mouse Button (Aim / Alt Fire)"),
        Triple(Protocol.MOUSE_MIDDLE, "MMB", "Middle Click / Scroll Wheel Click"),
        Triple(Protocol.MOUSE_BACK, "BACK", "Thumb Back (Mouse Button 4)"),
        Triple(Protocol.MOUSE_FORWARD, "FWD", "Thumb Forward (Mouse Button 5)"),
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((btn, label, desc) in buttons) {
            val active = btn == selectedButton
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(btn, label) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        label,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        desc,
                        fontSize = 11.sp,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active) {
                    Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
