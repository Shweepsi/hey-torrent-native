package app.heywatchlist.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

private val FieldHeight = 52.dp

/**
 * Champ de recherche en verre : loupe, saisie, et croix pour tout effacer.
 * [label] est lu par les lecteurs d'écran ; [clearLabel] décrit la croix.
 */
@Composable
fun HeySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val colors = HeyTheme.colors
    val focusManager = LocalFocusManager.current
    GlassSurface(modifier = modifier.fillMaxWidth(), shape = CircleShape) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = FieldHeight).padding(start = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeyIcon(icon = HeyIcons.Search, contentDescription = null, tint = colors.onBackgroundMuted, iconSize = 22.dp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = HeyTheme.typography.body.copy(color = colors.onBackground),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // La recherche part déjà à la frappe : « Rechercher » ferme le clavier pour voir les résultats.
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = label },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            HeyText(text = placeholder, color = colors.onBackgroundMuted, maxLines = 1)
                        }
                        field()
                    }
                },
            )
            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(FieldHeight)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = { onValueChange("") }),
                    contentAlignment = Alignment.Center,
                ) {
                    HeyIcon(icon = HeyIcons.Close, contentDescription = clearLabel, iconSize = 20.dp)
                }
            } else {
                Box(modifier = Modifier.size(width = 18.dp, height = FieldHeight))
            }
        }
    }
}

/**
 * Faux champ de recherche : un toucher ouvre l'écran de recherche. Même
 * apparence que [HeySearchField], mais c'est un bouton.
 */
@Composable
fun HeySearchEntry(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HeyTheme.colors
    GlassSurface(
        modifier = modifier.fillMaxWidth().clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = FieldHeight).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeyIcon(icon = HeyIcons.Search, contentDescription = null, tint = colors.onBackgroundMuted, iconSize = 22.dp)
            HeyText(text = text, color = colors.onBackgroundMuted, maxLines = 1)
        }
    }
}
