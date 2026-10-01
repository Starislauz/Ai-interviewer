package com.aiinterviewer.coach.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CoachColors = lightColorScheme(
    primary = Color(0xFF10233F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF2F6F6B),
    onSecondary = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
)

@Composable
fun CoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CoachColors, content = content)
}
