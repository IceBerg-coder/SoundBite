package com.soundbite.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SoundBiteDarkPrimary,
    onPrimary = SoundBiteDarkOnPrimary,
    primaryContainer = SoundBiteDarkPrimaryContainer,
    onPrimaryContainer = SoundBiteDarkOnPrimaryContainer,
    secondary = SoundBiteDarkSecondary,
    onSecondary = SoundBiteDarkOnSecondary,
    secondaryContainer = SoundBiteDarkSecondaryContainer,
    onSecondaryContainer = SoundBiteDarkOnSecondaryContainer,
    background = SoundBiteDarkBackground,
    onBackground = SoundBiteDarkOnBackground,
    surface = SoundBiteDarkSurface,
    onSurface = SoundBiteDarkOnSurface,
    surfaceVariant = SoundBiteDarkSurfaceVariant,
    onSurfaceVariant = SoundBiteDarkOnSurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    primary = SoundBitePrimary,
    onPrimary = SoundBiteOnPrimary,
    primaryContainer = SoundBitePrimaryContainer,
    onPrimaryContainer = SoundBiteOnPrimaryContainer,
    secondary = SoundBiteSecondary,
    onSecondary = SoundBiteOnSecondary,
    secondaryContainer = SoundBiteSecondaryContainer,
    onSecondaryContainer = SoundBiteOnSecondaryContainer,
    background = SoundBiteBackground,
    onBackground = SoundBiteOnBackground,
    surface = SoundBiteSurface,
    onSurface = SoundBiteOnSurface,
    surfaceVariant = SoundBiteSurfaceVariant,
    onSurfaceVariant = SoundBiteOnSurfaceVariant
)

@Composable
fun SoundBiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

