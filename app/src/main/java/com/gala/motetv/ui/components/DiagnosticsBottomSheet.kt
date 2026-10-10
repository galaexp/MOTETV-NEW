package com.gala.motetv.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.BackgroundLight
import com.gala.motetv.ui.theme.SurfaceCardLight
import com.gala.motetv.ui.theme.StatusErrorRed
import com.gala.motetv.ui.theme.StatusSuccessGreen
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val logEntries by TvLogger.logsFlow.collectAsStateWithLifecycle()
    var selectedTagFilter by remember { mutableStateOf<String?>(null) }

    val filteredLogs = remember(logEntries, selectedTagFilter) {
        if (selectedTagFilter == null) {
            logEntries
        } else {
            logEntries.filter { it.tag == selectedTagFilter }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCardLight,
        modifier = modifier.testTag("diagnostics_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElectricBluePrimary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = ElectricBluePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Diagnostics & Protocol Logs",
                        style = Typography.titleMedium,
                        color = TextPrimary
                    )
                }

                Row {
                    IconButton(
                        onClick = {
                            val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val formattedLogs = logEntries.joinToString("\n") { it.format() }
                            clipMgr.setPrimaryClip(ClipData.newPlainText("MoteTV Logs", formattedLogs))
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("btn_copy_logs")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Logs",
                            tint = TextPrimary
                        )
                    }
                    IconButton(
                        onClick = { TvLogger.clearLogs() },
                        modifier = Modifier.testTag("btn_clear_logs")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Logs",
                            tint = TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tag filter chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    null to "All",
                    TvLogger.TAG_DISCOVERY to "Discovery",
                    TvLogger.TAG_TLS to "TLS",
                    TvLogger.TAG_PAIR to "Pairing",
                    TvLogger.TAG_REMOTE to "Remote"
                ).forEach { (tag, label) ->
                    val selected = selectedTagFilter == tag
                    FilterChip(
                        selected = selected,
                        onClick = { selectedTagFilter = tag },
                        label = { Text(label, style = Typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricBluePrimary.copy(alpha = 0.25f),
                            selectedLabelColor = ElectricBluePrimary,
                            containerColor = GlassSurfaceElevated,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = GlassBorder,
                            selectedBorderColor = ElectricBluePrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Logs stream
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BackgroundLight)
                    .padding(12.dp)
            ) {
                if (filteredLogs.isEmpty()) {
                    Text(
                        text = "No log records in this category.",
                        style = Typography.bodyMedium,
                        color = TextMuted
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        reverseLayout = true
                    ) {
                        items(filteredLogs.reversed(), key = { "${it.timestamp}_${it.message.hashCode()}" }) { entry ->
                            val color = when (entry.level) {
                                "ERROR" -> StatusErrorRed
                                "WARN" -> Color(0xFFF59E0B)
                                "INFO" -> TextPrimary
                                else -> TextMuted
                            }
                            Text(
                                text = entry.format(),
                                style = Typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                ),
                                color = color
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
