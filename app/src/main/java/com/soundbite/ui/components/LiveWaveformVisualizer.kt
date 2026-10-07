package com.soundbite.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Real-time dynamic microphone waveform visualizer.
 *
 * Renders smooth animated vertical amplitude bars responding to incoming RMS audio levels.
 */
@Composable
fun LiveWaveformVisualizer(
    isRecording: Boolean,
    isPaused: Boolean,
    currentAmplitude: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 48
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    // Rolling window of recent amplitudes
    val amplitudes = remember {
        mutableStateListOf<Float>().apply {
            repeat(barCount) { add(0.05f) }
        }
    }

    LaunchedEffect(currentAmplitude, isRecording, isPaused) {
        if (isRecording && !isPaused) {
            if (amplitudes.size >= barCount) {
                amplitudes.removeAt(0)
            }
            amplitudes.add(currentAmplitude.coerceIn(0.05f, 1.0f))
        } else if (!isRecording) {
            // Decay
            for (i in amplitudes.indices) {
                amplitudes[i] = (amplitudes[i] * 0.90f).coerceAtLeast(0.04f)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val barSpacing = width / barCount.toFloat()
        val barWidth = (barSpacing * 0.60f).coerceAtLeast(2.dp.toPx())

        val brush = Brush.verticalGradient(
            colors = listOf(primaryColor, secondaryColor),
            startY = 0f,
            endY = height
        )

        for (i in 0 until barCount) {
            val amp = if (isRecording && !isPaused) {
                amplitudes.getOrElse(i) { 0.05f }
            } else {
                // Subtle idle breathing sine wave
                0.06f + 0.04f * kotlin.math.sin(idlePhase + (i * 0.35f)).toFloat()
            }

            val barHeight = (amp * (height * 0.85f)).coerceAtLeast(4.dp.toPx())
            val x = i * barSpacing + (barSpacing - barWidth) / 2f
            val top = centerY - barHeight / 2f

            drawRoundRect(
                brush = if (isRecording && !isPaused) brush else Brush.linearGradient(listOf(surfaceVariant, surfaceVariant)),
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

