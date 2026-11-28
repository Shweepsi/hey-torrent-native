package app.heywatchlist.admin

import uniffi.heywatchlist_core.AdminRequest
import uniffi.heywatchlist_core.AudioLanguage
import uniffi.heywatchlist_core.AutomationStatus
import uniffi.heywatchlist_core.ClearedRequests
import uniffi.heywatchlist_core.Download
import uniffi.heywatchlist_core.DownloadState
import uniffi.heywatchlist_core.AllowedEmail
import uniffi.heywatchlist_core.Member
import uniffi.heywatchlist_core.MediaType
import uniffi.heywatchlist_core.PlexSettings
import uniffi.heywatchlist_core.QbitSettings
import uniffi.heywatchlist_core.Recommendation
import uniffi.heywatchlist_core.Release
import uniffi.heywatchlist_core.ReleaseInfo
import uniffi.heywatchlist_core.ReleaseSource
import uniffi.heywatchlist_core.RequestFilter
import uniffi.heywatchlist_core.RequestFollower
import uniffi.heywatchlist_core.RequestStatus
import uniffi.heywatchlist_core.Resolution
import uniffi.heywatchlist_core.SearchOutcome
import uniffi.heywatchlist_core.SearchQuery
import uniffi.heywatchlist_core.ServerSettings
import uniffi.heywatchlist_core.SourceFailure
import uniffi.heywatchlist_core.TorznabSource
import uniffi.heywatchlist_core.VideoCodec

/** Espace admin simulé : chaque méthode rend ce que le test a préparé et note ses appels. */
class FakeAdminGateway : AdminGateway {
    val calls = mutableListOf<String>()

    var requestsResult: Result<List<AdminRequest>> = Result.success(emptyList())
    var searchProgress: List<SearchProgress> = emptyList()
    var searchResult: Result<SearchOutcome> = Result.success(SearchOutcome(emptyList(), emptyList()))
    var sendResult: Result<AdminRequest>? = null
    var confirmResult: Result<AdminRequest>? = null
    var refuseResult: Result<AdminRequest>? = null
    var localSettings = ServerSettings(emptyList(), null, "films", "series", emptyList())
    var remoteSettings: Result<ServerSettings> = Result.success(localSettings)
    var saved: ServerSettings? = null
    var downloadsResult: Result<List<Download>> = Result.success(emptyList())

    override suspend fun settings(): ServerSettings = localSettings

    override suspend fun syncSettings(): ServerSettings = remoteSettings.getOrThrow()

    override suspend fun saveSettings(settings: ServerSettings) {
        calls += "save"
        saved = settings
    }

    override suspend fun testSource(source: TorznabSource): SourceFailure? {
        calls += "testSource:${source.id}"
        return SourceFailure.AUTH_FAILED
    }

    override suspend fun testQbittorrent(settings: QbitSettings): String {
        calls += "testQbit"
        return "v5.0.2"
    }

    override suspend fun requests(filter: RequestFilter): List<AdminRequest> {
        calls += "requests:$filter"
        return requestsResult.getOrThrow()
    }

    override suspend fun search(query: SearchQuery, onUpdate: (SearchProgress) -> Unit): SearchOutcome {
        calls += "search:${query.title}:${query.season}"
        searchProgress.forEach(onUpdate)
        return searchResult.getOrThrow()
    }

    var recommendResult: Result<Recommendation> = failure(AdminFailure.AssistantNotConfigured)

    override suspend fun recommend(query: SearchQuery, releases: List<Release>): Recommendation {
        calls += "recommend:${releases.size}"
        return recommendResult.getOrThrow()
    }

    var clearResult: Result<ClearedRequests> = Result.success(ClearedRequests(3u, 2u))

    override suspend fun clearRequests(): ClearedRequests {
        calls += "clear"
        return clearResult.getOrThrow()
    }

    /** Lecture d'état : pas notée dans [calls]. */
    var automationResult: Result<AutomationStatus> = failure(AdminFailure.Server)

    override suspend fun automationStatus(): AutomationStatus = automationResult.getOrThrow()

    var plexResult: Result<Int> = Result.success(2)

    override suspend fun testPlex(settings: PlexSettings): Int {
        calls += "testPlex:${settings.url}"
        return plexResult.getOrThrow()
    }

    var categoriesResult: Result<List<String>> = Result.success(listOf("Animation", "films", "series"))

    override suspend fun qbitCategories(): List<String> {
        calls += "categories"
        return categoriesResult.getOrThrow()
    }

    override suspend fun sendRelease(requestId: String, releaseId: String, category: String): AdminRequest {
        calls += "send:$requestId:$releaseId:$category"
        return checkNotNull(sendResult).getOrThrow()
    }

    override suspend fun confirmRelease(requestId: String, releaseId: String): AdminRequest {
        calls += "confirm:$requestId:$releaseId"
        return checkNotNull(confirmResult).getOrThrow()
    }

    override suspend fun refuse(requestId: String, reason: String?): AdminRequest {
        calls += "refuse:$requestId:$reason"
        return checkNotNull(refuseResult).getOrThrow()
    }

    override suspend fun reopen(requestId: String): AdminRequest {
        calls += "reopen:$requestId"
        return request(requestId, RequestStatus.REQUESTED)
    }

    override suspend fun markAvailable(requestId: String): AdminRequest {
        calls += "available:$requestId"
        return request(requestId, RequestStatus.AVAILABLE)
    }

    override suspend fun downloads(): List<Download> {
        calls += "downloads"
        return downloadsResult.getOrThrow()
    }

    override suspend fun pauseDownload(hash: String) {
        calls += "pause:$hash"
    }

    override suspend fun resumeDownload(hash: String) {
        calls += "resume:$hash"
    }

    override suspend fun deleteDownload(hash: String, deleteFiles: Boolean) {
        calls += "delete:$hash:$deleteFiles"
    }

    var membersResult: List<Member> = emptyList()

    override suspend fun members(): List<Member> {
        calls += "members"
        return membersResult
    }

    override suspend fun decide(memberId: String, granted: Boolean) {
        calls += "decide:$memberId:$granted"
    }

    override suspend fun allowedEmails(): List<AllowedEmail> {
        calls += "emails"
        return emptyList()
    }

    override suspend fun allowEmail(email: String): Int {
        calls += "allow:$email"
        return 1
    }

    override suspend fun removeAllowedEmail(email: String) {
        calls += "remove:$email"
    }
}

fun failure(failure: AdminFailure): Result<Nothing> = Result.failure(AdminFailureException(failure))

fun request(
    id: String = "r1",
    status: RequestStatus = RequestStatus.REQUESTED,
    mediaType: MediaType = MediaType.MOVIE,
    seasons: List<UInt>? = null,
    candidateTitle: String? = null,
    candidateReason: String? = null,
) = AdminRequest(
    id = id,
    mediaType = mediaType,
    tmdbId = 129uL,
    title = "Le Voyage de Chihiro",
    posterUrl = null,
    year = 2001u,
    status = status,
    refusalReason = null,
    releaseTitle = null,
    createdAt = 1_700_000_000L,
    updatedAt = 1_700_000_000L,
    followers = listOf(RequestFollower(displayName = "Léa", seasons = seasons, addedAt = 1_700_000_000L)),
    seasons = seasons,
    candidateTitle = candidateTitle,
    candidateReason = candidateReason,
    candidateFoundAt = candidateTitle?.let { 1_700_000_100L },
    autoCheckedAt = candidateTitle?.let { 1_700_000_100L },
)

fun release(id: String = "x1", title: String = "Le.Voyage.de.Chihiro.2001.MULTi.VFF.1080p.BluRay.x265-GROUPE") = Release(
    id = id,
    title = title,
    sourceName = "Source A",
    indexer = null,
    sizeBytes = 4_500_000_000uL,
    seeders = 42u,
    peers = 3u,
    publishedAt = null,
    info = ReleaseInfo(Resolution.FULL_HD, VideoCodec.HEVC, ReleaseSource.BLU_RAY, AudioLanguage.MULTI_VFF, hdr = false),
    score = 100,
)

fun download(hash: String = "h1", state: DownloadState = DownloadState.DOWNLOADING) = Download(
    hash = hash,
    name = "Le.Voyage.de.Chihiro",
    category = "films",
    state = state,
    progress = 0.5,
    sizeBytes = 4_500_000_000uL,
    downloadSpeed = 1_000_000uL,
    etaSeconds = 600uL,
    requestId = "r1",
    addedAt = 1_700_000_000L,
)
