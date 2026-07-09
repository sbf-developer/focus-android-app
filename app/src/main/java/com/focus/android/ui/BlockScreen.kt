package com.focus.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.focus.android.data.AppStatus
import com.focus.android.util.displayDomain

@Composable
fun BlockScreen(
    blocklist: List<String>,
    status: AppStatus,
    vpnError: String?,
    onToggleBlocking: (Boolean) -> Unit,
    onToggleStartup: (Boolean) -> Unit,
    onAddDomain: (String) -> Unit,
    onRemoveDomain: (String) -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Block",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "System-wide domain blocking via local DNS",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        if (!status.usageAccessGranted) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Usage access needed for app time tracking.")
                    TextButton(onClick = onRequestUsageAccess) {
                        Text("Open Usage Access settings")
                    }
                }
            }
        }

        vpnError?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleBlocking(!status.blockingEnabled) },
            colors = CardDefaults.cardColors(
                containerColor = if (status.blockingEnabled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Blocking", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = when {
                                status.vpnRunning -> "Active — ${status.blocklistCount} domains blocked"
                                status.blockingEnabled -> "Starting…"
                                else -> "Off — tap below to enable"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = status.blockingEnabled,
                        onCheckedChange = onToggleBlocking,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }

                if (status.blockingEnabled) {
                    OutlinedButton(
                        onClick = { onToggleBlocking(false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Text("Turn blocking off")
                    }
                } else {
                    Button(
                        onClick = { onToggleBlocking(true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("Turn blocking on")
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Start on boot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Restart blocking after device reboot",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = status.launchAtStartup,
                    onCheckedChange = onToggleStartup,
                )
            }
        }

        TextButton(
            onClick = onRequestBatteryExemption,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Battery optimization exemption")
        }

        Text(
            text = "Blocklist (${blocklist.size})",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("example.com") },
                singleLine = true,
            )
            TextButton(
                onClick = {
                    if (input.isNotBlank()) {
                        onAddDomain(input)
                        input = ""
                    }
                },
            ) {
                Text("Add")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            blocklist.forEach { domain ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(displayDomain(domain))
                    IconButton(onClick = { onRemoveDomain(domain) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                }
            }
        }
    }
}
