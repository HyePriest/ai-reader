package com.jknl.englishreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB7CDAF), onPrimary = Color(0xFF183226),
    background = Color(0xFF18221E), surface = Color(0xFF202C25),
    surfaceVariant = Color(0xFF2C3930), onSurfaceVariant = Color(0xFFB4C1B6),
    onBackground = Color(0xFFE3E8DD), onSurface = Color(0xFFE3E8DD),
    outline = Color(0xFF728075), primaryContainer = Color(0xFF344D3A), onPrimaryContainer = Color(0xFFDFECD9),
    secondary = Color(0xFFB7CDAF), secondaryContainer = Color(0xFF344D3A), onSecondaryContainer = Color(0xFFDFECD9),
    surfaceContainerLowest = Color(0xFF16201B), surfaceContainerLow = Color(0xFF202C25),
    surfaceContainer = Color(0xFF253129), surfaceContainerHigh = Color(0xFF2B372E), surfaceContainerHighest = Color(0xFF334036)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF365B46), onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F5EF), surface = Color(0xFFFEFCF7),
    surfaceVariant = Color(0xFFEAECE2), onSurfaceVariant = Color(0xFF697369),
    onBackground = Color(0xFF24372B), onSurface = Color(0xFF24372B),
    outline = Color(0xFFADB6A7), primaryContainer = Color(0xFFE1E9D8), onPrimaryContainer = Color(0xFF284731),
    secondary = Color(0xFF52664B), secondaryContainer = Color(0xFFE1E9D8), onSecondaryContainer = Color(0xFF284731),
    surfaceContainerLowest = Color(0xFFFEFCF7), surfaceContainerLow = Color(0xFFF8F6F0),
    surfaceContainer = Color(0xFFF2F1E9), surfaceContainerHigh = Color(0xFFEEEFE5), surfaceContainerHighest = Color(0xFFE7E9DE)
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
        palette == "white" -> LightColorScheme.copy(background = Color(0xFFFAFAFA), surface = Color.White)
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
