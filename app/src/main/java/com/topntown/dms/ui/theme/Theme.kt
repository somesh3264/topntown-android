package com.topntown.dms.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * TNT light-only color scheme — field users operate outdoors in daylight,
 * so dark mode is intentionally omitted for v1.
 */
private val TntLightColorScheme = lightColorScheme(
    primary = TntPrimary,
    onPrimary = TntOnPrimary,
    primaryContainer = TntPrimaryContainer,
    onPrimaryContainer = TntOnPrimaryContainer,

    secondary = TntSecondary,
    onSecondary = TntOnSecondary,
    secondaryContainer = TntSecondaryContainer,
    onSecondaryContainer = TntOnSecondaryContainer,

    tertiary = TntTertiary,
    onTertiary = TntOnTertiary,
    tertiaryContainer = TntTertiaryContainer,
    onTertiaryContainer = TntOnTertiaryContainer,

    background = TntBackground,
    onBackground = TntOnBackground,
    surface = TntSurface,
    onSurface = TntOnSurface,
    surfaceVariant = TntSurfaceVariant,
    onSurfaceVariant = TntOnSurfaceVariant,

    error = TntError,
    onError = TntOnError,
    errorContainer = TntErrorContainer,
    onErrorContainer = TntOnErrorContainer,

    outline = TntOutline,
    outlineVariant = TntOutlineVariant
)

@Composable
fun TopNTownTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = TntLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TntTypography,
        content = content
    )
}
