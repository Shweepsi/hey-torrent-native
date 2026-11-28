package app.heywatchlist.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.floor

/**
 * Décor des écrans d'accueil et d'attente : un mur d'affiches qui défile
 * lentement, incliné, et se fond dans le fond de l'app vers le bas. [posters] :
 * les affiches en tendance (adresses d'images) ; tant qu'il n'y en a pas assez
 * (chargement, pas de réseau), le mur est dessiné. Les surfaces en verre du
 * [content] le floutent.
 */
@Composable
fun CinemaBackdrop(
    modifier: Modifier = Modifier,
    posters: List<String> = emptyList(),
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = rememberHazeState()
    Box(modifier = modifier.fillMaxSize()) {
        PosterWall(Modifier.fillMaxSize().hazeSource(hazeState), posters = posters)
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            content()
        }
    }
}

/**
 * Le mur seul : de vraies affiches s'il y en a au moins [MIN_POSTERS], sinon
 * des affiches dessinées. Le défilement n'est lu qu'au dessin ou à la mise en
 * place des couches : rien ne se recompose. Sans animations système, le mur
 * est fixe.
 */
@Composable
fun PosterWall(modifier: Modifier = Modifier, posters: List<String> = emptyList()) {
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
    Box(modifier = modifier.background(HeyTheme.colors.background)) {
        if (posters.size >= MIN_POSTERS) {
            ImageWall(posters = posters, drift = drift)
        } else {
            DrawnWall(drift = drift)
        }
        WallVeil()
    }
}

/**
 * Affiches réelles, en colonnes inclinées. Chaque colonne répète sa part des
 * affiches et glisse d'une répétition entière par boucle : la reprise ne se
 * voit pas.
 */
@Composable
private fun ImageWall(posters: List<String>, drift: State<Float>) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val wallWidth = maxWidth * WALL_SCALE
        val tileWidth = (wallWidth - WallGap * (COLUMNS - 1)) / COLUMNS
        val period = tileWidth * 1.5f + WallGap
        val perColumn = (posters.size / COLUMNS).coerceAtLeast(1)
        // Assez de répétitions pour couvrir l'écran incliné, plus une qui entre.
        val visible = ((maxHeight * WALL_SCALE) / period).toInt() + 2
        val repeats = visible / perColumn + 2
        Row(
            modifier = Modifier
                .requiredWidth(wallWidth)
                .requiredHeight(maxHeight * WALL_SCALE)
                .align(Alignment.Center)
                .graphicsLayer { rotationZ = TILT_DEGREES },
            horizontalArrangement = Arrangement.spacedBy(WallGap),
        ) {
            repeat(COLUMNS) { column ->
                val direction = if (column % 2 == 0) 1f else -1f
                Column(
                    modifier = Modifier
                        .width(tileWidth)
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .graphicsLayer {
                            val loop = period.toPx() * perColumn
                            // Une colonne sur deux monte, l'autre descend.
                            translationY = if (direction > 0) -loop + drift.value * loop else -drift.value * loop
                        },
                    verticalArrangement = Arrangement.spacedBy(WallGap),
                ) {
                    repeat(repeats * perColumn) { row ->
                        HeyPoster(
                            url = posters[(column * perColumn + row % perColumn) % posters.size],
                            modifier = Modifier.width(tileWidth).aspectRatio(POSTER_ASPECT_RATIO),
                            shape = RoundedCornerShape(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Voile qui monte du bas, et halos de couleur : le texte ne se pose jamais sur les affiches. */
@Composable
private fun WallVeil() {
    val colors = HeyTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().drawWithCache {
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
            // Le voile s'épaissit avant le titre (vers 40 % de la hauteur).
            val fade = Brush.verticalGradient(
                0f to colors.background.copy(alpha = 0.2f),
                0.3f to colors.background.copy(alpha = 0.55f),
                0.48f to colors.background.copy(alpha = 0.88f),
                1f to colors.background,
            )
            onDrawBehind {
                drawRect(fade)
                halos.forEach { drawRect(it) }
            }
        },
    )
}

/** Affiches dessinées (des dégradés), en une seule passe : pas un composable par affiche. */
@Composable
private fun DrawnWall(drift: State<Float>) {
    Box(
        modifier = Modifier.fillMaxSize().drawWithCache {
            val gap = WallGap.toPx()
            val radius = CornerRadius(18.dp.toPx())
            // Le mur déborde de l'écran : incliné, il doit en couvrir les coins.
            val wallWidth = size.width * WALL_SCALE
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
            onDrawBehind {
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
private const val WALL_SCALE = 1.4f
private val WallGap = 12.dp

/** En dessous, le mur réel se répéterait trop : on garde le mur dessiné. */
private const val MIN_POSTERS = 8
private const val TILT_DEGREES = -9f
private const val LOOP_MILLIS = 90_000
private const val STILL_DRIFT = 0.2f
