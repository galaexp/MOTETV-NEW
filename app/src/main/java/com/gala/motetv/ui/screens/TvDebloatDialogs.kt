package com.gala.motetv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gala.motetv.debloat.DebloatOperationEntry
import com.gala.motetv.debloat.ScannedPackage
import com.gala.motetv.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PackageInspectDialog(
    pkg: ScannedPackage,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(text = pkg.appName, color = TextPrimary, style = Typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Package: ${pkg.packageName}", style = Typography.bodySmall, color = ElectricBlueLight)
                Text("Category: ${pkg.category.title}", style = Typography.bodySmall, color = TextSecondary)
                Text(
                    "State: ${if (pkg.isEnabled) "Enabled" else "Disabled"}${if (pkg.modifiedByMoteTv) " (Modified by MoteTV)" else ""}",
                    style = Typography.bodySmall,
                    color = if (pkg.isEnabled) StatusSuccessGreen else StatusWarningAmber
                )
                Text("Recommendation: ${pkg.recommendation.label}", style = Typography.bodySmall, color = TextPrimary)
                Text("Risk Level: ${pkg.riskLevel.label}", style = Typography.bodySmall, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(pkg.reason, style = Typography.bodyMedium, color = TextSecondary)
                if (pkg.isBlockedFromDisable && pkg.blockReason != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StatusErrorRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = pkg.blockReason,
                            style = Typography.bodySmall,
                            color = StatusErrorRed,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary)
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
fun ConfirmDebloatDialog(
    selectedPackages: List<ScannedPackage>,
    isRestoreMode: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val optionalWarnings = selectedPackages.filter { !isRestoreMode && it.requiresOptionalPrompt }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isRestoreMode) StatusSuccessGreen else StatusWarningAmber
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRestoreMode) {
                        "Restore ${selectedPackages.size} Package(s)?"
                    } else {
                        "Confirm Disable (${selectedPackages.size} Package(s))"
                    },
                    color = TextPrimary,
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isRestoreMode) {
                        "Runs 'pm enable' for each selected package and verifies state:"
                    } else {
                        "A full TV package & HOME backup will be saved first. Each package below will be reversibly disabled with 'pm disable-user --user 0' in small verified batches:"
                    },
                    style = Typography.bodySmall,
                    color = TextSecondary
                )

                if (optionalWarnings.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StatusWarningAmber.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StatusWarningAmber.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "Optional TV Features Included (${optionalWarnings.joinToString { it.appName }}): Please confirm you intentionally want to disable these optional services.",
                            style = Typography.bodySmall,
                            color = StatusWarningAmber,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(selectedPackages, key = { it.packageName }) { pkg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BackgroundDark,
                            border = BorderStroke(1.dp, GlassBorder)
                        ) {
                            Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                                Text(pkg.appName, style = Typography.labelLarge, color = TextPrimary)
                                Text("${pkg.packageName} • ${pkg.riskLevel.label}", style = Typography.labelSmall, color = TextMuted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRestoreMode) StatusSuccessGreen else StatusErrorRed
                ),
                modifier = Modifier.testTag("debloat_confirm_execute_button")
            ) {
                Text(if (isRestoreMode) "Restore Now" else "Backup & Disable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
fun DebloatHistoryDialog(
    history: List<DebloatOperationEntry>,
    onDismiss: () -> Unit
) {
    val formatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text("Operation History (${history.size})", color = TextPrimary, style = Typography.titleLarge)
        },
        text = {
            if (history.isEmpty()) {
                Text("No debloat or backup operations recorded yet.", style = Typography.bodySmall, color = TextSecondary)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history, key = { it.id }) { entry ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = BackgroundDark,
                            border = BorderStroke(
                                1.dp,
                                if (entry.succeeded) GlassBorder else StatusErrorRed.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp).fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${entry.action.name} (${entry.verifiedState})",
                                        style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (entry.succeeded) StatusSuccessGreen else StatusErrorRed
                                    )
                                    Text(
                                        text = formatter.format(Date(entry.timestampMs)),
                                        style = Typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                                if (!entry.packageName.isNullOrBlank()) {
                                    Text(entry.packageName, style = Typography.bodySmall, color = ElectricBlueLight)
                                }
                                Text(entry.detail, style = Typography.bodySmall, color = TextSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary)
            ) {
                Text("Close")
            }
        }
    )
}
