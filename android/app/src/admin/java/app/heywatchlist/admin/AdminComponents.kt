package app.heywatchlist.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.heywatchlist.R
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyIconButton
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeySpinner
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Carte en verre d'une liste qui défile : sans flou (`docs/DESIGN.md`, le flou
 * est réservé aux surfaces fixes).
 */
@Composable
fun AdminCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier = modifier.fillMaxWidth(), hazeState = null) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Titre d'écran, avec un retour facultatif. */
@Composable
fun AdminHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            HeyIconButton(icon = HeyIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack)
        }
        HeyText(
            text = title,
            style = HeyTheme.typography.headline,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 2,
        )
    }
}

/** Message d'échec en rouge. */
@Composable
fun FailureText(failure: AdminFailure, modifier: Modifier = Modifier) {
    HeyText(text = stringResource(failure.messageRes), color = HeyTheme.colors.error, modifier = modifier)
}

/** Échec d'une lecture, avec « Réessayer ». */
@Composable
fun FailurePanel(failure: AdminFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    AdminCard(modifier = modifier) {
        FailureText(failure)
        HeyButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            kind = HeyButtonKind.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Texte discret au centre d'une liste vide. */
@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier) {
    HeyText(text = text, color = HeyTheme.colors.onBackgroundMuted, modifier = modifier.padding(24.dp))
}

/** Chargement en cours, avec sa description lue par les lecteurs d'écran. */
@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeySpinner(description = text)
        HeyText(text = text, color = HeyTheme.colors.onBackgroundMuted)
    }
}

/** Fenêtre de confirmation : verre sans flou, rien derrière à flouter. */
@Composable
fun AdminDialog(
    title: String,
    onDismiss: () -> Unit,
    body: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), hazeState = null) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HeyText(text = title, style = HeyTheme.typography.title)
                body?.let { HeyText(text = it, color = HeyTheme.colors.onBackgroundMuted) }
                content()
            }
        }
    }
}

/** Deux boutons côte à côte, de même largeur. */
@Composable
fun ButtonRow(content: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        content(Modifier.weight(1f))
    }
}
