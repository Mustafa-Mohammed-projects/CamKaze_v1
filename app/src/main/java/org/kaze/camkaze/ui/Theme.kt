package org.kaze.camkaze.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF121212)
val NormalAccent = Color(0xFF26A69A)
val SecureAccent = Color(0xFFFF6E40)

private val NormalScheme = darkColorScheme(
    primary = NormalAccent, onPrimary = Color.Black,
    background = Bg, onBackground = Color(0xFFE6E6E6),
    surface = Bg, onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1E1E1E), onSurfaceVariant = Color(0xFFB0B0B0),
    secondaryContainer = Color(0xFF1F3B38), onSecondaryContainer = Color(0xFFD7F5F1),
    surfaceContainer = Color(0xFF181818), surfaceContainerLow = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF1E1E1E),
)

private val SecureScheme = darkColorScheme(
    primary = SecureAccent, onPrimary = Color.Black,
    background = Bg, onBackground = Color(0xFFEDE3E0),
    surface = Color(0xFF1A1210), onSurface = Color(0xFFEDE3E0),
    surfaceVariant = Color(0xFF2A1512), onSurfaceVariant = Color(0xFFC9B3AD),
    secondaryContainer = Color(0xFF4A2418), onSecondaryContainer = Color(0xFFFFDBD0),
    surfaceContainer = Color(0xFF211412), surfaceContainerLow = Color(0xFF1A1210),
    surfaceContainerHigh = Color(0xFF2A1512),
)

@Composable
fun KazeTheme(secure: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (secure) SecureScheme else NormalScheme, content = content)
}
