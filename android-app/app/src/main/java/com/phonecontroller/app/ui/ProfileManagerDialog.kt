package com.phonecontroller.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.phonecontroller.app.profile.GameProfile
import com.phonecontroller.app.profile.ProfileStore

/**
 * Profile manager dialog (spec §7 & §11 Milestone 4).
 * Allows users to switch, create, duplicate, import, export, and delete game profiles.
 */
@Composable
fun ProfileManagerDialog(
    profileStore: ProfileStore,
    currentProfileId: String,
    onSelectProfile: (GameProfile) -> Unit,
    onEditProfile: (GameProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(profileStore.loadProfiles()) }
    var activeId by remember { mutableStateOf(currentProfileId) }
    var showNewDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Game Profiles",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Switch layouts or import/export configuration",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onDismiss) {
                        Text("Done")
                    }
                }

                // Profile List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(profiles) { profile ->
                        val isActive = profile.id == activeId
                        ProfileListItem(
                            profile = profile,
                            isActive = isActive,
                            canDelete = profiles.size > 1,
                            onSelect = {
                                activeId = profile.id
                                profileStore.setActiveProfileId(profile.id)
                                onSelectProfile(profile)
                            },
                            onEdit = {
                                onEditProfile(profile)
                                onDismiss()
                            },
                            onDuplicate = {
                                val dup = profileStore.duplicateProfile(profile.id)
                                profiles = profileStore.loadProfiles()
                                Toast.makeText(context, "Duplicated ${profile.name}", Toast.LENGTH_SHORT).show()
                            },
                            onExport = {
                                val json = profileStore.exportProfile(profile.id)
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("PhoneController Profile", json))
                                Toast.makeText(context, "Copied profile JSON to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onDelete = {
                                if (profileStore.deleteProfile(profile.id)) {
                                    profiles = profileStore.loadProfiles()
                                    if (activeId == profile.id) {
                                        activeId = profileStore.getActiveProfileId()
                                        onSelectProfile(profileStore.getActiveProfile())
                                    }
                                }
                            },
                        )
                    }
                }

                // Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { showNewDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("New")
                    }
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Import")
                    }
                    OutlinedButton(
                        onClick = {
                            profiles = profileStore.resetToDefaults()
                            activeId = profileStore.getActiveProfileId()
                            onSelectProfile(profileStore.getActiveProfile())
                            Toast.makeText(context, "Defaults restored", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Defaults")
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewProfileDialog(
            onCreate = { name ->
                val created = profileStore.createProfile(name)
                profiles = profileStore.loadProfiles()
                activeId = created.id
                onSelectProfile(created)
                showNewDialog = false
            },
            onDismiss = { showNewDialog = false },
        )
    }

    if (showImportDialog) {
        ImportProfileDialog(
            onImport = { json ->
                try {
                    val imported = profileStore.importProfile(json)
                    profiles = profileStore.loadProfiles()
                    activeId = imported.id
                    onSelectProfile(imported)
                    showImportDialog = false
                    Toast.makeText(context, "Imported ${imported.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { showImportDialog = false },
        )
    }
}

@Composable
private fun ProfileListItem(
    profile: GameProfile,
    isActive: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val border = if (isActive) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isActive) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "ACTIVE",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Text(
                "${profile.rows}×${profile.cols} grid · ${profile.cells.size} cells",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = onEdit,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text("Edit", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onDuplicate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text("Copy", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onExport,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text("Export", fontSize = 11.sp)
            }
            if (canDelete) {
                OutlinedButton(
                    onClick = onDelete,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("Del", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun NewProfileDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("New Profile", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (name.isNotBlank()) onCreate(name) }) { Text("Create") }
                }
            }
        }
    }
}

@Composable
private fun ImportProfileDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var jsonText by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Import Profile JSON", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    label = { Text("Paste JSON here") },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (jsonText.isNotBlank()) onImport(jsonText) }) { Text("Import") }
                }
            }
        }
    }
}
