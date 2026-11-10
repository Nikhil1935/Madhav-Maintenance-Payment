package com.payvault.upitracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val AppleGroupedBg = Color(0xFFF2F2F7)
val AppleCardWhite = Color(0xFFFFFFFF)
val AppleBlue = Color(0xFF007AFF)
val AppleGreen = Color(0xFF34C759)
val AppleOrange = Color(0xFFFF9500)
val AppleRed = Color(0xFFFF3B30)
val AppleTextPrimary = Color(0xFF1C1C1E)
val AppleTextSecondary = Color(0xFF636366)
val AppleHairline = Color(0x223C3C43)
val MadhavGold = Color(0xFFD4AF37)

data class PayVaultPalette(
    val isDark: Boolean,
    val groupedBg: Color,
    val cardBg: Color,
    val subtleBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val hairline: Color
)

val LightPayVaultPalette = PayVaultPalette(
    isDark = false,
    groupedBg = Color(0xFFF2F2F7),
    cardBg = Color(0xFFFFFFFF),
    subtleBg = Color(0xFFE5E5EA),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF636366),
    hairline = Color(0x223C3C43)
)

val DarkPayVaultPalette = PayVaultPalette(
    isDark = true,
    groupedBg = Color(0xFF0F141C),
    cardBg = Color(0xFF1A2230),
    subtleBg = Color(0xFF253042),
    textPrimary = Color(0xFFF2F5F9),
    textSecondary = Color(0xFF9BA6B8),
    hairline = Color(0x26FFFFFF)
)

val LocalPayVaultPalette = staticCompositionLocalOf { LightPayVaultPalette }

private val CupertinoLightColorScheme = lightColorScheme(
    primary = AppleBlue,
    onPrimary = Color.White,
    background = LightPayVaultPalette.groupedBg,
    onBackground = LightPayVaultPalette.textPrimary,
    surface = LightPayVaultPalette.cardBg,
    onSurface = LightPayVaultPalette.textPrimary,
    secondary = AppleGreen,
    error = AppleRed
)

private val CupertinoDarkColorScheme = darkColorScheme(
    primary = AppleBlue,
    onPrimary = Color.White,
    background = DarkPayVaultPalette.groupedBg,
    onBackground = DarkPayVaultPalette.textPrimary,
    surface = DarkPayVaultPalette.cardBg,
    onSurface = DarkPayVaultPalette.textPrimary,
    secondary = AppleGreen,
    error = AppleRed
)

@Composable
fun CupertinoPayVaultTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkPayVaultPalette else LightPayVaultPalette
    val colorScheme = if (darkTheme) CupertinoDarkColorScheme else CupertinoLightColorScheme

    CompositionLocalProvider(LocalPayVaultPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
