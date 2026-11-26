package app.heywatchlist.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Icônes dessinées à la main (pas de bibliothèque d'icônes), sur une grille de
 * 24 × 24. Les contours sont noirs : [HeyIcon] les teinte.
 */
object HeyIcons {
    val Search: ImageVector by lazy {
        stroked("Search") {
            moveTo(18f, 11f)
            arcTo(7f, 7f, 0f, true, true, 4f, 11f)
            arcTo(7f, 7f, 0f, true, true, 18f, 11f)
            moveTo(16.2f, 16.2f)
            lineTo(21f, 21f)
        }
    }

    val Back: ImageVector by lazy {
        stroked("Back") {
            moveTo(19f, 12f)
            lineTo(5f, 12f)
            moveTo(12f, 5f)
            lineTo(5f, 12f)
            lineTo(12f, 19f)
        }
    }

    val Close: ImageVector by lazy {
        stroked("Close") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        stroked("ChevronDown") {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }

    val Play: ImageVector by lazy {
        filled("Play") {
            moveTo(8f, 5f)
            lineTo(19f, 12f)
            lineTo(8f, 19f)
            close()
        }
    }

    val Star: ImageVector by lazy {
        filled("Star") {
            moveTo(12f, 2.5f)
            lineTo(14.8f, 8.7f)
            lineTo(21.5f, 9.4f)
            lineTo(16.5f, 13.9f)
            lineTo(17.9f, 20.5f)
            lineTo(12f, 17.1f)
            lineTo(6.1f, 20.5f)
            lineTo(7.5f, 13.9f)
            lineTo(2.5f, 9.4f)
            lineTo(9.2f, 8.7f)
            close()
        }
    }

    /** Image absente : une pellicule. */
    val Film: ImageVector by lazy {
        stroked("Film") {
            moveTo(6f, 4f)
            lineTo(18f, 4f)
            arcTo(2f, 2f, 0f, false, true, 20f, 6f)
            lineTo(20f, 18f)
            arcTo(2f, 2f, 0f, false, true, 18f, 20f)
            lineTo(6f, 20f)
            arcTo(2f, 2f, 0f, false, true, 4f, 18f)
            lineTo(4f, 6f)
            arcTo(2f, 2f, 0f, false, true, 6f, 4f)
            close()
            moveTo(8f, 4f)
            lineTo(8f, 20f)
            moveTo(16f, 4f)
            lineTo(16f, 20f)
            moveTo(4f, 9f)
            lineTo(8f, 9f)
            moveTo(4f, 15f)
            lineTo(8f, 15f)
            moveTo(16f, 9f)
            lineTo(20f, 9f)
            moveTo(16f, 15f)
            lineTo(20f, 15f)
        }
    }

    /** Photo absente : un buste. */
    val Person: ImageVector by lazy {
        stroked("Person") {
            moveTo(16f, 8f)
            arcTo(4f, 4f, 0f, true, true, 8f, 8f)
            arcTo(4f, 4f, 0f, true, true, 16f, 8f)
            moveTo(4f, 21f)
            curveTo(4f, 16.5f, 7.5f, 14f, 12f, 14f)
            curveTo(16.5f, 14f, 20f, 16.5f, 20f, 21f)
        }
    }

    private fun stroked(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathBuilder,
        ).build()

    private fun filled(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = SolidColor(Color.Black),
            pathBuilder = pathBuilder,
        ).build()
}

/**
 * Une icône de [HeyIcons], teintée. [contentDescription] vaut `null` quand le
 * texte voisin dit déjà la même chose.
 */
@Composable
fun HeyIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = HeyTheme.colors.onBackground,
    iconSize: Dp = 24.dp,
) {
    Image(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(iconSize),
        colorFilter = ColorFilter.tint(tint),
    )
}
