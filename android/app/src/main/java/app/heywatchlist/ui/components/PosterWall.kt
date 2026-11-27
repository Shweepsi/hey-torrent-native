package app.heywatchlist.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.floor

/**
 * Décor des écrans d'accueil et d'attente : un mur d'affiches stylisées (des
 * dégradés, aucune image réelle : on n'a pas encore de session pour lire le
 * catalogue) qui défile lentement, incliné, et se fond dans le fond de l'app
 * vers le bas. Les surfaces en verre du [content] le floutent.
 */
@Composable
fun CinemaBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val hazeState = rememberHazeState()
    Box(modifier = modifier.fillMaxSize()) {
        PosterWall(Modifier.fillMaxSize().hazeSource(hazeState))
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            content()
        }
    }
}

/**
 * Le mur seul. Tout est dessiné dans une seule passe (pas un composable par
 * affiche) et le défilement n'est lu qu'au dessin : rien ne se recompose.
 * Sans animations système, le mur est fixe.
 */
@Composable
fun PosterWall(modifier: Modifier = Modifier) {
    val colors = HeyTheme.colors
    val drift = if (rememberAnimationsEnabled()) {
        rememberInfiniteTransition(label = "poster-wall").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = LOOP_MILLIS, easing = LinearEasing)),
            label = "poster-wall-drift",
        )
    } else {
        remember { mutableFloatStateOf(STILL_DRIFT) }
    }
    Box(
        modifier = modifier.drawWithCache {
            val gap = 12.dp.toPx()
            val radius = CornerRadius(18.dp.toPx())
            // Le mur déborde de l'écran : incliné, il doit en couvrir les coins.
            val wallWidth = size.width * 1.4f
            val tileWidth = (wallWidth - gap * (COLUMNS - 1)) / COLUMNS
            val tileHeight = tileWidth * 1.5f
            val period = tileHeight + gap
            val tileSize = Size(tileWidth, tileHeight)
            val fills = Palette.map { (top, bottom) ->
                Brush.linearGradient(listOf(top, bottom), start = Offset.Zero, end = Offset(tileWidth * 0.5f, tileHeight))
            }
            val glows = Palette.map { (top, _) ->
                Brush.radialGradient(
                    listOf(lighten(top).copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(tileWidth * 0.68f, tileHeight * 0.32f),
                    radius = tileWidth * 0.62f,
                )
            }
            val sheen = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.18f),
                0.3f to Color.Transparent,
                startY = 0f,
                endY = tileHeight,
            )
            val band = Color.Black.copy(alpha = 0.28f)
            val longest = maxOf(size.width, size.height)
            val halos = listOf(
                Brush.radialGradient(
                    listOf(colors.primary.copy(alpha = 0.35f), Color.Transparent),
                    center = Offset(size.width * 0.1f, size.height * 0.95f),
                    radius = longest * 0.6f,
                ),
                Brush.radialGradient(
                    listOf(colors.accent.copy(alpha = 0.3f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.7f),
                    radius = longest * 0.55f,
                ),
            )
            val fade = Brush.verticalGradient(
                0f to colors.background.copy(alpha = 0.15f),
                0.35f to colors.background.copy(alpha = 0.45f),
                0.62f to colors.background.copy(alpha = 0.9f),
                1f to colors.background,
            )
            onDrawBehind {
                drawRect(colors.background)
                rotate(degrees = TILT_DEGREES) {
                    val left = (size.width - wallWidth) / 2f
                    for (column in 0 until COLUMNS) {
                        // Une colonne sur deux monte, l'autre descend ; une boucle fait défiler
                        // autant d'affiches qu'il y a de teintes, pour que la reprise ne se voie pas.
                        val direction = if (column % 2 == 0) 1f else -1f
                        val travel = drift.value * Palette.size * period * direction
                        val rows = floor(travel / period)
                        val shift = travel - rows * period
                        val x = left + column * (tileWidth + gap)
                        var row = 0
                        var y = -2f * period + shift
                        while (y < size.height + period) {
                            val index = Math.floorMod(column * 3 + row - rows.toInt(), Palette.size)
                            translate(left = x, top = y) {
                                drawRoundRect(brush = fills[index], size = tileSize, cornerRadius = radius)
                                drawRoundRect(brush = glows[index], size = tileSize, cornerRadius = radius)
                                drawRoundRect(brush = sheen, size = tileSize, cornerRadius = radius)
                                drawRoundRect(
                                    color = band,
                                    topLeft = Offset(tileWidth * 0.12f, tileHeight * 0.8f),
                                    size = Size(tileWidth * 0.62f, tileHeight * 0.05f),
                                    cornerRadius = CornerRadius(tileHeight * 0.025f),
                                )
                            }
                            y += period
                            row++
                        }
                    }
                }
                drawRect(fade)
                halos.forEach { drawRect(it) }
            }
        },
    )
}

/** Teintes des affiches : du haut vers le bas de chacune. */
private val Palette = listOf(
    Color(0xFF2A4F9E) to Color(0xFF0E1630),
    Color(0xFFB4475A) to Color(0xFF3A1430),
    Color(0xFF2E8C83) to Color(0xFF0C2A2E),
    Color(0xFFD9893B) to Color(0xFF4A1F12),
    Color(0xFF6B4FD8) to Color(0xFF1B1446),
    Color(0xFF3C7A4F) to Color(0xFF0E2117),
)

private fun lighten(color: Color) = Color(
    red = color.red + (1f - color.red) * 0.45f,
    green = color.green + (1f - color.green) * 0.45f,
    blue = color.blue + (1f - color.blue) * 0.45f,
)

private const val COLUMNS = 4
private const val TILT_DEGREES = -9f
private const val LOOP_MILLIS = 90_000
private const val STILL_DRIFT = 0.2f
