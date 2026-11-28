package app.heywatchlist.admin

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.heywatchlist.R
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.StatusPill
import app.heywatchlist.ui.components.StatusTone
import uniffi.heywatchlist_core.AudioLanguage
import uniffi.heywatchlist_core.DownloadState
import uniffi.heywatchlist_core.ReleaseInfo
import uniffi.heywatchlist_core.ReleaseSource
import uniffi.heywatchlist_core.RequestStatus
import uniffi.heywatchlist_core.Resolution
import uniffi.heywatchlist_core.SourceFailure
import uniffi.heywatchlist_core.VideoCodec
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Libellés des types du cœur : le cœur ne produit aucun texte affiché. */
@StringRes
fun RequestStatus.labelRes(): Int = when (this) {
    RequestStatus.REQUESTED -> R.string.admin_status_requested
    RequestStatus.IN_PROGRESS -> R.string.admin_status_in_progress
    RequestStatus.AVAILABLE -> R.string.admin_status_available
    RequestStatus.REFUSED -> R.string.admin_status_refused
}

/** Teinte de l'état d'une demande, en plus de son libellé et de son icône. */
fun RequestStatus.tone(): StatusTone = when (this) {
    RequestStatus.REQUESTED -> StatusTone.Info
    RequestStatus.IN_PROGRESS -> StatusTone.Progress
    RequestStatus.AVAILABLE -> StatusTone.Success
    RequestStatus.REFUSED -> StatusTone.Danger
}

fun RequestStatus.icon(): ImageVector = when (this) {
    RequestStatus.REQUESTED -> HeyIcons.Clock
    RequestStatus.IN_PROGRESS -> HeyIcons.Download
    RequestStatus.AVAILABLE -> HeyIcons.Check
    RequestStatus.REFUSED -> HeyIcons.Close
}

/** Pastille de l'état d'une demande. */
@Composable
fun RequestStatusPill(status: RequestStatus, modifier: Modifier = Modifier) {
    StatusPill(text = stringResource(status.labelRes()), tone = status.tone(), icon = status.icon(), modifier = modifier)
}

@StringRes
fun SourceFailure.labelRes(): Int = when (this) {
    SourceFailure.TIMEOUT -> R.string.admin_source_timeout
    SourceFailure.UNREACHABLE -> R.string.admin_source_unreachable
    SourceFailure.AUTH_FAILED -> R.string.admin_source_auth_failed
    SourceFailure.RATE_LIMITED -> R.string.admin_source_rate_limited
    SourceFailure.SERVER -> R.string.admin_source_server
    SourceFailure.INVALID_RESPONSE -> R.string.admin_source_invalid_response
    SourceFailure.INVALID_ADDRESS -> R.string.admin_source_invalid_address
}

@StringRes
fun DownloadState.labelRes(): Int = when (this) {
    DownloadState.DOWNLOADING -> R.string.admin_download_downloading
    DownloadState.STALLED -> R.string.admin_download_stalled
    DownloadState.QUEUED -> R.string.admin_download_queued
    DownloadState.CHECKING -> R.string.admin_download_checking
    DownloadState.PAUSED -> R.string.admin_download_paused
    DownloadState.COMPLETED -> R.string.admin_download_completed
    DownloadState.ERROR -> R.string.admin_download_error
    DownloadState.UNKNOWN -> R.string.admin_download_unknown
}

/** Le téléchargement peut être mis en pause (sinon : repris). */
fun DownloadState.isActive(): Boolean = this != DownloadState.PAUSED && this != DownloadState.ERROR

/**
 * Étiquettes techniques d'une release (« 1080p », « x265 », « MULTi VFF »…),
 * en ressources. La langue vient en premier : c'est ce qui compte le plus ici.
 */
fun ReleaseInfo.tags(): List<Int> = listOfNotNull(
    language.tag(),
    resolution.tag(),
    source.tag(),
    codec.tag(),
    R.string.admin_tag_hdr.takeIf { hdr },
)

private fun AudioLanguage.tag(): Int? = when (this) {
    AudioLanguage.MULTI_VFF -> R.string.admin_tag_multi_vff
    AudioLanguage.MULTI -> R.string.admin_tag_multi
    AudioLanguage.VFF -> R.string.admin_tag_vff
    AudioLanguage.VF -> R.string.admin_tag_vf
    AudioLanguage.VFQ -> R.string.admin_tag_vfq
    AudioLanguage.VOSTFR -> R.string.admin_tag_vostfr
    AudioLanguage.UNKNOWN -> null
}

private fun Resolution.tag(): Int? = when (this) {
    Resolution.UHD -> R.string.admin_tag_2160p
    Resolution.FULL_HD -> R.string.admin_tag_1080p
    Resolution.HD -> R.string.admin_tag_720p
    Resolution.SD -> R.string.admin_tag_sd
    Resolution.UNKNOWN -> null
}

private fun ReleaseSource.tag(): Int? = when (this) {
    ReleaseSource.REMUX -> R.string.admin_tag_remux
    ReleaseSource.BLU_RAY -> R.string.admin_tag_bluray
    ReleaseSource.WEB_DL -> R.string.admin_tag_web_dl
    ReleaseSource.WEB_RIP -> R.string.admin_tag_webrip
    ReleaseSource.WEB -> R.string.admin_tag_web
    ReleaseSource.HDTV -> R.string.admin_tag_hdtv
    ReleaseSource.DVD -> R.string.admin_tag_dvd
    ReleaseSource.CAM -> R.string.admin_tag_cam
    ReleaseSource.UNKNOWN -> null
}

private fun VideoCodec.tag(): Int? = when (this) {
    VideoCodec.HEVC -> R.string.admin_tag_x265
    VideoCodec.AV1 -> R.string.admin_tag_av1
    VideoCodec.H264 -> R.string.admin_tag_x264
    VideoCodec.XVID -> R.string.admin_tag_xvid
    VideoCodec.UNKNOWN -> null
}

/** Taille lisible, en unités binaires à la française (« 4,2 Go »). */
@Composable
fun bytesLabel(bytes: Long): String {
    val units = listOf(R.string.admin_size_b, R.string.admin_size_kb, R.string.admin_size_mb, R.string.admin_size_gb, R.string.admin_size_tb)
    var value = bytes.toDouble().coerceAtLeast(0.0)
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val number = String.format(Locale.FRANCE, if (unit == 0 || value >= 100) "%.0f" else "%.1f", value)
    return stringResource(units[unit], number)
}

/** Date et heure courtes (« 26 nov. 2026, 14:05 ») d'un instant en secondes Unix. */
fun formatDateTime(seconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.FRANCE).format(Date(seconds * 1000))

/** Date courte (« 26 nov. 2026 ») d'un instant en secondes Unix. */
fun formatDate(seconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE).format(Date(seconds * 1000))
