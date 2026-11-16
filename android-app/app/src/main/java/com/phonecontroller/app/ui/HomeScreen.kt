package com.phonecontroller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontroller.app.AppModel
import com.phonecontroller.app.net.DiscoveredPc
import com.phonecontroller.app.net.Discovery
import com.phonecontroller.app.net.DiscoveryResult
import com.phonecontroller.app.net.LocalNetwork
import kotlinx.coroutines.launch

/**
 * Home / connection screen (spec §9.1–9.2): discovery, manual IP entry,
 * PIN pairing and clear connection status.
 */
@Composable
fun HomeScreen(model: AppModel) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("41000") }
    var pin by remember { mutableStateOf("") }
    var pcs by remember { mutableStateOf<List<DiscoveredPc>>(emptyList()) }
    var scannedNetworks by remember { mutableStateOf<List<LocalNetwork>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Edge-to-edge is mandatory on Android 15+; keep the title and
            // fields clear of the status bar, cutout and navigation bar.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "PhoneController",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Turn this phone into a wireless keyboard + mouse for your PC.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )

        StatusLine(model)

        // ---- discovery ----
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Find your PC", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Button(
                    onClick = {
                        scanning = true
                        model.scope.launch {
                            val result: DiscoveryResult = Discovery.discoverDetailed()
                            pcs = result.pcs
                            scannedNetworks = result.networks
                            scanning = false
                        }
                    },
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (scanning) "Scanning…" else "Scan local network")
                }
                if (!scanning && scannedNetworks.isNotEmpty()) {
                    Text(
                        "Probed: " + scannedNetworks.joinToString(" · ") {
                            "${it.label} ${it.address.hostAddress}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                if (pcs.isEmpty() && !scanning) {
                    Text(
                        "No PCs found yet. Keep the receiver running and the PC on the same Wi-Fi or phone hotspot, then scan again.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                if (pcs.isEmpty() && !scanning) {
                    Text(
                        "PC on your phone's hotspot? Join the hotspot from the PC, then scan — every local interface is probed.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                for (pc in pcs) {
                    OutlinedButton(
                        onClick = {
                            host = pc.address.hostAddress ?: ""
                            port = pc.announce.tcpPort.toString()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${pc.announce.name} — ${pc.address.hostAddress}" +
                                (pc.via?.let { " · ${it.label}" } ?: ""),
                        )
                    }
                }
            }
        }

        // ---- manual connection ----
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Connect manually", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("PC address") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    label = { Text("Pairing PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        model.connect(
                            host = host.trim(),
                            tcpPort = port.toIntOrNull() ?: 41000,
                            pin = pin,
                        )
                    },
                    enabled = !model.connecting && host.isNotBlank() && pin.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (model.connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (model.connecting) "Pairing…" else "Pair with PC")
                }
            }
        }

        Text(
            "Tip: run `phonecontroller-receiver --pair` on the PC to print this run's PIN.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatusLine(model: AppModel) {
    val stateFlow = model.connection?.state
    val byState = stateFlow?.collectAsState()
    val text = when {
        model.connecting -> "Pairing…"
        model.error != null -> model.error!!
        byState != null -> byState.value.toString()
        else -> "Not connected"
    }
    val color = when {
        model.error != null -> MaterialTheme.colorScheme.error
        byState != null -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Status: ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(text.removePrefix("com.phonecontroller.app.net.ConnState\$"), color = color, fontSize = 13.sp)
    }
}
