package com.jknl.englishreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm, low-glare paper with a rust accent; the night palette keeps the same warmth.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE2A272), onPrimary = Color(0xFF2B1A0F),
    background = Color(0xFF16181B), surface = Color(0xFF1E2125),
    surfaceVariant = Color(0xFF282C31), onSurfaceVariant = Color(0xFF9D978D),
    onBackground = Color(0xFFDDD8CE), onSurface = Color(0xFFDDD8CE),
    outline = Color(0xFF3B4047), outlineVariant = Color(0xFF2E3238),
    primaryContainer = Color(0xFF45301F), onPrimaryContainer = Color(0xFFF6DCC6),
    secondary = Color(0xFFB9B2A6), secondaryContainer = Color(0xFF33363B), onSecondaryContainer = Color(0xFFE6E1D8),
    tertiaryContainer = Color(0xFF524619), onTertiaryContainer = Color(0xFFF3E6B0),
    surfaceContainerLowest = Color(0xFF131518), surfaceContainerLow = Color(0xFF1B1E21),
    surfaceContainer = Color(0xFF202327), surfaceContainerHigh = Color(0xFF262A2E), surfaceContainerHighest = Color(0xFF2D3136)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF9E4127), onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F2E8), surface = Color(0xFFFCF9F3),
    surfaceVariant = Color(0xFFEDE6D9), onSurfaceVariant = Color(0xFF6F675B),
    onBackground = Color(0xFF221F1A), onSurface = Color(0xFF221F1A),
    outline = Color(0xFFD8CEBC), outlineVariant = Color(0xFFE6DDCD),
    primaryContainer = Color(0xFFF3DCCB), onPrimaryContainer = Color(0xFF4A1C0D),
    secondary = Color(0xFF5E574C), secondaryContainer = Color(0xFFEAE2D3), onSecondaryContainer = Color(0xFF2E2A23),
    tertiaryContainer = Color(0xFFF5E7A8), onTertiaryContainer = Color(0xFF3A3205),
    surfaceContainerLowest = Color(0xFFFFFDF9), surfaceContainerLow = Color(0xFFFAF6EE),
    surfaceContainer = Color(0xFFF3EDE2), surfaceContainerHigh = Color(0xFFEEE7DA), surfaceContainerHighest = Color(0xFFE8E0D1)
)

@Composable
fun EnglishReaderTheme(
    palette: String = "paper",
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        palette == "stars" -> darkColorScheme(
            primary = Color(0xFFBDDDF2), onPrimary = Color(0xFF102738),
            background = Color(0xFF050D18), surface = Color(0xFF101F30),
            surfaceContainerLow = Color(0xFF102032), surfaceVariant = Color(0xFF213448),
            onBackground = Color(0xFFF3EEE4), onSurface = Color(0xFFF3EEE4), onSurfaceVariant = Color(0xFFABBECD),
            secondary = Color(0xFFDCC8A3), outline = Color(0xFF60798B), primaryContainer = Color(0xFF28485E))
        palette == "night" -> DarkColorScheme
        palette == "black" -> darkColorScheme(
            background = Color.Black, surface = Color(0xFF171717),
            primary = Color(0xFFD0D0D0), onPrimary = Color.Black,
            onBackground = Color(0xFFE6E6E6), onSurface = Color(0xFFE6E6E6),
            onSurfaceVariant = Color(0xFFB0B0B0), surfaceVariant = Color(0xFF292929),
            primaryContainer = Color(0xFF3B3B3B), onPrimaryContainer = Color.White,
            surfaceContainerLow = Color(0xFF171717), outline = Color(0xFF777777))
        palette == "white" -> LightColorScheme.copy(background = Color(0xFFFFFFFF), surface = Color(0xFFF7F7F5), surfaceContainerLow = Color(0xFFF7F7F5),
            primary = Color(0xFF2B5C8A), primaryContainer = Color(0xFFDCE7F3), onPrimaryContainer = Color(0xFF0E2A44),
            onBackground = Color(0xFF1C1C1C), onSurface = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFF6A6A6A), outline = Color(0xFFDDDDDD))
        palette == "sage" -> LightColorScheme.copy(background = Color(0xFFE4ECDf), surface = Color(0xFFEFF4E9), surfaceContainerLow = Color(0xFFEFF4E9))
        palette == "blue" -> LightColorScheme.copy(background = Color(0xFFE4ECF2), surface = Color(0xFFF0F5F8), surfaceContainerLow = Color(0xFFF0F5F8),
            primary = Color(0xFF3A5C72), onBackground = Color(0xFF293E4B), onSurface = Color(0xFF293E4B), primaryContainer = Color(0xFFCDDFEC))
        palette == "rose" -> LightColorScheme.copy(background = Color(0xFFF1E4E3), surface = Color(0xFFFAEFED), surfaceContainerLow = Color(0xFFFAEFED),
            primary = Color(0xFF80585F), onBackground = Color(0xFF4A363B), onSurface = Color(0xFF4A363B), primaryContainer = Color(0xFFE9CED2))
        palette == "sand" -> LightColorScheme.copy(background = Color(0xFFEBDCC3), surface = Color(0xFFF5EAD7), surfaceContainerLow = Color(0xFFF5EAD7),
            primary = Color(0xFF735A38), onBackground = Color(0xFF463A2A), onSurface = Color(0xFF463A2A), primaryContainer = Color(0xFFE4CCA4))
        palette == "ink" -> DarkColorScheme.copy(background = Color(0xFF171E2C), surface = Color(0xFF222C3D), surfaceContainerLow = Color(0xFF222C3D),
            primary = Color(0xFFB0C9E7), onBackground = Color(0xFFDFE5F1), onSurface = Color(0xFFDFE5F1), primaryContainer = Color(0xFF354B67))
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
