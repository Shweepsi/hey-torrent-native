package app.heywatchlist.ui.discover

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.core.MediaKind
import app.heywatchlist.ui.components.HeyIcon
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme

/** Sépare les éléments d'une ligne d'informations (« 2024 · 1 h 45 »). */
internal const val META_SEPARATOR = " · "

/** Couleur des étoiles de note : la note est aussi écrite en chiffres, la teinte n'est qu'un repère. */
private val StarColor = Color(0xFFFFC857)

@Composable
internal fun kindLabel(kind: MediaKind): String =
    stringResource(if (kind == MediaKind.Movie) R.string.kind_movie else R.string.kind_series)

/** « 45 min », « 1 h 05 », « 2 h 00 ». */
@Composable
internal fun runtimeLabel(minutes: Int): String =
    if (minutes < MINUTES_PER_HOUR) {
        stringResource(R.string.runtime_minutes, minutes)
    } else {
        stringResource(R.string.runtime_hours_minutes, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    }

private const val MINUTES_PER_HOUR = 60

/** Texte d'une ressource `plurals` ; [formatArgs] viennent après le nombre. */
@Composable
internal fun pluralString(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String {
    val resources = LocalContext.current.resources
    return resources.getQuantityString(id, count, *formatArgs)
}

/** Titre d'une section, annoncé comme un titre par les lecteurs d'écran. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    HeyText(
        text = text,
        style = HeyTheme.typography.title,
        modifier = modifier.semantics { heading() },
    )
}

/** Étoile et note sur 10 ; rien si le titre n'a pas de note. */
@Composable
internal fun RatingBadge(
    rating: Double,
    voteCount: Int,
    modifier: Modifier = Modifier,
    color: Color = HeyTheme.colors.onBackground,
    style: TextStyle = HeyTheme.typography.caption,
) {
    if (rating <= 0.0 || voteCount <= 0) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeyIcon(icon = HeyIcons.Star, contentDescription = null, tint = StarColor, iconSize = 16.dp)
        HeyText(text = stringResource(R.string.rating_out_of_ten, formatRating(rating)), style = style, color = color)
    }
}

/** Marge horizontale commune aux écrans de la section Découvrir. */
internal val ScreenPadding = 16.dp

internal fun Modifier.screenPadding(): Modifier = padding(horizontal = ScreenPadding)
