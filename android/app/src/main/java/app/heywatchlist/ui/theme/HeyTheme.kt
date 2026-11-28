package app.heywatchlist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Jetons de couleur d'HeyWatchlist. Le thème sombre est la référence ; le
 * thème clair en est la déclinaison, avec les mêmes rôles.
 */
@Immutable
class HeyColors(
    val primary: Color,
    /** Texte posé sur [primary]. */
    val onPrimary: Color,
    val background: Color,
    val onBackground: Color,
    /** Texte secondaire : libellés, sous-titres. */
    val onBackgroundMuted: Color,
    /** Teinte du verre dépoli, posée sur le flou. */
    val glassFill: Color,
    /** Verre sans flou (repli) : plus opaque, pour garder le texte lisible. */
    val glassFillFallback: Color,
    val glassBorder: Color,
    val error: Color,
    /** Seconde couleur des halos décoratifs du fond. */
    val accent: Color,
    /** Reflet du haut d'une surface en verre (le filet s'éclaircit vers le haut). */
    val glassHighlight: Color,
    /** État « disponible ». */
    val success: Color,
    /** État « en cours ». */
    val warning: Color,
)

private val DarkColors = HeyColors(
    primary = Color(0xFF4F9CF7),
    onPrimary = Color(0xFF0C0C0F),
    background = Color(0xFF0C0C0F),
    onBackground = Color(0xFFF5F5F8),
    onBackgroundMuted = Color(0xFFF5F5F8).copy(alpha = 0.72f),
    glassFill = Color.White.copy(alpha = 0.09f),
    glassFillFallback = Color(0xFF1B1B22).copy(alpha = 0.88f),
    glassBorder = Color.White.copy(alpha = 0.2f),
    error = Color(0xFFFF8A80),
    accent = Color(0xFF7C5CFF),
    glassHighlight = Color.White.copy(alpha = 0.42f),
    success = Color(0xFF5FD49A),
    warning = Color(0xFFF2B65A),
)

private val LightColors = HeyColors(
    primary = Color(0xFF1A66CC),
    onPrimary = Color.White,
    background = Color(0xFFF3F5FA),
    onBackground = Color(0xFF121317),
    onBackgroundMuted = Color(0xFF121317).copy(alpha = 0.68f),
    glassFill = Color.White.copy(alpha = 0.55f),
    glassFillFallback = Color.White.copy(alpha = 0.92f),
    glassBorder = Color(0xFF121317).copy(alpha = 0.12f),
    error = Color(0xFFB3261E),
    accent = Color(0xFFB9A8FF),
    glassHighlight = Color.White.copy(alpha = 0.95f),
    success = Color(0xFF12703F),
    warning = Color(0xFF8A5300),
)

/** Typographie : sans-serif système, titres larges et nets. */
@Immutable
class HeyTypography(
    val display: TextStyle,
    /** Titre d'une fiche ou d'un grand encart. */
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    /** Légende sous une affiche, mentions secondaires. */
    val caption: TextStyle,
)

private val DefaultTypography = HeyTypography(
    display = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
    ),
    title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
)

/** Formes très arrondies. */
@Immutable
class HeyShapes(
    val card: RoundedCornerShape,
    val button: RoundedCornerShape,
    /** Barre d'onglets flottante et pastilles : bords entièrement arrondis. */
    val pill: RoundedCornerShape,
    /** Affiches et vignettes. */
    val poster: RoundedCornerShape,
)

private val DefaultShapes = HeyShapes(
    card = RoundedCornerShape(28.dp),
    button = RoundedCornerShape(20.dp),
    pill = RoundedCornerShape(percent = 50),
    poster = RoundedCornerShape(16.dp),
)

// Valeurs par défaut sûres : un composable hors de HeyTheme reste affichable.
private val LocalHeyColors = staticCompositionLocalOf { DarkColors }
private val LocalHeyTypography = staticCompositionLocalOf { DefaultTypography }
private val LocalHeyShapes = staticCompositionLocalOf { DefaultShapes }

/** Accès aux jetons du thème courant : `HeyTheme.colors.primary`, etc. */
object HeyTheme {
    val colors: HeyColors
        @Composable @ReadOnlyComposable
        get() = LocalHeyColors.current

    val typography: HeyTypography
        @Composable @ReadOnlyComposable
        get() = LocalHeyTypography.current

    val shapes: HeyShapes
        @Composable @ReadOnlyComposable
        get() = LocalHeyShapes.current
}

/** Thème d'HeyWatchlist, sombre ou clair selon le réglage du système. */
@Composable
fun HeyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalHeyColors provides colors,
        LocalHeyTypography provides DefaultTypography,
        LocalHeyShapes provides DefaultShapes,
        content = content,
    )
}
