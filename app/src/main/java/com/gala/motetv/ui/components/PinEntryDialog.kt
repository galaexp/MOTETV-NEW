package com.gala.motetv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.ui.theme.ElectricBlueLight
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.StatusErrorRed
import com.gala.motetv.ui.theme.SurfaceCardLight
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@Composable
fun PinEntryDialog(
    tvName: String,
    prompt: String,
    errorMessage: String? = null,
    isSubmitting: Boolean = false,
    onPinSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pinText by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // When a new error message is supplied (e.g. STATUS_BAD_SECRET), re-enable input and clear previous PIN
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            submitted = false
            pinText = ""
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val trySubmit: (String) -> Unit = { rawPin ->
        if (!isSubmitting && !submitted && rawPin.length == 6) {
            submitted = true
            TvLogger.i(TvLogger.TAG_PAIR, "PIN UI: final six-character value captured, submissionStarted = true")
            onPinSubmit(rawPin)
        }
    }

    AlertDialog(
        onDismissRequest = {
            // Never dismiss automatically on focus loss or click outside
        },
        properties = DialogProperties(
            dismissOnBackPress = !isSubmitting,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = true
        ),
        containerColor = SurfaceCardLight,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ElectricBluePrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = ElectricBluePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "Pair with $tvName",
                    style = Typography.titleLarge,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = prompt,
                    style = Typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Hidden field controlling focus & input
                BasicTextField(
                    value = pinText,
                    onValueChange = { newValue ->
                        if (!isSubmitting && !submitted) {
                            val sanitized = newValue.filter { it.isLetterOrDigit() }.take(6).uppercase()
                            pinText = sanitized
                            TvLogger.d(TvLogger.TAG_PAIR, "PIN UI: changed length = ${sanitized.length}")

                            if (sanitized.length == 6) {
                                trySubmit(sanitized)
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            trySubmit(pinText)
                        }
                    ),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .testTag("pin_hidden_input"),
                    decorationBox = {
                        // 6 Pin character boxes
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 0 until 6) {
                                val char = pinText.getOrNull(i)?.toString() ?: ""
                                val isFocused = pinText.length == i && !isSubmitting

                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(GlassSurfaceElevated)
                                        .border(
                                            width = if (isFocused) 2.dp else 1.dp,
                                            color = when {
                                                errorMessage != null -> StatusErrorRed
                                                isFocused -> ElectricBluePrimary
                                                char.isNotEmpty() -> ElectricBlueLight
                                                else -> GlassBorder
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = char,
                                        style = Typography.titleMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp
                                        ),
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                )

                if (isSubmitting) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = ElectricBluePrimary,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Verifying pairing code...",
                            style = Typography.bodySmall,
                            color = ElectricBlueLight
                        )
                    }
                } else if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = errorMessage,
                        style = Typography.bodyMedium,
                        color = StatusErrorRed,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            GlassButton(
                text = if (isSubmitting) "Verifying..." else "Pair TV",
                isPrimary = true,
                enabled = pinText.length == 6 && !isSubmitting && !submitted,
                onClick = {
                    trySubmit(pinText)
                },
                testTag = "btn_submit_pin"
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                modifier = Modifier.testTag("btn_cancel_pin")
            ) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}
