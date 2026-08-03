package com.jt.naicenotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Always dynamic (Material You). There is no fixed-palette fallback: minSdk is 34, so
 * dynamic colour is guaranteed available, and every accent in this app comes from
 * [com.jt.naicenotes.data.entity.Section.color] rather than the scheme.
 */
@Composable
fun NaiceNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (darkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        },
        typography = Typography,
        content = content,
    )
}
