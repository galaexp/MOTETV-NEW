package com.gala.motetv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.StatusErrorRed
import com.gala.motetv.ui.theme.StatusSuccessGreen
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@Composable
fun ConnectionStatusBadge(
    connectionState: ConnectionState,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (color, text, isPulsing) = when (connectionState) {
        is ConnectionState.Ready -> Triple(StatusSuccessGreen, "Connected (TCP 6466)", false)
        is ConnectionState.Connecting -> Triple(ElectricBluePrimary, "Connecting...", true)
        is ConnectionState.Authenticating -> Triple(ElectricBluePrimary, "Authenticating TLS...", true)
        is ConnectionState.Pairing -> Triple(ElectricBluePrimary, "Pairing (${connectionState.stage})", true)
        is ConnectionState.Discovering -> Triple(ElectricBluePrimary, "Discovering...", true)
        is ConnectionState.Reconnecting -> Triple(ElectricBluePrimary, "Reconnecting...", true)
        is ConnectionState.Failed -> Triple(StatusErrorRed, "Connection Failed (Tap to retry)", false)
        is ConnectionState.Disconnected -> Triple(TextMuted, "Disconnected", false)
    }

    val clickModifier = if (connectionState is ConnectionState.Failed) {
        Modifier.clickable(onClick = onRetry)
    } else Modifier

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .then(clickModifier)
            .padding(vertical = 2.dp)
            .testTag("connection_status_badge"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
                .alpha(if (isPulsing) pulseAlpha else 1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = Typography.labelSmall,
            color = if (connectionState is ConnectionState.Failed) StatusErrorRed else TextSecondary
        )
    }
}
