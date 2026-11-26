package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/** Champ de saisie sur une seule ligne, en verre, avec texte d'exemple. */
@Composable
fun HeyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    /** Par exemple `PasswordVisualTransformation()` pour masquer un secret. */
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = HeyTheme.colors
    val shape = HeyTheme.shapes.button
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = HeyTheme.typography.title.copy(color = colors.onBackground),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { field ->
            Box(
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .clip(shape)
                    .background(colors.glassFill)
                    .border(1.dp, colors.glassBorder, shape)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) HeyText(text = placeholder, style = HeyTheme.typography.title, color = colors.onBackgroundMuted)
                field()
            }
        },
    )
}
