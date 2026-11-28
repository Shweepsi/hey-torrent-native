package app.heywatchlist.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.heywatchlist_core.AdminRequest
import uniffi.heywatchlist_core.MediaType
import uniffi.heywatchlist_core.Release
import uniffi.heywatchlist_core.RequestStatus
import uniffi.heywatchlist_core.SearchQuery
import uniffi.heywatchlist_core.SourceReport

/** Recherche de releases pour une demande. */
sealed interface ReleaseSearch {
    data object Idle : ReleaseSearch

    /** [pending] : sources qui n'ont pas encore répondu. */
    data class Running(val releases: List<Release>, val reports: List<SourceReport>, val pending: Int) : ReleaseSearch

    data class Done(val releases: List<Release>, val reports: List<SourceReport>) : ReleaseSearch

    data class Failed(val failure: AdminFailure) : ReleaseSearch
}

/** Action en cours ou terminée sur la demande (envoi, refus, réouverture). */
sealed interface RequestAction {
    data object Idle : RequestAction

    data object Working : RequestAction

    data class Failed(val failure: AdminFailure) : RequestAction

    /**
     * Torrent ajouté, statut non enregistré : [releaseId] permet de réessayer
     * sans renvoyer le torrent ; [retryFailure] dit pourquoi le dernier essai a échoué.
     */
    data class NotSaved(val releaseId: String, val retryFailure: AdminFailure? = null) : RequestAction

    data object Sent : RequestAction

    data object Refused : RequestAction

    data object Reopened : RequestAction

    data object MarkedAvailable : RequestAction
}

/** Avis de Claude sur les releases trouvées. */
sealed interface Advice {
    /** Pas d'avis : pas de résultat, ou pas de clé Anthropic dans Serveurs. */
    data object None : Advice

    data object Thinking : Advice

    /**
     * [releaseId] : la release conseillée, `null` si aucune ne convient ;
     * [automatic] : trouvée par la recherche automatique (sans nouvel appel).
     */
    data class Ready(val releaseId: String?, val reason: String, val automatic: Boolean = false) : Advice

    data class Failed(val failure: AdminFailure) : Advice
}

/** Catégories qBittorrent proposées à l'envoi. */
sealed interface CategoryChoice {
    data object Loading : CategoryChoice

    /** [options] : catégories existantes de qBittorrent ; [selected] : celle de l'envoi. */
    data class Ready(val options: List<String>, val selected: String) : CategoryChoice

    data class Failed(val failure: AdminFailure) : CategoryChoice
}

data class RequestUiState(
    val request: AdminRequest,
    val search: ReleaseSearch = ReleaseSearch.Idle,
    val advice: Advice = Advice.None,
    /** Release choisie, en attente de confirmation de l'envoi. */
    val confirming: Release? = null,
    val categories: CategoryChoice = CategoryChoice.Loading,
    /** Fenêtre de confirmation de « Déjà sur Plex » ouverte. */
    val confirmingAvailable: Boolean = false,
    /** Fenêtre de refus ouverte. */
    val refusing: Boolean = false,
    val action: RequestAction = RequestAction.Idle,
)

/**
 * Fiche d'une demande : recherche dans les sources, envoi d'une release à
 * qBittorrent, refus ou réouverture. Les champs saisis vivent en état Compose
 * (pas de frappe perdue) ; le reste dans [uiState].
 */
class RequestViewModel(private val gateway: AdminGateway, request: AdminRequest) : ViewModel() {
    private val _uiState = MutableStateFlow(RequestUiState(request))
    val uiState: StateFlow<RequestUiState> = _uiState.asStateFlow()

    /** Titre cherché, modifiable (titre français par défaut). */
    var searchTitle by mutableStateOf(request.title)

    /** Saison cherchée (séries) ; vide = toute la série. */
    var season by mutableStateOf(request.seasons?.singleOrNull()?.toString().orEmpty())

    var refusalReason by mutableStateOf("")

    private var searchJob: Job? = null

    init {
        if (request.status == RequestStatus.REQUESTED) search()
    }

    fun search() {
        val request = _uiState.value.request
        val title = searchTitle.trim()
        if (title.isEmpty()) return
        val query = SearchQuery(
            mediaType = request.mediaType,
            title = title,
            originalTitle = null,
            year = request.year,
            season = if (request.mediaType == MediaType.TV) season.trim().toUIntOrNull() else null,
        )
        searchJob?.cancel()
        _uiState.update { it.copy(search = ReleaseSearch.Running(emptyList(), emptyList(), pending = 0), advice = Advice.None) }
        searchJob = viewModelScope.launch {
            attemptAdmin {
                gateway.search(query) { progress ->
                    _uiState.update { state ->
                        if (state.search is ReleaseSearch.Running) {
                            state.copy(search = ReleaseSearch.Running(progress.releases, progress.reports, progress.pending))
                        } else {
                            state
                        }
                    }
                }
            }
                .onSuccess { outcome ->
                    _uiState.update { it.copy(search = ReleaseSearch.Done(outcome.releases, outcome.reports)) }
                    advise(query, outcome.releases)
                }
                .onFailure { error -> _uiState.update { it.copy(search = ReleaseSearch.Failed(error.adminFailure)) } }
        }
    }

    /**
     * Avis de Claude sur une recherche finie. La release déjà trouvée par la
     * recherche automatique est reprise telle quelle si elle est dans la
     * liste : pas de second appel.
     */
    private suspend fun advise(query: SearchQuery, releases: List<Release>) {
        if (releases.isEmpty()) return
        val request = _uiState.value.request
        val known = request.candidateTitle?.let { title -> releases.firstOrNull { it.title == title } }
        if (known != null) {
            _uiState.update { it.copy(advice = Advice.Ready(known.id, request.candidateReason.orEmpty(), automatic = true)) }
            return
        }
        _uiState.update { it.copy(advice = Advice.Thinking) }
        val advice = attemptAdmin { gateway.recommend(query, releases) }.fold(
            onSuccess = { Advice.Ready(it.releaseId, it.reason) },
            onFailure = { error ->
                // Sans clé Anthropic, l'app marche comme avant, sans avis.
                if (error.adminFailure == AdminFailure.AssistantNotConfigured) Advice.None else Advice.Failed(error.adminFailure)
            },
        )
        _uiState.update { it.copy(advice = advice) }
    }

    fun choose(release: Release) {
        if (_uiState.value.action == RequestAction.Working) return
        _uiState.update { it.copy(confirming = release) }
        if (_uiState.value.categories !is CategoryChoice.Ready) loadCategories()
    }

    /**
     * Lit les catégories de qBittorrent. Présélection : la catégorie par défaut
     * du type (Serveurs) si elle existe, sinon la première ; si qBittorrent
     * n'en a aucune, la catégorie par défaut, créée à l'envoi.
     */
    fun loadCategories() {
        val request = _uiState.value.request
        _uiState.update { it.copy(categories = CategoryChoice.Loading) }
        viewModelScope.launch {
            val choice = attemptAdmin {
                val settings = gateway.settings()
                val preferred = if (request.mediaType == MediaType.TV) settings.seriesCategory else settings.movieCategory
                val existing = gateway.qbitCategories()
                val options = existing.ifEmpty { listOf(preferred) }
                val selected = options.firstOrNull { it == preferred }
                    ?: options.firstOrNull { it.equals(preferred, ignoreCase = true) }
                    ?: options.first()
                CategoryChoice.Ready(options, selected)
            }.getOrElse { CategoryChoice.Failed(it.adminFailure) }
            _uiState.update { it.copy(categories = choice) }
        }
    }

    fun selectCategory(category: String) {
        _uiState.update { state ->
            val ready = state.categories as? CategoryChoice.Ready ?: return@update state
            if (category in ready.options) state.copy(categories = ready.copy(selected = category)) else state
        }
    }

    fun cancelChoice() {
        _uiState.update { it.copy(confirming = null) }
    }

    /** Envoie la release confirmée à qBittorrent, puis marque la demande « en cours ». */
    fun send() {
        val state = _uiState.value
        val release = state.confirming ?: return
        val category = (state.categories as? CategoryChoice.Ready)?.selected ?: return
        val request = state.request
        run(close = { it.copy(confirming = null) }) {
            attemptAdmin { gateway.sendRelease(request.id, release.id, category) }
                .fold(
                    onSuccess = { updated -> RequestAction.Sent to updated },
                    onFailure = { error ->
                        val failure = error.adminFailure
                        val action = if (failure == AdminFailure.StatusNotSaved) {
                            RequestAction.NotSaved(release.id)
                        } else {
                            RequestAction.Failed(failure)
                        }
                        action to null
                    },
                )
        }
    }

    /** Après [RequestAction.NotSaved] : réessaie seulement de marquer la demande. */
    fun retryStatus() {
        val action = _uiState.value.action as? RequestAction.NotSaved ?: return
        val request = _uiState.value.request
        run {
            attemptAdmin { gateway.confirmRelease(request.id, action.releaseId) }
                .fold(
                    onSuccess = { updated -> RequestAction.Sent to updated },
                    onFailure = { error ->
                        val failure = error.adminFailure
                        val next = if (failure.isTransient()) action.copy(retryFailure = failure) else RequestAction.Failed(failure)
                        next to null
                    },
                )
        }
    }

    fun openRefusal() {
        _uiState.update { it.copy(refusing = true) }
    }

    fun closeRefusal() {
        _uiState.update { it.copy(refusing = false) }
    }

    fun refuse() {
        val request = _uiState.value.request
        val reason = refusalReason.trim().take(MAX_REASON).ifEmpty { null }
        run(close = { it.copy(refusing = false) }) {
            attemptAdmin { gateway.refuse(request.id, reason) }.toAction(RequestAction.Refused)
        }
    }

    fun reopen() {
        val request = _uiState.value.request
        run { attemptAdmin { gateway.reopen(request.id) }.toAction(RequestAction.Reopened) }
    }

    fun askMarkAvailable() {
        _uiState.update { it.copy(confirmingAvailable = true) }
    }

    fun cancelMarkAvailable() {
        _uiState.update { it.copy(confirmingAvailable = false) }
    }

    /** Après confirmation : marque la demande « disponible » (réversible par [reopen]). */
    fun markAvailable() {
        val request = _uiState.value.request
        run(close = { it.copy(confirmingAvailable = false) }) {
            attemptAdmin { gateway.markAvailable(request.id) }.toAction(RequestAction.MarkedAvailable)
        }
    }

    /** Lance une action ; [block] rend l'état suivant et, si elle a réussi, la demande à jour. */
    private fun run(
        close: (RequestUiState) -> RequestUiState = { it },
        block: suspend () -> Pair<RequestAction, AdminRequest?>,
    ) {
        if (_uiState.value.action == RequestAction.Working) return
        _uiState.update { close(it).copy(action = RequestAction.Working) }
        viewModelScope.launch {
            val (action, updated) = block()
            _uiState.update { state -> state.copy(action = action, request = updated ?: state.request) }
        }
    }

    companion object {
        /** Longueur maximale d'un motif de refus (comme le serveur). */
        const val MAX_REASON = 200

        fun factory(gateway: AdminGateway, request: AdminRequest): ViewModelProvider.Factory = viewModelFactory {
            initializer { RequestViewModel(gateway, request) }
        }
    }
}

private fun Result<AdminRequest>.toAction(success: RequestAction): Pair<RequestAction, AdminRequest?> = fold(
    onSuccess = { updated -> success to updated },
    onFailure = { error -> RequestAction.Failed(error.adminFailure) to null },
)

/** Échec qui peut passer en réessayant (réseau, serveur). */
private fun AdminFailure.isTransient(): Boolean = this == AdminFailure.Network || this == AdminFailure.Server
