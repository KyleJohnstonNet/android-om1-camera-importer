package dev.om1.camerahelper

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CameraLinkColors = lightColorScheme(
    primary = Color(0xFF275DA7), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF535F72), secondaryContainer = Color(0xFFD7E3F7),
    background = Color(0xFFF9F9FF), surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFDFE2EB)
)

@Composable fun CameraLinkTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme=CameraLinkColors, content=content)
