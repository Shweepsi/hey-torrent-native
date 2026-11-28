package app.heywatchlist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.heywatchlist.ui.AppRoot
import app.heywatchlist.ui.theme.HeyTheme
import app.heywatchlist.ui.theme.transparentBars

/** Activité unique : l'interface est entièrement en Compose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Un changement de thème du téléphone recrée l'activité : les barres suivent.
        val bars = transparentBars(dark = resources.configuration.isNightModeActive)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        setContent {
            HeyTheme {
                AppRoot()
            }
        }
    }
}
