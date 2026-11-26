package app.heywatchlist.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Faux quand la personne a supprimé les animations dans les réglages
 * d'accessibilité d'Android (échelle de durée des animations à zéro) : les
 * composants animés se figent alors. Lu à l'affichage de l'écran.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
