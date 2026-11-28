package app.heywatchlist.admin

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.heywatchlist.R
import app.heywatchlist.appServices
import app.heywatchlist.core.KeystoreSessionStore
import app.heywatchlist.ui.components.AppBackdrop
import app.heywatchlist.ui.components.GlassTab
import app.heywatchlist.ui.components.GlassTabBar
import app.heywatchlist.ui.components.HeyIconButton
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.theme.HeyTheme
import app.heywatchlist.ui.theme.transparentBars
import uniffi.heywatchlist_core.AdminRequest

/** Espace admin, ouvert depuis Réglages : une activité à part, absente de l'app publique. */
class AdminActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Un changement de thème du téléphone recrée l'activité : les barres suivent.
        val bars = transparentBars(dark = resources.configuration.isNightModeActive)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        val gateway = adminGateway(this)
        setContent {
            HeyTheme {
                AdminRoot(gateway = gateway, onClose = ::finish)
            }
        }
    }
}

/** Sections de l'espace admin. */
internal enum class AdminTab(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    Requests(R.string.admin_tab_requests, HeyIcons.Inbox),
    Downloads(R.string.admin_tab_downloads, HeyIcons.Download),
    Servers(R.string.admin_tab_servers, HeyIcons.Server),
    Access(R.string.admin_tab_access, HeyIcons.People),
}

@Composable
private fun AdminRoot(gateway: AdminGateway, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(AdminTab.Requests) }
    // La fiche ouverte garde la demande telle qu'à l'ouverture, même si elle sort de la liste
    // (refusée depuis « À traiter ») ; après une rotation, on la retrouve dans la liste.
    var openRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<AdminRequest?>(null) }
    val requests: RequestsViewModel = viewModel(factory = remember(gateway) { RequestsViewModel.factory(gateway) })
    val requestsState by requests.uiState.collectAsStateWithLifecycle()
    val openRequest = openRequestId?.let { id ->
        opened?.takeIf { it.id == id } ?: requestsState.requests.firstOrNull { it.id == id }
    }

    AppBackdrop {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (openRequest != null) {
                    BackHandler { openRequestId = null }
                    val detail: RequestViewModel = viewModel(
                        key = "request-${openRequest.id}",
                        factory = remember(gateway, openRequest.id) { RequestViewModel.factory(gateway, openRequest) },
                    )
                    RequestScreen(viewModel = detail, onBack = { openRequestId = null }, onChanged = requests::replace)
                } else {
                    when (tab) {
                        AdminTab.Requests -> RequestsScreen(
                            viewModel = requests,
                            onOpen = {
                                opened = it
                                openRequestId = it.id
                            },
                        )
                        AdminTab.Downloads -> DownloadsScreen(
                            viewModel = viewModel(factory = remember(gateway) { DownloadsViewModel.factory(gateway) }),
                        )
                        AdminTab.Servers -> ServersScreen(
                            viewModel = viewModel(factory = remember(gateway) { ServersViewModel.factory(gateway) }),
                        )
                        AdminTab.Access -> AccessScreen(
                            viewModel = viewModel(factory = remember(gateway) { AccessViewModel.factory(gateway) }),
                        )
                    }
                }
            }
            if (openRequest == null) AdminTabBar(selected = tab, onSelect = { tab = it }, onClose = onClose)
        }
    }
}

@Composable
internal fun AdminTabBar(selected: AdminTab, onSelect: (AdminTab) -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeyIconButton(icon = HeyIcons.Close, contentDescription = stringResource(R.string.admin_close), onClick = onClose)
        GlassTabBar(
            tabs = AdminTab.entries.map { GlassTab(label = stringResource(it.labelRes), icon = it.icon) },
            selectedIndex = selected.ordinal,
            onSelect = { onSelect(AdminTab.entries[it]) },
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

/** Espace admin du processus : un seul, partagé par les ouvertures successives de l'activité. */
private object AdminProcess {
    private var gateway: AdminGateway? = null

    @Synchronized
    fun gateway(context: Context): AdminGateway = gateway ?: UniffiAdminGateway(
        account = context.appServices.account,
        settingsStore = KeystoreSessionStore(context, file = SETTINGS_FILE, alias = SETTINGS_ALIAS),
    ).also { gateway = it }

    const val SETTINGS_FILE = "admin-settings"
    const val SETTINGS_ALIAS = "heywatchlist-admin-settings"
}

private fun adminGateway(context: Context): AdminGateway = AdminProcess.gateway(context)
