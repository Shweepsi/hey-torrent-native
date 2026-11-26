package app.heywatchlist.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Bouton rond en verre portant une icône, cible de 48 dp. [contentDescription] dit ce qu'il fait. */
@Composable
fun HeyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        // Le découpage précède le toucher : le retour visuel suit le rond, pas le carré.
        modifier = modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
    ) {
        HeyIcon(icon = icon, contentDescription = contentDescription, modifier = Modifier.align(Alignment.Center))
    }
}
