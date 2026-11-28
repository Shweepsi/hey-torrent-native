//! Routes d'administration du Worker (`/v1/admin/…`) : file des demandes et
//! leur traitement, réglages des serveurs chiffrés, accès des comptes.
//!
//! Lecture tolérante : une demande illisible est ignorée, pas toute la file.

use std::time::Duration;

use serde::Deserialize;

use super::AdminError;
use super::search::{Release, SearchQuery, SourceFailure};
use super::settings::PlexSettings;
use crate::account::{AccessStatus, Account, AccountError};
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
    /// Release jugée convenable par la recherche automatique (Claude), à
    /// chercher puis envoyer depuis l'app.
    #[uniffi(default = None)]
    pub candidate_title: Option<String>,
    /// Pourquoi Claude l'a choisie, en français.
    #[uniffi(default = None)]
    pub candidate_reason: Option<String>,
    #[uniffi(default = None)]
    pub candidate_found_at: Option<i64>,
    /// Dernière recherche automatique, en secondes Unix.
    #[uniffi(default = None)]
    pub auto_checked_at: Option<i64>,
}

/// Un compte, vu par l'administrateur.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Member {
    pub id: String,
    pub display_name: Option<String>,
    /// Adresse vérifiée par Apple ou Google, pour reconnaître la personne.
    pub email: Option<String>,
    pub access: AccessStatus,
    pub is_admin: bool,
    /// Création du compte (la demande d'accès), en secondes Unix.
    pub created_at: i64,
    /// Dernière décision de l'administrateur.
    pub status_changed_at: Option<i64>,
}

/// Adresse acceptée d'avance : son compte sera actif dès sa création.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AllowedEmail {
    pub email: String,
    pub created_at: i64,
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
    #[serde(default)]
    candidate_title: Option<String>,
    #[serde(default)]
    candidate_reason: Option<String>,
    #[serde(default)]
    candidate_found_at: Option<i64>,
    #[serde(default)]
    auto_checked_at: Option<i64>,
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
            candidate_title: self.candidate_title.filter(|t| !t.trim().is_empty()),
            candidate_reason: self.candidate_reason.filter(|r| !r.trim().is_empty()),
            candidate_found_at: self.candidate_found_at,
            auto_checked_at: self.auto_checked_at,
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
struct WireMember {
    id: String,
    role: String,
    display_name: Option<String>,
    status: String,
    email: Option<String>,
    #[serde(default)]
    created_at: i64,
    status_changed_at: Option<i64>,
}

impl WireMember {
    fn into_member(self) -> Option<Member> {
        let access = match self.status.as_str() {
            "active" => AccessStatus::Active,
            "pending" => AccessStatus::Pending,
            "refused" => AccessStatus::Refused,
            _ => return None,
        };
        Some(Member {
            id: self.id,
            display_name: self.display_name.filter(|n| !n.trim().is_empty()),
            email: self.email.filter(|e| !e.trim().is_empty()),
            access,
            is_admin: self.role == "admin",
            created_at: self.created_at,
            status_changed_at: self.status_changed_at,
        })
    }
}

#[derive(Deserialize)]
struct MembersBody {
    members: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
struct WireAllowedEmail {
    email: String,
    #[serde(default)]
    created_at: i64,
}

#[derive(Deserialize)]
struct AllowedEmailsBody {
    emails: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
struct AllowedBody {
    #[serde(default)]
    approved: u32,
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
        Some("assistant_not_configured") => AdminError::AssistantNotConfigured,
        Some("assistant_auth_failed") => AdminError::AssistantAuthFailed,
        Some("assistant_unavailable") => AdminError::AssistantUnavailable,
        Some("plex_unreachable") => AdminError::PlexUnreachable,
        Some("plex_auth_failed") => AdminError::PlexAuthFailed,
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
    call_within(account, method, target, body, None).await
}

/// Comme [`call`], avec un délai propre à la route (`None` : le délai commun).
async fn call_within(
    account: &Account,
    method: reqwest::Method,
    target: &str,
    body: Option<&serde_json::Value>,
    timeout: Option<Duration>,
) -> Result<Vec<u8>, AdminError> {
    let (status, bytes) = account
        .send_json(method, target, body, timeout)
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

pub(crate) async fn mark_available(
    account: &Account,
    id: &str,
) -> Result<AdminRequest, AdminError> {
    action(account, id, "available", serde_json::json!({})).await
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

/// Comptes : ceux en attente d'abord.
pub(crate) async fn members(account: &Account) -> Result<Vec<Member>, AdminError> {
    let bytes = call(account, reqwest::Method::GET, "/v1/admin/members", None).await?;
    let body: MembersBody = serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
    Ok(body
        .members
        .into_iter()
        .filter_map(|v| serde_json::from_value::<WireMember>(v).ok())
        .filter_map(WireMember::into_member)
        .collect())
}

/// Accepte (`granted`) ou refuse l'accès d'un compte.
pub(crate) async fn decide(account: &Account, id: &str, granted: bool) -> Result<(), AdminError> {
    let verb = if granted { "approve" } else { "refuse" };
    let target = format!("/v1/admin/members/{}/{verb}", path_id(id)?);
    call(
        account,
        reqwest::Method::POST,
        &target,
        Some(&serde_json::json!({})),
    )
    .await
    .map(|_| ())
}

pub(crate) async fn allowed_emails(account: &Account) -> Result<Vec<AllowedEmail>, AdminError> {
    let bytes = call(
        account,
        reqwest::Method::GET,
        "/v1/admin/allowed-emails",
        None,
    )
    .await?;
    let body: AllowedEmailsBody =
        serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
    Ok(body
        .emails
        .into_iter()
        .filter_map(|v| serde_json::from_value::<WireAllowedEmail>(v).ok())
        .map(|e| AllowedEmail {
            email: e.email,
            created_at: e.created_at,
        })
        .collect())
}

/// Accepte d'avance une adresse ; rend le nombre de comptes en attente acceptés du même coup.
pub(crate) async fn allow_email(account: &Account, email: &str) -> Result<u32, AdminError> {
    let email = email.trim();
    if email.is_empty() || !email.contains('@') {
        return Err(AdminError::InvalidSettings);
    }
    let body = serde_json::json!({ "email": email });
    let bytes = call(
        account,
        reqwest::Method::POST,
        "/v1/admin/allowed-emails",
        Some(&body),
    )
    .await?;
    // Réponse illisible : l'adresse est quand même enregistrée.
    Ok(serde_json::from_slice::<AllowedBody>(&bytes)
        .map(|b| b.approved)
        .unwrap_or(0))
}

pub(crate) async fn remove_allowed_email(account: &Account, email: &str) -> Result<(), AdminError> {
    let body = serde_json::json!({ "email": email.trim() });
    call(
        account,
        reqwest::Method::DELETE,
        "/v1/admin/allowed-emails",
        Some(&body),
    )
    .await
    .map(|_| ())
}

/// Ce qu'a supprimé « Vider les demandes ».
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ClearedRequests {
    pub requests: u32,
    /// Personnes dont la watchlist a été vidée.
    pub people: u32,
}

#[derive(Deserialize)]
struct WireCleared {
    #[serde(default)]
    requests: u32,
    #[serde(default)]
    people: u32,
}

/// Vide la file : demandes, watchlists qui s'y rattachent, traces de notification.
pub(crate) async fn clear_requests(account: &Account) -> Result<ClearedRequests, AdminError> {
    let body = serde_json::json!({ "confirm": true });
    let bytes = call(
        account,
        reqwest::Method::POST,
        "/v1/admin/requests/clear",
        Some(&body),
    )
    .await?;
    // Réponse illisible : la file est quand même vidée.
    let wire = serde_json::from_slice::<WireCleared>(&bytes).unwrap_or(WireCleared {
        requests: 0,
        people: 0,
    });
    Ok(ClearedRequests {
        requests: wire.requests,
        people: wire.people,
    })
}

/// Avis de Claude sur une recherche.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Recommendation {
    /// Release choisie (identifiant d'une des releases données), ou `None`
    /// si aucune ne convient.
    pub release_id: Option<String>,
    /// Une phrase en français, à montrer telle quelle.
    pub reason: String,
}

/// Au plus autant de releases partent vers Claude : les mieux notées.
const MAX_RECOMMEND: usize = 40;
/// Délai de la recommandation : le Worker laisse à Claude deux essais de 40 s
/// (`backend/src/claude.ts`), bien plus que le délai commun des autres routes.
const RECOMMEND_TIMEOUT: Duration = Duration::from_secs(90);

#[derive(Deserialize)]
struct WireRecommendation {
    release_id: Option<String>,
    #[serde(default)]
    reason: String,
}

/// Demande à Claude (par le Worker, avec la clé et les règles de
/// l'administrateur) la release la plus adaptée parmi `releases`, déjà triées.
/// Aucun lien ne part : seuls noms, tailles et sources.
pub(crate) async fn recommend(
    account: &Account,
    query: &SearchQuery,
    releases: &[Release],
) -> Result<Recommendation, AdminError> {
    let releases: Vec<serde_json::Value> = releases
        .iter()
        .take(MAX_RECOMMEND)
        .map(|r| {
            serde_json::json!({
                "id": r.id,
                "title": r.title,
                "size_bytes": r.size_bytes,
                "seeders": r.seeders,
                "indexer": r.indexer,
            })
        })
        .collect();
    let body = serde_json::json!({
        "media_type": match query.media_type { MediaType::Movie => "movie", MediaType::Tv => "tv" },
        "title": query.title,
        "original_title": query.original_title,
        "year": query.year,
        "season": query.season,
        "releases": releases,
    });
    let bytes = call_within(
        account,
        reqwest::Method::POST,
        "/v1/admin/recommend",
        Some(&body),
        Some(RECOMMEND_TIMEOUT),
    )
    .await?;
    let wire: WireRecommendation =
        serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
    let release_id = wire.release_id.filter(|id| !id.is_empty());
    // Un identifiant inconnu ne doit rien mettre en avant.
    if release_id
        .as_ref()
        .is_some_and(|id| !releases.iter().any(|r| r["id"] == id.as_str()))
    {
        return Err(AdminError::Unexpected);
    }
    Ok(Recommendation {
        release_id,
        reason: wire.reason.trim().to_owned(),
    })
}

/// État de Claude au dernier passage de la recherche automatique.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AssistantStatus {
    Ok,
    /// Clé refusée : la recherche automatique est arrêtée.
    AuthFailed,
    /// Claude n'a pas répondu ; nouvel essai au passage suivant.
    Unavailable,
}

/// État de la dernière lecture de la bibliothèque Plex par le Worker.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PlexStatus {
    Ok,
    Unreachable,
    AuthFailed,
    InvalidResponse,
}

/// Une source vue depuis le Worker (Cloudflare) au dernier passage.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AutoSourceReport {
    /// Identifiant de la source dans les réglages.
    pub source_id: String,
    /// `None` : la source a répondu. Un échec ici mais pas depuis le
    /// téléphone : la source refuse sans doute les adresses de Cloudflare.
    pub failure: Option<SourceFailure>,
}

/// Où en sont les tâches automatiques du Worker.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AutomationStatus {
    /// Dernier passage de la recherche automatique, en secondes Unix.
    pub searched_at: Option<i64>,
    pub sources: Vec<AutoSourceReport>,
    pub assistant: Option<AssistantStatus>,
    /// Dernière lecture de Plex, en secondes Unix.
    pub plex_synced_at: Option<i64>,
    pub plex: Option<PlexStatus>,
    /// Films et séries reconnus dans la bibliothèque.
    pub plex_items: Option<u32>,
}

fn parse_source_failure(raw: &str) -> Option<SourceFailure> {
    Some(match raw {
        "timeout" => SourceFailure::Timeout,
        "unreachable" => SourceFailure::Unreachable,
        "auth_failed" => SourceFailure::AuthFailed,
        "rate_limited" => SourceFailure::RateLimited,
        "server" => SourceFailure::Server,
        "invalid_response" => SourceFailure::InvalidResponse,
        "invalid_address" => SourceFailure::InvalidAddress,
        _ => return None,
    })
}

#[derive(Deserialize)]
struct WireAutoSource {
    id: String,
    failure: Option<String>,
}

#[derive(Deserialize)]
struct WireAutomation {
    searched_at: Option<i64>,
    #[serde(default)]
    sources: Vec<serde_json::Value>,
    assistant_status: Option<String>,
    plex_synced_at: Option<i64>,
    plex_status: Option<String>,
    plex_items: Option<u32>,
}

pub(crate) async fn automation_status(account: &Account) -> Result<AutomationStatus, AdminError> {
    let bytes = call(account, reqwest::Method::GET, "/v1/admin/automation", None).await?;
    let wire: WireAutomation =
        serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
    Ok(AutomationStatus {
        searched_at: wire.searched_at,
        // Tolérant : une entrée illisible est ignorée ; un échec inconnu
        // compte comme réponse illisible.
        sources: wire
            .sources
            .into_iter()
            .filter_map(|v| serde_json::from_value::<WireAutoSource>(v).ok())
            .map(|s| AutoSourceReport {
                source_id: s.id,
                failure: s
                    .failure
                    .map(|f| parse_source_failure(&f).unwrap_or(SourceFailure::InvalidResponse)),
            })
            .collect(),
        assistant: wire.assistant_status.as_deref().and_then(|s| match s {
            "ok" => Some(AssistantStatus::Ok),
            "auth_failed" => Some(AssistantStatus::AuthFailed),
            "unavailable" => Some(AssistantStatus::Unavailable),
            _ => None,
        }),
        plex_synced_at: wire.plex_synced_at,
        plex: wire.plex_status.as_deref().and_then(|s| match s {
            "ok" => Some(PlexStatus::Ok),
            "unreachable" => Some(PlexStatus::Unreachable),
            "auth_failed" => Some(PlexStatus::AuthFailed),
            "invalid_response" => Some(PlexStatus::InvalidResponse),
            _ => None,
        }),
        plex_items: wire.plex_items,
    })
}

#[derive(Deserialize)]
struct PlexTestBody {
    sections: u32,
}

/// Le Worker joint-il ce Plex ? Rend le nombre de bibliothèques films et séries.
pub(crate) async fn test_plex(account: &Account, plex: &PlexSettings) -> Result<u32, AdminError> {
    let body = serde_json::json!({ "url": plex.url.trim(), "token": plex.token.trim() });
    let bytes = call(
        account,
        reqwest::Method::POST,
        "/v1/admin/plex/test",
        Some(&body),
    )
    .await?;
    let body: PlexTestBody = serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
    Ok(body.sections)
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
    fn unreadable_members_are_skipped() {
        let wire: WireMember = serde_json::from_value(serde_json::json!({
            "id": "a", "role": "member", "display_name": " ", "status": "banni", "email": null,
            "created_at": 1, "status_changed_at": null
        }))
        .unwrap();
        assert!(wire.into_member().is_none());
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
