package app.heywatchlist.core

import androidx.annotation.StringRes
import app.heywatchlist.R

/** État d'une demande, partagé par toutes les personnes qui l'attendent. */
enum class RequestState { Requested, InProgress, Available, Refused }

/** Un titre de la watchlist et l'état de sa demande. */
data class WatchlistEntry(
    val kind: MediaKind,
    val id: Long,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    /** Série : saisons demandées ; `null` : film ou série entière. */
    val seasons: List<Int>?,
    /** Date d'ajout, en secondes Unix. */
    val addedAt: Long,
    val state: RequestState,
    /** Motif d'un refus, écrit par l'administrateur. */
    val refusalReason: String?,
    /** Changement fait sur ce téléphone, pas encore reçu par le serveur. */
    val pending: Boolean,
) {
    val key: String get() = "${kind.name}-$id"

    /** Le titre tel que la fiche l'attend ; elle complète le reste en se chargeant. */
    fun toTitle() = Title(
        id = id,
        kind = kind,
        title = title,
        year = year,
        releaseDate = null,
        overview = "",
        posterUrl = posterUrl,
        backdropUrl = null,
        rating = 0.0,
        voteCount = 0,
        genreIds = emptyList(),
    )
}

/** La watchlist après une synchronisation. [offline] : le serveur n'a pas répondu, les changements attendent. */
data class WatchlistSnapshot(val entries: List<WatchlistEntry>, val offline: Boolean)

/** Cause d'un échec d'une opération sur la watchlist. Le réseau n'en est jamais une. */
enum class WatchlistFailure(@param:StringRes val messageRes: Int) {
    SignedOut(R.string.catalog_error_signed_out),

    /** Le serveur refuse ce titre ou ces saisons. */
    Rejected(R.string.watchlist_error_rejected),
    NotConfigured(R.string.catalog_error_not_configured),
    CoreUnavailable(R.string.catalog_error_core_unavailable),
    Unknown(R.string.watchlist_error_unknown),
}

class WatchlistFailureException(val failure: WatchlistFailure, cause: Throwable? = null) :
    Exception(failure.name, cause)

/**
 * Frontière entre l'interface et la watchlist du cœur Rust. Chaque méthode
 * s'exécute hors du fil principal et lève [WatchlistFailureException].
 */
interface WatchlistGateway {
    /** Watchlist connue sur le téléphone, sans réseau. */
    suspend fun entries(): List<WatchlistEntry>

    /** Envoie les changements en attente et relit la watchlist du serveur. */
    suspend fun refresh(): WatchlistSnapshot

    /** [seasons] : `null` pour un film ou une série entière. */
    suspend fun add(title: Title, seasons: List<Int>?): WatchlistSnapshot

    suspend fun remove(kind: MediaKind, id: Long): WatchlistSnapshot
}
