package app.heywatchlist.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heywatchlist.core.Title
import app.heywatchlist.ui.theme.HeyTheme

/** Rapport largeur/hauteur d'une affiche. */
const val POSTER_ASPECT_RATIO = 2f / 3f

/**
 * Affiche d'un titre avec son nom en dessous (jamais sur l'affiche). La largeur
 * vient de l'appelant. [caption] : année, date de sortie…
 */
@Composable
fun TitleCard(title: Title, onClick: () -> Unit, modifier: Modifier = Modifier, caption: String? = null) {
    val press = rememberPress()
    Column(
        modifier = modifier
            .pressScale(press)
            // Pas de découpe arrondie sur toute la carte : ses coins rognaient la légende
            // sous l'affiche. L'enfoncement suffit comme retour au toucher.
            .clickable(interactionSource = press.interaction, indication = null, role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeyPoster(
            url = title.posterUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT_RATIO)
                .border(1.dp, HeyTheme.colors.glassBorder, HeyTheme.shapes.poster),
        )
        // Légende de hauteur fixe : deux lignes de titre et une de légende, toujours. Dans une
        // rangée qui défile, une carte à titre court puis une à deux lignes feraient grandir la
        // rangée (et sauter tout ce qui est dessous) quand elle entre à l'écran.
        val line = with(LocalDensity.current) { HeyTheme.typography.caption.lineHeight.toDp() }
        HeyText(
            text = title.title,
            style = HeyTheme.typography.caption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = line * 2),
        )
        HeyText(
            text = caption.orEmpty(),
            style = HeyTheme.typography.caption,
            color = HeyTheme.colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = line),
        )
    }
}

/** Squelette d'une [TitleCard]. */
@Composable
fun TitleCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Skeleton(modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_ASPECT_RATIO))
        // Même hauteur que la légende d'une carte : rien ne saute quand les titres arrivent.
        val line = with(LocalDensity.current) { HeyTheme.typography.caption.lineHeight.toDp() }
        Skeleton(modifier = Modifier.fillMaxWidth(0.8f).height(12.dp), shape = HeyTheme.shapes.button)
        Spacer(Modifier.height(line * 2 - 12.dp - 6.dp))
        Skeleton(modifier = Modifier.fillMaxWidth(0.5f).height(12.dp), shape = HeyTheme.shapes.button)
    }
}
