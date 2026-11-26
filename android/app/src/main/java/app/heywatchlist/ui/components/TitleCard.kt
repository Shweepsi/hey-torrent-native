package app.heywatchlist.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    Column(
        modifier = modifier.clip(HeyTheme.shapes.poster).clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeyPoster(url = title.posterUrl, modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_ASPECT_RATIO))
        HeyText(
            text = title.title,
            style = HeyTheme.typography.caption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            HeyText(
                text = caption,
                style = HeyTheme.typography.caption,
                color = HeyTheme.colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Squelette d'une [TitleCard]. */
@Composable
fun TitleCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Skeleton(modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_ASPECT_RATIO))
        Skeleton(modifier = Modifier.fillMaxWidth(0.8f).height(12.dp), shape = HeyTheme.shapes.button)
        Skeleton(modifier = Modifier.fillMaxWidth(0.5f).height(12.dp), shape = HeyTheme.shapes.button)
    }
}
