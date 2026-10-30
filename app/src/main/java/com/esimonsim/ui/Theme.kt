package com.esimonsim.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.LayoutDirection

// Graphite: a Material 3 tonal scheme with zero chroma, so no surface picks up a blue, green or
// pink cast. Greys are CIELAB tones (T98 = L* 98). Every role is set, including the surface
// containers (dialogs, sheets, cards): left unset they fall back to the baseline purple.
// Red is the only hue, and only for errors and delete. Fixed rather than wallpaper-based dynamic
// colour, so the app keeps one recognisable look.
private val Light = lightColorScheme(
    primary = Color(0xFF1F1F1F), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E2E2), onPrimaryContainer = Color(0xFF1B1B1B),
    inversePrimary = Color(0xFFC6C6C6),
    secondary = Color(0xFF5E5E5E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8), onSecondaryContainer = Color(0xFF1B1B1B),
    tertiary = Color(0xFF5E5E5E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2E2E2), onTertiaryContainer = Color(0xFF1B1B1B),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F9F9), onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFF9F9F9), onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE2E2E2), onSurfaceVariant = Color(0xFF474747),
    surfaceTint = Color(0xFF1F1F1F),
    inverseSurface = Color(0xFF303030), inverseOnSurface = Color(0xFFF1F1F1),
    outline = Color(0xFF777777), outlineVariant = Color(0xFFC6C6C6),
    scrim = Color.Black,
    surfaceDim = Color(0xFFDADADA), surfaceBright = Color(0xFFF9F9F9),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F3F3),
    surfaceContainer = Color(0xFFEEEEEE), surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE2E2E2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE2E2E2), onPrimary = Color(0xFF1B1B1B),
    primaryContainer = Color(0xFF474747), onPrimaryContainer = Color(0xFFE2E2E2),
    inversePrimary = Color(0xFF474747),
    secondary = Color(0xFFC6C6C6), onSecondary = Color(0xFF303030),
    secondaryContainer = Color(0xFF3B3B3B), onSecondaryContainer = Color(0xFFE2E2E2),
    tertiary = Color(0xFFC6C6C6), onTertiary = Color(0xFF303030),
    tertiaryContainer = Color(0xFF474747), onTertiaryContainer = Color(0xFFE2E2E2),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131313), onBackground = Color(0xFFE2E2E2),
    surface = Color(0xFF131313), onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF474747), onSurfaceVariant = Color(0xFFC6C6C6),
    surfaceTint = Color(0xFFE2E2E2),
    inverseSurface = Color(0xFFE2E2E2), inverseOnSurface = Color(0xFF303030),
    outline = Color(0xFF919191), outlineVariant = Color(0xFF474747),
    scrim = Color.Black,
    surfaceDim = Color(0xFF131313), surfaceBright = Color(0xFF393939),
    surfaceContainerLowest = Color(0xFF0E0E0E), surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1F1F1F), surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF353535),
)

// Balanced line breaks everywhere: the lines of a paragraph come out about equally long, so none ends on
// one stray word ("…turn it on / once."). The app's texts are short, which is where this works best.
private val Balanced = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

private val Type = Typography().run {
    fun TextStyle.b() = copy(lineBreak = Balanced)
    copy(displayLarge = displayLarge.b(), displayMedium = displayMedium.b(), displaySmall = displaySmall.b(),
        headlineLarge = headlineLarge.b(), headlineMedium = headlineMedium.b(), headlineSmall = headlineSmall.b(),
        titleLarge = titleLarge.b(), titleMedium = titleMedium.b(), titleSmall = titleSmall.b(),
        bodyLarge = bodyLarge.b(), bodyMedium = bodyMedium.b(), bodySmall = bodySmall.b(),
        labelLarge = labelLarge.b(), labelMedium = labelMedium.b(), labelSmall = labelSmall.b())
}

/** For text fields that wrap: balancing re-flows the lines above the cursor as people type. */
val FieldText: TextStyle @Composable get() = LocalTextStyle.current.copy(lineBreak = LineBreak.Simple)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // The app is English only, so left to right on any phone: on a right-to-left phone the EID's and ICCID's groups of
    // digits would show in reverse order, and people type those into shops. Translating it would mean undoing this.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
    }
}
