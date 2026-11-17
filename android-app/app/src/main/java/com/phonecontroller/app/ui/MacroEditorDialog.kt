package com.phonecontroller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.phonecontroller.app.input.Keycodes
import com.phonecontroller.app.profile.MacroConfig
import com.phonecontroller.app.profile.MacroLimits
import com.phonecontroller.app.profile.MacroStep
import com.phonecontroller.app.profile.MacroStepType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Editor for a phone-side macro.
 *
 * The list screen keeps the sequence compact; tapping a step opens its
 * dedicated editor so key selection, hold timing and inter-step delay do not
 * crowd the whole macro. All values are clamped by [MacroConfig.sanitized] on
 * save, and Save is disabled when the current plan exceeds the duration cap.
 */
@Composable
fun MacroEditorDialog(
    initial: MacroConfig,
    onSave: (MacroConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var config by remember { mutableStateOf(initial.sanitized()) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

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
                val index = editingIndex
                if (index != null && index in config.steps.indices) {
                    StepEditor(
                        index = index,
                        step = config.steps[index],
                        onChange = { updated ->
                            config = config.copy(
                                steps = config.steps.toMutableList().also { it[index] = updated },
                            )
                        },
                        onBack = { editingIndex = null },
                    )
                } else {
                    MacroListEditor(
                        config = config,
                        onChange = { config = it },
                        onEditStep = { editingIndex = it },
                        onDismiss = onDismiss,
                        onSave = { onSave(config.sanitized()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroListEditor(
    config: MacroConfig,
    onChange: (MacroConfig) -> Unit,
    onEditStep: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val estimated = config.estimatedDurationMs
    val tooLong = estimated > MacroLimits.MAX_DURATION_MS
    val runnable = config.sanitized().isRunnable()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "Macro Editor",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Runs on the phone; tap the macro button again to stop.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatMacroDuration(estimated),
            color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Repeat",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "×${config.repeatCount}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
        Slider(
            value = config.repeatCount.toFloat(),
            onValueChange = {
                onChange(config.copy(repeatCount = it.roundToInt().coerceIn(1, MacroLimits.MAX_REPEAT)))
            },
            valueRange = 1f..MacroLimits.MAX_REPEAT.toFloat(),
            steps = MacroLimits.MAX_REPEAT - 2,
        )
    }

    if (config.steps.isEmpty()) {
        Text(
            "No steps yet. Add a Tap, Hold, Release or Wait step below.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }

    config.steps.forEachIndexed { index, step ->
        MacroStepCard(
            index = index,
            step = step,
            canMoveUp = index > 0,
            canMoveDown = index < config.steps.lastIndex,
            onEdit = { onEditStep(index) },
            onMoveUp = {
                val steps = config.steps.toMutableList()
                val item = steps.removeAt(index)
                steps.add(index - 1, item)
                onChange(config.copy(steps = steps))
            },
            onMoveDown = {
                val steps = config.steps.toMutableList()
                val item = steps.removeAt(index)
                steps.add(index + 1, item)
                onChange(config.copy(steps = steps))
            },
            onDelete = {
                onChange(config.copy(steps = config.steps.filterIndexed { i, _ -> i != index }))
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val atLimit = config.steps.size >= MacroLimits.MAX_STEPS
        for (type in MacroStepType.entries) {
            OutlinedButton(
                onClick = {
                    if (!atLimit) {
                        onChange(
                            config.copy(
                                steps = config.steps + defaultStep(type),
                            ),
                        )
                    }
                },
                enabled = !atLimit,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("+ ${typeLabel(type)}", fontSize = 11.sp)
            }
        }
    }
    if (config.steps.size >= MacroLimits.MAX_STEPS) {
        Text(
            "Step limit reached (${MacroLimits.MAX_STEPS}).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }

    Text(
        "Limits: ${MacroLimits.MAX_STEPS} steps, ×${MacroLimits.MAX_REPEAT} repeats, " +
            "${MacroLimits.MAX_DURATION_MS / 1000} s total. " +
            "Keys are released in reverse order when a tap or the macro ends.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
    )
    if (tooLong) {
        Text(
            "This plan is ${formatMacroDuration(estimated)}; reduce repeats, holds or delays to fit under " +
                "${formatMacroDuration(MacroLimits.MAX_DURATION_MS.toLong())}.",
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp,
        )
    }
    if (!runnable && config.steps.isNotEmpty()) {
        Text(
            "Add at least one key step before saving.",
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSave,
            enabled = config.steps.isNotEmpty() && runnable && !tooLong,
        ) {
            Text("Save Macro")
        }
    }
}

@Composable
private fun MacroStepCard(
    index: Int,
    step: MacroStep,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${index + 1}. ${typeLabel(step.type)}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stepSummary(step),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MacroIconButton("↑", enabled = canMoveUp, onClick = onMoveUp)
            MacroIconButton("↓", enabled = canMoveDown, onClick = onMoveDown)
            MacroIconButton("✕", enabled = true, onClick = onDelete)
        }
    }
}

@Composable
private fun MacroIconButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
        modifier = Modifier
            .padding(start = 3.dp)
            .height(28.dp),
    ) {
        Text(label, fontSize = 11.sp)
    }
}

@Composable
private fun StepEditor(
    index: Int,
    step: MacroStep,
    onChange: (MacroStep) -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Step ${index + 1}",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(onClick = onBack) { Text("Back to steps") }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (type in MacroStepType.entries) {
            MacroTypeChip(
                label = typeLabel(type),
                active = step.type == type,
                modifier = Modifier.weight(1f),
                onClick = {
                    val keys = when {
                        type == MacroStepType.WAIT -> emptyList()
                        step.keys.isEmpty() -> defaultStep(type).keys
                        else -> step.keys
                    }
                    onChange(step.copy(type = type, keys = keys))
                },
            )
        }
    }

    if (step.type == MacroStepType.WAIT) {
        Text(
            "Wait steps send no keys; they only pause the macro.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            when (step.type) {
                MacroStepType.TAP -> "Keys pressed together, held for the tap duration, then released."
                MacroStepType.HOLD -> "Keys stay held until a matching Release step or the end of the macro."
                MacroStepType.RELEASE -> "Releases the newest held step with exactly these keys."
                MacroStepType.WAIT -> ""
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyComboSection(
            selectedKeys = step.keys,
            onChange = { onChange(step.copy(keys = it)) },
            description = "Keys for this step (max ${MacroLimits.MAX_KEYS_PER_STEP}):",
        )
    }

    if (step.type == MacroStepType.TAP) {
        TimingSlider(
            label = "Tap hold duration",
            value = step.holdMs,
            range = 0..MacroLimits.MAX_HOLD_MS,
            onChange = { onChange(step.copy(holdMs = it)) },
        )
    }
    TimingSlider(
        label = "Delay after step",
        value = step.delayMs,
        range = 0..MacroLimits.MAX_DELAY_MS,
        onChange = { onChange(step.copy(delayMs = it)) },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(onClick = onBack) { Text("Done") }
    }
}

@Composable
private fun TimingSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "$value ms",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = 39,
        )
    }
}

@Composable
private fun MacroTypeChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
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
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun defaultStep(type: MacroStepType): MacroStep = when (type) {
    MacroStepType.TAP -> MacroStep(
        type = type,
        keys = listOf(Keycodes.KEY_W),
        holdMs = MacroLimits.DEFAULT_HOLD_MS,
        delayMs = MacroLimits.DEFAULT_DELAY_MS,
    )

    MacroStepType.HOLD,
    MacroStepType.RELEASE,
    -> MacroStep(
        type = type,
        keys = listOf(Keycodes.KEY_LEFTSHIFT),
        delayMs = MacroLimits.DEFAULT_DELAY_MS,
    )

    MacroStepType.WAIT -> MacroStep(
        type = type,
        keys = emptyList(),
        delayMs = 100,
    )
}

private fun typeLabel(type: MacroStepType): String = when (type) {
    MacroStepType.TAP -> "Tap"
    MacroStepType.HOLD -> "Hold"
    MacroStepType.RELEASE -> "Release"
    MacroStepType.WAIT -> "Wait"
}

private fun stepSummary(step: MacroStep): String {
    val keys = step.keys.joinToString(" + ") { Keycodes.labelFor(it) }
    return when (step.type) {
        MacroStepType.TAP -> "Tap $keys · hold ${step.holdMs} ms · wait ${step.delayMs} ms"
        MacroStepType.HOLD -> "Hold $keys · wait ${step.delayMs} ms"
        MacroStepType.RELEASE -> "Release $keys · wait ${step.delayMs} ms"
        MacroStepType.WAIT -> "Wait ${step.delayMs} ms"
    }
}

private fun formatMacroDuration(ms: Long): String =
    if (ms < 1_000) "$ms ms" else String.format(Locale.US, "%.2f s", ms / 1_000.0)
