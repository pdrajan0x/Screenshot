package com.pdrajan.dot.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalTextApi::class)
private fun doto(weight: Int) = Font(
    resId = R.font.doto,
    weight = FontWeight(weight),
    // ROND 100 = round dots, like Nothing's dot-matrix type.
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("ROND", 100f)),
)

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: Int) = Font(
    resId = R.font.space_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Dot-matrix display face (Doto, OFL) — a free stand-in for Nothing's NDot. */
val DotMatrix = FontFamily(doto(500), doto(700), doto(900))

/** Body face (Space Grotesk, OFL). */
val Grotesk = FontFamily(grotesk(300), grotesk(400), grotesk(500), grotesk(700))

/** Small technical labels (Space Mono, OFL). */
val Mono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

internal val DotTypography = Typography(
    displayLarge = TextStyle(fontFamily = DotMatrix, fontWeight = FontWeight(900), fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = 0.02.em),
    displayMedium = TextStyle(fontFamily = DotMatrix, fontWeight = FontWeight(900), fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = 0.02.em),
    displaySmall = TextStyle(fontFamily = DotMatrix, fontWeight = FontWeight(900), fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = 0.02.em),
    headlineLarge = TextStyle(fontFamily = DotMatrix, fontWeight = FontWeight(900), fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = 0.02.em),
    headlineMedium = TextStyle(fontFamily = DotMatrix, fontWeight = FontWeight(700), fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = 0.02.em),
    headlineSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.08.em),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.1.em),
)
