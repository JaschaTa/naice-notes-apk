package com.jt.naicenotes.ui.util

import androidx.compose.ui.graphics.Color

val SectionColorPalette: List<Color> = listOf(
    Color(0xFF4CAF50), // green
    Color(0xFF2196F3), // blue
    Color(0xFFFF9800), // orange
    Color(0xFFE91E63), // pink
    Color(0xFF9C27B0), // purple
    Color(0xFFF44336), // red
    Color(0xFF009688), // teal
    Color(0xFF795548), // brown
    Color(0xFF607D8B), // blue-grey
)

fun randomSectionColor(): Int = SectionColorPalette.random().toArgb()

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
