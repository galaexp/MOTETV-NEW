package com.gala.motetv.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.core.DiagnosticItem
import com.gala.motetv.core.DiagnosticReport
import com.gala.motetv.core.DiagnosticStatus
import com.gala.motetv.core.DiagnosticsService
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.storage.TvCredentialStore
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(
    currentDevice: TvDevice?,
    connectionState: ConnectionState,
    credentialStore: TvCredentialStore,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onOpenPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnosticsService = remember { DiagnosticsService(context, credentialStore) }

    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    fun runDiagnosticsCheck() {
        isRunning = true
        scope.launch {
            report = diagnosticsService.runDiagnostics(currentDevice, connectionState)
            isRunning = false
        }
    }

    LaunchedEffect(currentDevice, connectionState) {
        runDiagnosticsCheck()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // TOP BAR
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF334155), CircleShape)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Connection Diagnostics",
                            style = Typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = currentDevice?.name ?: "No TV Selected",
                            style = Typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Refresh Button
                    IconButton(
                        onClick = { runDiagnosticsCheck() },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF334155), CircleShape)
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF38BDF8), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Rerun", tint = Color.White)
                        }
                    }

                    // Copy Report Button
                    IconButton(
                        onClick = {
                            val r = report ?: return@IconButton
                            val text = diagnosticsService.generateRedactedReport(r)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostics Report", text))
                            Toast.makeText(context, "Copied diagnostic report to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF334155), CircleShape)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Report", tint = Color(0xFF38BDF8))
                    }
                }
            }

            // SUMMARY CARD
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isHealthy = report?.isOverallHealthy == true
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (isHealthy) Color(0x2210B981) else Color(0x22EF4444))
                            .border(1.dp, if (isHealthy) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isHealthy) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (isHealthy) Color(0xFF10B981) else Color(0xFFEF4444),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isHealthy) "System Healthy" else "Connection Issues Detected",
                            style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = if (isHealthy) "Phone, Wi-Fi, and Android TV endpoint passed checks." else "Follow suggested actions below to restore remote control.",
                            style = Typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            Text(
                text = "DIAGNOSTIC CHECKS (${report?.items?.size ?: 0})",
                style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = Color(0xFF64748B)
            )

            // CHECKS LIST
            val items = report?.items ?: emptyList()
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(items, key = { it.id }) { item ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val iconColor = when (item.status) {
                                DiagnosticStatus.PASSED -> Color(0xFF10B981)
                                DiagnosticStatus.FAILED -> Color(0xFFEF4444)
                                DiagnosticStatus.NOT_AVAILABLE -> Color(0xFFF59E0B)
                            }

                            Icon(
                                imageVector = when (item.status) {
                                    DiagnosticStatus.PASSED -> Icons.Default.CheckCircle
                                    DiagnosticStatus.FAILED -> Icons.Default.Error
                                    DiagnosticStatus.NOT_AVAILABLE -> Icons.Default.Help
                                },
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = item.explanation,
                                    style = Typography.bodySmall,
                                    color = Color(0xFF94A3B8)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Action: ${item.suggestedAction}",
                                    style = Typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = iconColor
                                )
                            }
                        }
                    }
                }
            }

            // ACTION BAR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onReconnect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Retry Connect", style = Typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }

                OutlinedButton(
                    onClick = onOpenPairing,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Re-pair TV", color = Color(0xFF38BDF8), style = Typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}
