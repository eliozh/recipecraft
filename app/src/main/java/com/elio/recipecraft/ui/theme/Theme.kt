package com.elio.recipecraft.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Terracotta40,
    secondary = Olive40,
    tertiary = Saffron40,
)

private val DarkColors = darkColorScheme(
    primary = Terracotta80,
    secondary = Olive80,
    tertiary = Saffron80,
)

/**
 * App theme. Uses the wallpaper-based dynamic color scheme when [dynamicColor] is true
 * (always available at minSdk 34) and falls back to the static brand palette otherwise.
 */
@Composable
fun RecipeCraftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
