//! Routes d'administration du Worker (`/v1/admin/…`) : file des demandes et
//! leur traitement, réglages des serveurs chiffrés, invitations.
//!
//! Lecture tolérante : une demande illisible est ignorée, pas toute la file.

use serde::Deserialize;

use super::AdminError;
use crate::account::{Account, AccountError};
use crate::catalog::MediaType;
use crate::watchlist::RequestStatus;

/// État lu du serveur ; `None` pour un état inconnu (la demande est ignorée).
fn parse_status(raw: &str) -> Option<RequestStatus> {
    Some(match raw {
        "requested" => RequestStatus::Requested,
        "in_progress" => RequestStatus::InProgress,
        "available" => RequestStatus::Available,
        "refused" => RequestStatus::Refused,
        _ => return None,
    })
}

fn status_wire(status: RequestStatus) -> &'static str {
    match status {
        RequestStatus::Requested => "requested",
        RequestStatus::InProgress => "in_progress",
        RequestStatus::Available => "available",
        RequestStatus::Refused => "refused",
    }
}

/// Une personne qui attend la demande.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RequestFollower {
    pub display_name: Option<String>,
    /// Saisons voulues ; `None` : film ou série entière.
    pub seasons: Option<Vec<u32>>,
    /// Ajout à sa watchlist, en secondes Unix.
    pub added_at: i64,
}

/// Une demande de la file.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AdminRequest {
    pub id: String,
    pub media_type: MediaType,
    pub tmdb_id: u64,
    pub title: String,
    pub poster_url: Option<String>,
    pub year: Option<u32>,
    pub status: RequestStatus,
    pub refusal_reason: Option<String>,
    /// Nom de la release envoyée à qBittorrent.
    pub release_title: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    pub followers: Vec<RequestFollower>,
    /// Série : saisons à chercher ; `None` si quelqu'un veut toute la série.
    pub seasons: Option<Vec<u32>>,
}

/// Une invitation créée par l'administrateur.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct InvitationInfo {
    pub id: String,
    pub created_at: i64,
    pub expires_at: i64,
    pub used_at: Option<i64>,
    /// Prénom de la personne qui l'a utilisée, s'il est connu.
    pub used_by_name: Option<String>,
    pub revoked_at: Option<i64>,
}

#[derive(Deserialize)]
struct WireFollower {
    display_name: Option<String>,
    seasons: Option<Vec<u32>>,
    added_at: i64,
}

#[derive(Deserialize)]
struct WireRequest {
    id: String,
    media_type: String,
    tmdb_id: u64,
    title: String,
    poster_path: Option<String>,
    year: Option<u32>,
    status: String,
    refusal_reason: Option<String>,
    release_title: Option<String>,
    #[serde(default)]
    created_at: i64,
    #[serde(default)]
    updated_at: i64,
    #[serde(default)]
    followers: Vec<serde_json::Value>,
    seasons: Option<Vec<u32>>,
}

impl WireRequest {
    fn into_request(self) -> Option<AdminRequest> {
        let media_type = match self.media_type.as_str() {
            "movie" => MediaType::Movie,
            "tv" => MediaType::Tv,
            _ => return None,
        };
        Some(AdminRequest {
            id: self.id,
            media_type,
            tmdb_id: self.tmdb_id,
            title: self.title,
            poster_url: crate::tmdb::image_url(self.poster_path.as_deref(), "w342"),
            year: self.year,
            status: parse_status(&self.status)?,
            refusal_reason: self.refusal_reason.filter(|r| !r.trim().is_empty()),
            release_title: self.release_title,
            created_at: self.created_at,
            updated_at: self.updated_at,
            followers: self
                .followers
                .into_iter()
                .filter_map(|f| serde_json::from_value::<WireFollower>(f).ok())
                .map(|f| RequestFollower {
                    display_name: f.display_name.filter(|n| !n.trim().is_empty()),
                    seasons: f.seasons,
                    added_at: f.added_at,
                })
                .collect(),
            seasons: self.seasons,
        })
    }
}

#[derive(Deserialize)]
struct RequestsBody {
    requests: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
struct RequestBody {
    request: WireRequest,
}

#[derive(Deserialize)]
struct SettingsBody {
    settings: Option<String>,
}

#[derive(Deserialize)]
struct WireInvitation {
    id: String,
    created_at: i64,
    expires_at: i64,
    used_at: Option<i64>,
    used_by_name: Option<String>,
    revoked_at: Option<i64>,
}

#[derive(Deserialize)]
struct InvitationsBody {
    invitations: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
struct ErrorBody {
    error: String,
}

fn account_error(error: AccountError) -> AdminError {
    match error {
        AccountError::Network => AdminError::Network,
        AccountError::SignedOut => AdminError::SignedOut,
        AccountError::Forbidden => AdminError::Forbidden,
        AccountError::Server | AccountError::ProviderUnavailable => AdminError::Server,
        AccountError::Internal => AdminError::Internal,
        _ => AdminError::Unexpected,
    }
}

/// Réponse en échec du Worker → erreur typée.
fn failure(status: u16, body: &[u8]) -> AdminError {
    let code = serde_json::from_slice::<ErrorBody>(body)
        .ok()
        .map(|b| b.error);
    match code.as_deref() {
        Some("forbidden") => AdminError::Forbidden,
        Some("unauthorized" | "session_expired") => AdminError::SignedOut,
        Some("not_found") => AdminError::NotFound,
        Some("conflict") => AdminError::Conflict,
        Some("invalid_request") => AdminError::InvalidSettings,
        // Clé de chiffrement absente du Worker.
        Some("provider_unavailable") => AdminError::Server,
        _ if status == 401 => AdminError::SignedOut,
        _ if status >= 500 => AdminError::Server,
        _ => AdminError::Unexpected,
    }
}

async fn call(
    account: &Account,
    method: reqwest::Method,
    target: &str,
    body: Option<&serde_json::Value>,
) -> Result<Vec<u8>, AdminError> {
    let (status, bytes) = account
        .send_json(method, target, body)
        .await
        .map_err(account_error)?;
    if (200..300).contains(&status) {
        Ok(bytes)
    } else {
        Err(failure(status, &bytes))
    }
}

/// Un identifiant reçu de l'app, avant de le mettre dans un chemin : UUID seulement.
fn path_id(id: &str) -> Result<&str, AdminError> {
    let uuid = id.len() == 36 && id.chars().all(|c| c.is_ascii_hexdigit() || c == '-');
    if uuid {
        Ok(id)
    } else {
        Err(AdminError::NotFound)
    }
}

/// Filtre de la file.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RequestFilter {
    /// À traiter : demandées puis en cours.
    Open,
    /// Toutes, les plus récemment changées d'abord.
    All,
    Only {
        status: RequestStatus,
    },
}

pub(crate) async fn requests(
    account: &Account,
    filter: RequestFilter,
) -> Result<Vec<AdminRequest>, AdminError> {
    let target = match filter {
        RequestFilter::Open => "/v1/admin/requests".to_owned(),
        RequestFilter::All => "/v1/admin/requests?status=all".to_owned(),
        RequestFilter::Only { status } => {
            format!("/v1/admin/requests?status={}", status_wire(status))
        }
    };
    let bytes = call(account, reqwest::Method::GET, &target, None).await?;
    let body: RequestsBody = serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
    Ok(body
        .requests
        .into_iter()
        .filter_map(|v| serde_json::from_value::<WireRequest>(v).ok())
        .filter_map(WireRequest::into_request)
        .collect())
}

async fn action(
    account: &Account,
    id: &str,
    verb: &str,
    body: serde_json::Value,
) -> Result<AdminRequest, AdminError> {
    let target = format!("/v1/admin/requests/{}/{verb}", path_id(id)?);
    let bytes = call(account, reqwest::Method::POST, &target, Some(&body)).await?;
    let body: RequestBody = serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
    body.request.into_request().ok_or(AdminError::Unexpected)
}

pub(crate) async fn mark_started(
    account: &Account,
    id: &str,
    release_title: &str,
    torrent_hash: Option<&str>,
) -> Result<AdminRequest, AdminError> {
    let body = serde_json::json!({ "release_title": release_title, "torrent_hash": torrent_hash });
    action(account, id, "start", body).await
}

pub(crate) async fn refuse(
    account: &Account,
    id: &str,
    reason: Option<&str>,
) -> Result<AdminRequest, AdminError> {
    let reason = reason.map(str::trim).filter(|r| !r.is_empty());
    action(
        account,
        id,
        "refuse",
        serde_json::json!({ "reason": reason }),
    )
    .await
}

pub(crate) async fn reopen(account: &Account, id: &str) -> Result<AdminRequest, AdminError> {
    action(account, id, "reopen", serde_json::json!({})).await
}

/// Réglages gardés sur le Worker (texte du cœur), ou `None`.
pub(crate) async fn load_settings(account: &Account) -> Result<Option<String>, AdminError> {
    let bytes = call(account, reqwest::Method::GET, "/v1/admin/settings", None).await?;
    let body: SettingsBody = serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
    Ok(body.settings)
}

pub(crate) async fn save_settings(account: &Account, stored: &str) -> Result<(), AdminError> {
    let body = serde_json::json!({ "settings": stored });
    call(
        account,
        reqwest::Method::PUT,
        "/v1/admin/settings",
        Some(&body),
    )
    .await
    .map(|_| ())
}

pub(crate) async fn invitations(account: &Account) -> Result<Vec<InvitationInfo>, AdminError> {
    let bytes = call(account, reqwest::Method::GET, "/v1/admin/invitations", None).await?;
    let body: InvitationsBody =
        serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
    Ok(body
        .invitations
        .into_iter()
        .filter_map(|v| serde_json::from_value::<WireInvitation>(v).ok())
        .map(|i| InvitationInfo {
            id: i.id,
            created_at: i.created_at,
            expires_at: i.expires_at,
            used_at: i.used_at,
            used_by_name: i.used_by_name.filter(|n| !n.trim().is_empty()),
            revoked_at: i.revoked_at,
        })
        .collect())
}

pub(crate) async fn revoke_invitation(account: &Account, id: &str) -> Result<(), AdminError> {
    let target = format!("/v1/admin/invitations/{}", path_id(id)?);
    call(account, reqwest::Method::DELETE, &target, None)
        .await
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn worker_codes_map_to_admin_errors() {
        assert!(matches!(
            failure(403, br#"{"error":"forbidden"}"#),
            AdminError::Forbidden
        ));
        assert!(matches!(
            failure(409, br#"{"error":"conflict"}"#),
            AdminError::Conflict
        ));
        assert!(matches!(
            failure(404, br#"{"error":"not_found"}"#),
            AdminError::NotFound
        ));
        assert!(matches!(failure(401, b""), AdminError::SignedOut));
        assert!(matches!(failure(502, b"<html>"), AdminError::Server));
        assert!(matches!(failure(418, b""), AdminError::Unexpected));
    }

    #[test]
    fn ids_are_checked_before_entering_a_path() {
        assert!(path_id("11111111-1111-4111-8111-111111111111").is_ok());
        assert!(path_id("../settings").is_err());
        assert!(path_id("").is_err());
    }

    #[test]
    fn unreadable_requests_are_skipped() {
        let wire: WireRequest = serde_json::from_value(serde_json::json!({
            "id": "a", "media_type": "podcast", "tmdb_id": 1, "title": "x", "poster_path": null,
            "year": null, "status": "requested", "refusal_reason": null, "release_title": null, "seasons": null
        }))
        .unwrap();
        assert!(wire.into_request().is_none());
    }
}
