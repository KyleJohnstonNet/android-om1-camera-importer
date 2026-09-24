package dev.om1.importer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ImporterColors = lightColorScheme(
    primary = Color(0xFF006C4D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F5D1),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF456457),
    secondaryContainer = Color(0xFFC7E9D5),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFDDE7DF),
    background = Color(0xFFF8FBF7),
    error = Color(0xFFBA1A1A)
)

@Composable
fun ImporterTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = ImporterColors, content = content)
