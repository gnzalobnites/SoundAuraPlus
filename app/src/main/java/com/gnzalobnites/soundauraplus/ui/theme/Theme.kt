/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.gnzalobnites.soundauraplus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Paletas de colores estáticas (fallback para versiones pre-Android 12)
// Basadas en los colores originales de la app
private val LightColorPalette = lightColors(
    primary = LightThemePrimary,
    primaryVariant = LightThemePrimaryVariant,
    secondary = LightThemeSecondary,
    secondaryVariant = LightThemeSecondaryVariant,
    background = LightBackground,
    surface = LightSurface,
    error = LightError,
    onBackground = LightOnSurface,
    onSurface = LightOnSurface,
    onPrimary = LightOnPrimary
)

private val DarkColorPalette = darkColors(
    primary = DarkThemePrimary,
    primaryVariant = DarkThemePrimaryVariant,
    secondary = DarkThemeSecondary,
    secondaryVariant = DarkThemeSecondaryVariant,
    background = DarkBackground,
    surface = DarkSurface,
    error = DarkError,
    onBackground = DarkOnSurface,
    onSurface = DarkOnSurface,
    onPrimary = DarkOnPrimary
)

// Colores dinámicos de Material You (Android 12+) adaptados al esquema de
// colores de Material 2, que es el que usan todos los composables de la
// app. IMPORTANTE: primaryVariant/secondaryVariant usan los mismos tonos
// que primary/secondary (m3.primary / m3.secondary), y NO m3.primaryContainer
// / m3.secondaryContainer. Los "container" de Material 3 estan disenados
// para contrastar con onPrimaryContainer/onSecondaryContainer (que Material 2
// no tiene); usarlos junto con onPrimary/onSecondary rompe el contraste
// (texto claro sobre fondo claro en modo claro, y viceversa en oscuro).
// También se usa darkColors()/lightColors() según corresponda (y no siempre
// lightColors()) para que Material 2 fije bien `isLight`, del cual dependen
// las superposiciones de elevación en modo oscuro.
@Composable
private fun dynamicMaterial2Colors(darkTheme: Boolean): Colors {
    val context = LocalContext.current
    return if (darkTheme) {
        val m3 = androidx.compose.material3.dynamicDarkColorScheme(context)
        darkColors(
            primary = m3.primary,
            primaryVariant = m3.primary,
            secondary = m3.secondary,
            secondaryVariant = m3.secondary,
            background = DarkBackground,
            surface = DarkSurface,
            error = m3.error,
            onPrimary = m3.onPrimary,
            onSecondary = m3.onSecondary,
            onBackground = DarkOnSurface,
            onSurface = DarkOnSurface,
            onError = m3.onError
        )
    } else {
        val m3 = androidx.compose.material3.dynamicLightColorScheme(context)
        lightColors(
            primary = m3.primary,
            primaryVariant = m3.primary,
            secondary = m3.secondary,
            secondaryVariant = m3.secondary,
            background = LightBackground,
            surface = LightSurface,
            error = m3.error,
            onPrimary = m3.onPrimary,
            onSecondary = m3.onSecondary,
            onBackground = LightOnSurface,
            onSurface = LightOnSurface,
            onError = m3.onError
        )
    }
}

@Composable
fun SoundAuraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Permite desactivar los colores dinámicos si el usuario lo desea
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // Determina si el sistema soporta colores dinámicos (Android 12+)
    val hasDynamicColorSupport = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useDynamicColor = dynamicColor && hasDynamicColorSupport

    val colors =
        if (useDynamicColor) dynamicMaterial2Colors(darkTheme)
        else if (darkTheme) DarkColorPalette
        else LightColorPalette

    MaterialTheme(
        colors = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
