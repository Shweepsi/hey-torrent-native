package app.heywatchlist.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.settings.SettingsScreen
import app.heywatchlist.ui.theme.HeyTheme

/** Les trois onglets de l'app. */
enum class Tab(@param:StringRes val labelRes: Int) {
    Discover(R.string.tab_discover),
    Watchlist(R.string.tab_watchlist),
    Settings(R.string.tab_settings),
}

/** Onglets Découvrir, Watchlist et Réglages, avec la barre en verre en bas. */
@Composable
fun MainScreen(state: AccountUiState.SignedIn, viewModel: AccountViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Discover) }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Discover -> ComingSoon(R.string.tab_discover, R.string.discover_coming_soon)
                Tab.Watchlist -> ComingSoon(R.string.tab_watchlist, R.string.watchlist_coming_soon)
                Tab.Settings -> SettingsScreen(state = state, viewModel = viewModel)
            }
        }
        TabBar(selected = tab, onSelect = { tab = it })
    }
}

@Composable
private fun ComingSoon(@StringRes titleRes: Int, @StringRes bodyRes: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeyText(
            text = stringResource(titleRes),
            style = HeyTheme.typography.display,
            modifier = Modifier.semantics { heading() },
        )
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            HeyText(
                text = stringResource(bodyRes),
                color = HeyTheme.colors.onBackgroundMuted,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(6.dp)) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .semantics { this.selected = isSelected },
                    contentAlignment = Alignment.Center,
                ) {
                    HeyText(
                        text = stringResource(tab.labelRes),
                        style = HeyTheme.typography.label,
                        color = if (isSelected) HeyTheme.colors.primary else HeyTheme.colors.onBackgroundMuted,
                    )
                }
            }
        }
    }
}
