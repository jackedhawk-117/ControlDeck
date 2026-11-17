package com.phonecontroller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontroller.app.input.GyroActivationMode
import com.phonecontroller.app.input.GyroConfig
import com.phonecontroller.app.input.GyroSensorSource
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Gyroscope tuning screen (Milestone 6).
 *
 * Every control applies live to the running [com.phonecontroller.app.input.GyroMapper];
 * the caller persists the profile after the sliders settle. Calibration and
 * recentering are handled by the mapper so the UI stays declarative.
 */
@Composable
fun GyroSettingsScreen(
    config: GyroConfig,
    gyroAvailable: Boolean,
    orientationAvailable: Boolean,
    orientationLabel: String,
    sensorRatesHz: Pair<Int, Int>,
    active: Boolean,
    calibrationStatus: String?,
    onChange: (GyroConfig) -> Unit,
    onCalibrate: () -> Unit,
    onRecenter: () -> Unit,
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
                    "Gyro aiming",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                )
                Text(
                    "Yaw and pitch of the screen normal drive the camera. Roll is ignored.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Button(onClick = onClose) { Text("Done") }
        }

        if (!gyroAvailable || !orientationAvailable) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Gyro unavailable on this device",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        buildString {
                            if (!gyroAvailable) append("No TYPE_GYROSCOPE sensor. ")
                            if (!orientationAvailable) {
                                append("No rotation-vector sensor; a stable yaw/pitch reference is impossible. ")
                            }
                            append("A profile still imports, but gyro cells stay disabled.")
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "Sensors",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Gyro ${sensorRatesHz.first} Hz · $orientationLabel ${sensorRatesHz.second} Hz",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Status: ${when {
                        !config.enabled -> "disabled in this profile"
                        active -> "aiming active"
                        else -> "armed, waiting for activation"
                    }}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ToggleRow(
            label = "Enable gyro aiming in this profile",
            checked = config.enabled,
            onChange = { onChange(config.copy(enabled = it)) },
        )

        ChoiceRow(
            label = "Activation",
            options = listOf(
                GyroActivationMode.ALWAYS.name to "Always",
                GyroActivationMode.HOLD.name to "Hold button",
                GyroActivationMode.TOGGLE.name to "Toggle button",
                GyroActivationMode.REGION.name to "Touch region",
            ),
            selected = config.activationMode.name,
            onSelect = { name ->
                onChange(
                    config.copy(
                        activationMode = GyroActivationMode.entries
                            .firstOrNull { it.name == name } ?: config.activationMode,
                    ),
                )
            },
        )

        ChoiceRow(
            label = "Sensor source",
            options = listOf(
                GyroSensorSource.FUSED.name to "Fused (drift-free)",
                GyroSensorSource.GYRO.name to "Raw gyro (high rate)",
            ),
            selected = config.sensorSource.name,
            onSelect = { name ->
                onChange(
                    config.copy(
                        sensorSource = GyroSensorSource.entries
                            .firstOrNull { it.name == name } ?: config.sensorSource,
                    ),
                )
            },
        )

        Text(
            "Fused uses the rotation vector and never drifts; raw gyro runs at the "
                + "input tick rate and drifts until you recenter. Both need calibration "
                + "to remove hand tremor at rest.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TuningSlider(
            label = "Horizontal sensitivity",
            valueLabel = "${formatGyro(config.sensitivityX)} px/°",
            value = config.sensitivityX,
            range = GyroConfig.MIN_SENSITIVITY..GyroConfig.MAX_SENSITIVITY,
            onChange = { onChange(config.copy(sensitivityX = it)) },
        )
        TuningSlider(
            label = "Vertical sensitivity",
            valueLabel = "${formatGyro(config.sensitivityY)} px/°",
            value = config.sensitivityY,
            range = GyroConfig.MIN_SENSITIVITY..GyroConfig.MAX_SENSITIVITY,
            onChange = { onChange(config.copy(sensitivityY = it)) },
        )
        TuningSlider(
            label = "Aim (ADS/RMB) multiplier",
            valueLabel = "×${formatGyro(config.adsMultiplier)}",
            value = config.adsMultiplier,
            range = GyroConfig.MIN_ADS_MULTIPLIER..GyroConfig.MAX_ADS_MULTIPLIER,
            onChange = { onChange(config.copy(adsMultiplier = it)) },
        )
        TuningSlider(
            label = "Deadzone",
            valueLabel = "${formatGyro(config.deadzoneDps)} °/s",
            value = config.deadzoneDps,
            range = 0f..GyroConfig.MAX_DEADZONE_DPS,
            onChange = { onChange(config.copy(deadzoneDps = it)) },
        )
        TuningSlider(
            label = "Smoothing",
            valueLabel = formatGyro(config.smoothing),
            value = config.smoothing,
            range = 0f..0.9f,
            onChange = { onChange(config.copy(smoothing = it)) },
        )
        TuningSlider(
            label = "Maximum pixels per tick",
            valueLabel = config.maxPixelsPerTick.roundToInt().toString(),
            value = config.maxPixelsPerTick,
            range = GyroConfig.MIN_PIXELS_PER_TICK..GyroConfig.MAX_PIXELS_PER_TICK,
            onChange = { onChange(config.copy(maxPixelsPerTick = it)) },
        )

        ToggleRow(
            label = "Enable horizontal (yaw) axis",
            checked = config.enableYaw,
            onChange = { onChange(config.copy(enableYaw = it)) },
        )
        ToggleRow(
            label = "Enable vertical (pitch) axis",
            checked = config.enablePitch,
            onChange = { onChange(config.copy(enablePitch = it)) },
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
            label = "Re-prime neutral pose after hold release",
            checked = config.recenterOnRelease,
            onChange = { onChange(config.copy(recenterOnRelease = it)) },
        )

        if (config.activationMode == GyroActivationMode.REGION) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Activation region",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "A translucent rectangle is drawn on the gameplay canvas. "
                            + "Touch anywhere inside it to aim; the touch is still "
                            + "delivered to the controls underneath.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TuningSlider(
                        label = "Region left",
                        valueLabel = "${(config.regionXPct * 100).roundToInt()}%",
                        value = config.regionXPct,
                        range = 0f..0.9f,
                        onChange = {
                            onChange(
                                config.copy(regionXPct = it).let { c ->
                                    c.copy(
                                        regionWidthPct = c.regionWidthPct
                                            .coerceAtMost((1f - c.regionXPct).coerceAtLeast(GyroConfig.MIN_REGION)),
                                    )
                                },
                            )
                        },
                    )
                    TuningSlider(
                        label = "Region top",
                        valueLabel = "${(config.regionYPct * 100).roundToInt()}%",
                        value = config.regionYPct,
                        range = 0f..0.9f,
                        onChange = {
                            onChange(
                                config.copy(regionYPct = it).let { c ->
                                    c.copy(
                                        regionHeightPct = c.regionHeightPct
                                            .coerceAtMost((1f - c.regionYPct).coerceAtLeast(GyroConfig.MIN_REGION)),
                                    )
                                },
                            )
                        },
                    )
                    TuningSlider(
                        label = "Region width",
                        valueLabel = "${(config.regionWidthPct * 100).roundToInt()}%",
                        value = config.regionWidthPct,
                        range = GyroConfig.MIN_REGION..
                            (1f - config.regionXPct)
                                .coerceAtLeast(GyroConfig.MIN_REGION + 0.01f),
                        onChange = { onChange(config.copy(regionWidthPct = it)) },
                    )
                    TuningSlider(
                        label = "Region height",
                        valueLabel = "${(config.regionHeightPct * 100).roundToInt()}%",
                        value = config.regionHeightPct,
                        range = GyroConfig.MIN_REGION..
                            (1f - config.regionYPct)
                                .coerceAtLeast(GyroConfig.MIN_REGION + 0.01f),
                        onChange = { onChange(config.copy(regionHeightPct = it)) },
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Calibration & recentering",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Bias: ${formatBias(config.biasX)} / ${formatBias(config.biasY)} / "
                        + "${formatBias(config.biasZ)} rad/s",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Calibration samples the gyroscope for one second; keep the phone "
                        + "still on a stable surface. Recentering stores the current "
                        + "pose as neutral without moving the camera.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (calibrationStatus != null) {
                    Text(
                        calibrationStatus,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRecenter) {
                        Text("Recenter now")
                    }
                    Button(
                        onClick = onCalibrate,
                        enabled = config.enabled && gyroAvailable && orientationAvailable,
                    ) {
                        Text("Calibrate (1 s)")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    onChange(
                        GyroConfig(
                            enabled = config.enabled,
                            biasX = config.biasX,
                            biasY = config.biasY,
                            biasZ = config.biasZ,
                        ),
                    )
                },
            ) {
                Text("Reset tuning defaults")
            }
        }
    }
}

/**
 * Single-select chip row used for the activation mode and sensor source.
 * Values are enum names so the caller can map them back defensively.
 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((value, text) in options) {
                val active = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onSelect(value) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun formatGyro(value: Float): String =
    String.format(Locale.US, "%.2f", value)

private fun formatBias(value: Float): String =
    String.format(Locale.US, "%.3f", value)
