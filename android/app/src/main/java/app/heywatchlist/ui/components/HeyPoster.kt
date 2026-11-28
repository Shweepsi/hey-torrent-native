package app.heywatchlist.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import kotlinx.coroutines.launch

/**
 * Affiche, fond, vignette ou logo chargé par Coil : un fond uni tant que l'image
 * n'est pas là, puis l'image. Si elle manque ou échoue, le fond porte
 * [placeholderIcon]. Le contenu est décoratif : l'appelant porte le libellé.
 *
 * Seule une image qui arrive du réseau ou du disque apparaît en fondu ; une
 * image déjà en mémoire (affiche qui revient à l'écran au défilement, au
 * changement d'onglet ou au retour d'une fiche) est là tout de suite. Sans
 * cela, chaque retour refaisait le passage fond uni puis fondu : un
 * clignotement.
 *
 * Quand [url] change (l'aperçu d'une liste, puis la même image en plus grand sur
 * la fiche), la dernière image affichée reste dessous jusqu'à la fin du fondu de
 * la nouvelle, et lui sert de repli si elle échoue.
 */
@Composable
fun HeyPoster(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = HeyTheme.shapes.poster,
    placeholderIcon: ImageVector = HeyIcons.Film,
) {
    val colors = HeyTheme.colors
    // Dernière adresse dont l'image est entièrement affichée.
    var shown by remember { mutableStateOf<String?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    val animations = rememberAnimationsEnabled()
    // Opacité de l'image : lue au dessin seulement, le fondu ne recompose rien.
    val fade = remember(url) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val previous = if (url != null) shown?.takeIf { it != url } else null
    Box(
        modifier = modifier.clip(shape).background(colors.glassFillFallback),
        contentAlignment = Alignment.Center,
    ) {
        if (previous != null) {
            AsyncImage(
                model = previous,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (url == null || (failed && previous == null)) {
            HeyIcon(icon = placeholderIcon, contentDescription = null, tint = colors.onBackgroundMuted, iconSize = 32.dp)
        } else if (!failed) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
                contentScale = ContentScale.Crop,
                onSuccess = { success ->
                    val fromMemory = success.result.dataSource == DataSource.MEMORY_CACHE
                    scope.launch {
                        if (fromMemory || !animations) {
                            fade.snapTo(1f)
                        } else {
                            fade.animateTo(1f, tween(durationMillis = FADE_MILLIS))
                        }
                        shown = url
                    }
                },
                onError = { failed = true },
            )
        }
    }
}

private const val FADE_MILLIS = 220
