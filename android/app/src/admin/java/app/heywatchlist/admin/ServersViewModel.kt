package app.heywatchlist.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.heywatchlist_core.QbitAuth
import uniffi.heywatchlist_core.QbitSettings
import uniffi.heywatchlist_core.ServerSettings
import uniffi.heywatchlist_core.SourceFailure
import uniffi.heywatchlist_core.TorznabSource

/** Une source Torznab telle que saisie. */
data class SourceForm(
    val id: String,
    val name: String = "",
    val url: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
)

/** Réglages des serveurs tels que saisis ; [toSettings] les rend au cœur. */
data class ServerForm(
    val sources: List<SourceForm> = emptyList(),
    val qbitUrl: String = "",
    /** Connexion par jeton (en-tête Bearer) plutôt que par identifiant et mot de passe. */
    val qbitUsesToken: Boolean = false,
    val qbitUsername: String = "",
    val qbitPassword: String = "",
    val qbitToken: String = "",
    val movieCategory: String = DEFAULT_MOVIE_CATEGORY,
    val seriesCategory: String = DEFAULT_SERIES_CATEGORY,
    /** Termes exclus, séparés par des virgules. */
    val excludedTerms: String = "",
) {
    fun qbitSettings(): QbitSettings? = qbitUrl.trim().takeIf { it.isNotEmpty() }?.let { url ->
        val auth = if (qbitUsesToken) {
            QbitAuth.Bearer(qbitToken.trim())
        } else {
            QbitAuth.Password(qbitUsername.trim(), qbitPassword)
        }
        QbitSettings(url, auth)
    }

    fun toSettings(): ServerSettings = ServerSettings(
        sources = sources.map { it.toSource() },
        qbittorrent = qbitSettings(),
        movieCategory = movieCategory.trim().ifEmpty { DEFAULT_MOVIE_CATEGORY },
        seriesCategory = seriesCategory.trim().ifEmpty { DEFAULT_SERIES_CATEGORY },
        excludedTerms = excludedTerms.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )

    companion object {
        const val DEFAULT_MOVIE_CATEGORY = "films"
        const val DEFAULT_SERIES_CATEGORY = "series"

        fun from(settings: ServerSettings): ServerForm {
            val qbit = settings.qbittorrent
            val auth = qbit?.auth
            return ServerForm(
                sources = settings.sources.map { SourceForm(it.id, it.name, it.url, it.apiKey, it.enabled) },
                qbitUrl = qbit?.url.orEmpty(),
                qbitUsesToken = auth is QbitAuth.Bearer,
                qbitUsername = (auth as? QbitAuth.Password)?.username.orEmpty(),
                qbitPassword = (auth as? QbitAuth.Password)?.password.orEmpty(),
                qbitToken = (auth as? QbitAuth.Bearer)?.token.orEmpty(),
                movieCategory = settings.movieCategory,
                seriesCategory = settings.seriesCategory,
                excludedTerms = settings.excludedTerms.joinToString(", "),
            )
        }
    }
}

fun SourceForm.toSource() = TorznabSource(id = id, name = name.trim(), url = url.trim(), apiKey = apiKey.trim(), enabled = enabled)

/** Essai d'une source ou de qBittorrent. */
sealed interface ConnectionTest {
    data object Running : ConnectionTest

    /** [detail] : version de qBittorrent, vide pour une source. */
    data class Passed(val detail: String = "") : ConnectionTest

    data class SourceFailed(val failure: SourceFailure) : ConnectionTest

    data class Failed(val failure: AdminFailure) : ConnectionTest
}

data class ServersUiState(
    /** Lecture des réglages locaux, puis du serveur. */
    val loading: Boolean = true,
    /** La copie du serveur n'a pas pu être lue : on montre la copie locale. */
    val syncFailure: AdminFailure? = null,
    /** Essais des sources, par identifiant. */
    val sourceTests: Map<String, ConnectionTest> = emptyMap(),
    val qbitTest: ConnectionTest? = null,
    val saving: Boolean = false,
    val saveFailure: AdminFailure? = null,
    /** Modifications non enregistrées. */
    val dirty: Boolean = false,
    val saved: Boolean = false,
)

/**
 * Réglages des serveurs (sources Torznab, qBittorrent, catégories). Ils sont
 * chiffrés sur le serveur et gardés chiffrés sur l'appareil ; à l'ouverture,
 * la copie locale s'affiche puis la copie du serveur la remplace.
 */
class ServersViewModel(private val gateway: AdminGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(ServersUiState())
    val uiState: StateFlow<ServersUiState> = _uiState.asStateFlow()

    /** Formulaire, en état Compose : aucune frappe perdue. */
    var form by mutableStateOf(ServerForm())
        private set

    init {
        viewModelScope.launch {
            attemptAdmin { gateway.settings() }.onSuccess { form = ServerForm.from(it) }
            attemptAdmin { gateway.syncSettings() }
                .onSuccess { settings ->
                    if (!_uiState.value.dirty) form = ServerForm.from(settings)
                    _uiState.update { it.copy(loading = false) }
                }
                .onFailure { error -> _uiState.update { it.copy(loading = false, syncFailure = error.adminFailure) } }
        }
    }

    fun edit(change: (ServerForm) -> ServerForm) {
        form = change(form)
        _uiState.update { it.copy(dirty = true, saved = false, saveFailure = null) }
    }

    fun editSource(id: String, change: (SourceForm) -> SourceForm) {
        edit { current -> current.copy(sources = current.sources.map { if (it.id == id) change(it) else it }) }
        _uiState.update { it.copy(sourceTests = it.sourceTests - id) }
    }

    /** Ajoute une source vide et rend son identifiant, pour ouvrir sa feuille. */
    fun addSource(): String {
        val id = UUID.randomUUID().toString()
        edit { it.copy(sources = it.sources + SourceForm(id = id)) }
        return id
    }

    /** Teste les services réglés qui n'ont pas encore de résultat (à l'ouverture de l'écran). */
    fun testUntested() {
        form.sources
            .filter { it.enabled && it.url.isNotBlank() && it.id !in _uiState.value.sourceTests }
            .forEach { testSource(it.id) }
        if (_uiState.value.qbitTest == null) testQbittorrent()
    }

    fun removeSource(id: String) {
        edit { current -> current.copy(sources = current.sources.filterNot { it.id == id }) }
        _uiState.update { it.copy(sourceTests = it.sourceTests - id) }
    }

    fun testSource(id: String) {
        val source = form.sources.firstOrNull { it.id == id } ?: return
        if (_uiState.value.sourceTests[id] == ConnectionTest.Running) return
        _uiState.update { it.copy(sourceTests = it.sourceTests + (id to ConnectionTest.Running)) }
        viewModelScope.launch {
            val result = attemptAdmin { gateway.testSource(source.toSource()) }.fold(
                onSuccess = { failure -> failure?.let { ConnectionTest.SourceFailed(it) } ?: ConnectionTest.Passed() },
                onFailure = { error -> ConnectionTest.Failed(error.adminFailure) },
            )
            _uiState.update { it.copy(sourceTests = it.sourceTests + (id to result)) }
        }
    }

    fun testQbittorrent() {
        val settings = form.qbitSettings() ?: return
        if (_uiState.value.qbitTest == ConnectionTest.Running) return
        _uiState.update { it.copy(qbitTest = ConnectionTest.Running) }
        viewModelScope.launch {
            val result = attemptAdmin { gateway.testQbittorrent(settings) }.fold(
                onSuccess = { version -> ConnectionTest.Passed(version) },
                onFailure = { error -> ConnectionTest.Failed(error.adminFailure) },
            )
            _uiState.update { it.copy(qbitTest = result) }
        }
    }

    fun save() {
        if (_uiState.value.saving) return
        val settings = form.toSettings()
        _uiState.update { it.copy(saving = true, saveFailure = null, saved = false) }
        viewModelScope.launch {
            attemptAdmin { gateway.saveSettings(settings) }
                .onSuccess { _uiState.update { it.copy(saving = false, dirty = false, saved = true, syncFailure = null) } }
                .onFailure { error -> _uiState.update { it.copy(saving = false, saveFailure = error.adminFailure) } }
        }
    }

    companion object {
        fun factory(gateway: AdminGateway): ViewModelProvider.Factory = viewModelFactory {
            initializer { ServersViewModel(gateway) }
        }
    }
}
