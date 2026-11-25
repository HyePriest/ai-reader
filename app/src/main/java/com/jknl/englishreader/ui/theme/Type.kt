package com.jknl.englishreader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.jknl.englishreader.R

/** Literata: a text face designed for long-form reading on screens. Chinese falls back to the system font. */
val ReadingSerif = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_medium, FontWeight.Medium),
    Font(R.font.literata_semibold, FontWeight.SemiBold),
    Font(R.font.literata_semibold, FontWeight.Bold),
)

/** Inter for interface text and the sans reading option. */
val InterfaceSans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold),
)

private fun TextStyle.ui() = copy(fontFamily = InterfaceSans)

val Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.ui(), displayMedium = displayMedium.ui(), displaySmall = displaySmall.ui(),
        headlineLarge = headlineLarge.ui(), headlineMedium = headlineMedium.ui(), headlineSmall = headlineSmall.ui(),
        titleLarge = titleLarge.ui(), titleMedium = titleMedium.ui(), titleSmall = titleSmall.ui(),
        bodyLarge = bodyLarge.ui(), bodyMedium = bodyMedium.ui(), bodySmall = bodySmall.ui(),
        labelLarge = labelLarge.ui(), labelMedium = labelMedium.ui(), labelSmall = labelSmall.ui(),
    )
}
