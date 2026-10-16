package com.picoxr.stereoviewer.content

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

private val LoadingGreen = Color(0xFF30A472)

@Composable
internal fun HighResolutionLoadingIndicator(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "high-resolution-loading")
    val rotation =
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "high-resolution-loading-rotation",
        )

    Box(
        modifier = modifier.size(32.dp).graphicsLayer { rotationZ = rotation.value },
    ) {
        Box(
            modifier =
                Modifier.align(Alignment.CenterStart)
                    .size(8.dp)
                    .background(LoadingGreen, CircleShape),
        )
        Box(
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .size(8.dp)
                    .background(LoadingGreen.copy(alpha = 0.55f), CircleShape),
        )
    }
}
