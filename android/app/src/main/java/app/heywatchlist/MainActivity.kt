package app.heywatchlist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.heywatchlist.ui.AppRoot
import app.heywatchlist.ui.theme.HeyTheme

/** Activité unique : l'interface est entièrement en Compose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HeyTheme {
                AppRoot()
            }
        }
    }
}
