package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val EterDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = CharcoalVoid,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = CobaltBlue,
    onSecondary = TextPrimary,
    secondaryContainer = CobaltContainer,
    onSecondaryContainer = TextPrimary,
    tertiary = VioletAccent,
    onTertiary = TextPrimary,
    tertiaryContainer = VioletContainer,
    onTertiaryContainer = TextPrimary,
    background = CharcoalVoid,
    onBackground = TextPrimary,
    surface = CharcoalSurface,
    onSurface = TextPrimary,
    surfaceVariant = CharcoalElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = CharcoalVoid,
    surfaceContainerLow = CharcoalSurface,
    surfaceContainer = CharcoalElevated,
    surfaceContainerHigh = CharcoalHigh,
    outline = CharcoalBorder,
    outlineVariant = CharcoalBorder.copy(alpha = 0.5f),
    error = StatusError,
    onError = TextPrimary,
    errorContainer = StatusErrorContainer,
    onErrorContainer = StatusError
)

val EterShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun EterScreenTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = EterDarkColorScheme,
        typography = EterTypography,
        shapes = EterShapes,
        content = content
    )
}
