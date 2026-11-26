//! Client de la Web API de qBittorrent (v4.1 et plus, v5 compris), en HTTPS.
//!
//! La version est lue à la connexion : la v5 a renommé `pause`/`resume` en
//! `stop`/`start` et les états `paused*` en `stopped*`. Tout ajout porte une
//! catégorie (films ou séries) et l'étiquette `heywatchlist`, plus une
//! étiquette propre à la demande pour la retrouver.
//!
//! Ni l'adresse, ni les identifiants, ni les liens ne sont journalisés.

use tokio::sync::Mutex;

use super::AdminError;
use super::settings::{QbitAuth, QbitSettings};
use crate::api::BaseUrl;

/// Étiquette posée sur tout ce qu'envoie HeyWatchlist.
pub(crate) const APP_TAG: &str = "heywatchlist";
/// Préfixe de l'étiquette qui désigne la demande (`hw-<id>`).
const REQUEST_TAG_PREFIX: &str = "hw-";

/// État d'un téléchargement, ramené à ce que l'écran distingue.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DownloadState {
    Downloading,
    /// En attente de sources (rien ne vient).
    Stalled,
    /// En file d'attente de qBittorrent.
    Queued,
    /// Vérification des fichiers ou récupération des métadonnées.
    Checking,
    /// Mis en pause (v4) ou arrêté (v5) avant la fin.
    Paused,
    /// Terminé (partagé ou arrêté après la fin).
    Completed,
    /// Erreur de qBittorrent (disque, fichiers manquants).
    Error,
    Unknown,
}

/// Un téléchargement de qBittorrent dans une des catégories de l'app.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Download {
    /// Empreinte (40 caractères hexadécimaux), pour les actions.
    pub hash: String,
    pub name: String,
    pub category: String,
    pub state: DownloadState,
    /// De 0 à 1.
    pub progress: f64,
    pub size_bytes: u64,
    /// Octets par seconde.
    pub download_speed: u64,
    /// Secondes restantes estimées, si qBittorrent en donne une.
    pub eta_seconds: Option<u64>,
    /// Demande d'origine, si le torrent a été envoyé depuis une demande.
    pub request_id: Option<String>,
    /// Ajout, en secondes Unix.
    pub added_at: i64,
}

#[derive(serde::Deserialize)]
struct WireTorrent {
    hash: String,
    name: String,
    #[serde(default)]
    category: String,
    #[serde(default)]
    state: String,
    #[serde(default)]
    progress: f64,
    #[serde(default)]
    size: i64,
    #[serde(default)]
    dlspeed: i64,
    #[serde(default)]
    eta: i64,
    #[serde(default)]
    tags: String,
    #[serde(default)]
    added_on: i64,
}

/// Valeur d'`eta` de qBittorrent quand il n'a pas d'estimation (100 jours).
const ETA_INFINITE: i64 = 8_640_000;

fn state(raw: &str) -> DownloadState {
    match raw {
        "downloading" | "forcedDL" | "metaDL" | "forcedMetaDL" => DownloadState::Downloading,
        "stalledDL" => DownloadState::Stalled,
        "queuedDL" => DownloadState::Queued,
        "checkingDL" | "checkingUP" | "checkingResumeData" | "allocating" | "moving" => {
            DownloadState::Checking
        }
        "pausedDL" | "stoppedDL" => DownloadState::Paused,
        "uploading" | "stalledUP" | "forcedUP" | "queuedUP" | "pausedUP" | "stoppedUP" => {
            DownloadState::Completed
        }
        "error" | "missingFiles" => DownloadState::Error,
        _ => DownloadState::Unknown,
    }
}

impl WireTorrent {
    fn into_download(self) -> Option<Download> {
        let hash = self.hash.to_ascii_lowercase();
        if hash.len() != 40 || !hash.bytes().all(|b| b.is_ascii_hexdigit()) {
            return None;
        }
        let request_id = self
            .tags
            .split(',')
            .map(str::trim)
            .find_map(|t| t.strip_prefix(REQUEST_TAG_PREFIX))
            .filter(|id| !id.is_empty())
            .map(str::to_owned);
        Some(Download {
            hash,
            name: self.name,
            category: self.category,
            state: state(&self.state),
            progress: if self.progress.is_finite() {
                self.progress.clamp(0.0, 1.0)
            } else {
                0.0
            },
            size_bytes: u64::try_from(self.size).unwrap_or(0),
            download_speed: u64::try_from(self.dlspeed).unwrap_or(0),
            eta_seconds: u64::try_from(self.eta)
                .ok()
                .filter(|e| *e > 0 && self.eta < ETA_INFINITE),
            request_id,
            added_at: self.added_on,
        })
    }
}

/// Grande version de qBittorrent, lue à la connexion.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Version {
    pub major: u32,
    pub minor: u32,
}

impl Version {
    /// `v4.6.7`, `v5.0.0beta1` → version. `None` si illisible.
    pub(crate) fn parse(raw: &str) -> Option<Self> {
        let raw = raw.trim().trim_start_matches(['v', 'V']);
        let mut parts = raw.split('.');
        let major = parts.next()?.parse().ok()?;
        let minor_digits: String = parts
            .next()
            .unwrap_or("0")
            .chars()
            .take_while(char::is_ascii_digit)
            .collect();
        Some(Self {
            major,
            minor: minor_digits.parse().unwrap_or(0),
        })
    }

    fn supported(self) -> bool {
        self.major > 4 || (self.major == 4 && self.minor >= 1)
    }

    /// Routes « pause » et « reprise » de cette version.
    fn pause_route(self) -> &'static str {
        if self.major >= 5 {
            "/api/v2/torrents/stop"
        } else {
            "/api/v2/torrents/pause"
        }
    }

    fn resume_route(self) -> &'static str {
        if self.major >= 5 {
            "/api/v2/torrents/start"
        } else {
            "/api/v2/torrents/resume"
        }
    }
}

/// Ce qu'on envoie à qBittorrent.
pub(crate) struct NewTorrent<'a> {
    /// Lien magnet ou adresse du `.torrent`.
    pub link: &'a str,
    pub category: &'a str,
    pub request_id: Option<&'a str>,
}

struct Connection {
    version: Version,
    /// Cookie de session (`SID=…`), en connexion par mot de passe.
    cookie: Option<String>,
}

/// Client d'un qBittorrent. La connexion est ouverte au premier appel, puis
/// rouverte une fois si qBittorrent l'a oubliée (403).
pub(crate) struct Qbit {
    http: reqwest::Client,
    base: BaseUrl,
    /// Origine envoyée en `Referer` et `Origin`, exigée par la protection
    /// CSRF de qBittorrent.
    origin: String,
    auth: QbitAuth,
    connection: Mutex<Option<Connection>>,
}

fn transport(_error: reqwest::Error) -> AdminError {
    AdminError::QbitUnreachable
}

impl Qbit {
    pub(crate) fn new(http: reqwest::Client, settings: &QbitSettings) -> Result<Self, AdminError> {
        let base = BaseUrl::parse(&settings.url).map_err(|_| AdminError::InvalidSettings)?;
        let origin = url::Url::parse(settings.url.trim())
            .map(|u| u.origin().ascii_serialization())
            .map_err(|_| AdminError::InvalidSettings)?;
        Ok(Self {
            http,
            base,
            origin,
            auth: settings.auth.clone(),
            connection: Mutex::new(None),
        })
    }

    fn request(
        &self,
        method: reqwest::Method,
        route: &str,
        cookie: Option<&str>,
    ) -> reqwest::RequestBuilder {
        let builder = self
            .http
            .request(method, self.base.join(route))
            .header(reqwest::header::REFERER, format!("{}/", self.origin))
            .header(reqwest::header::ORIGIN, &self.origin);
        let builder = match &self.auth {
            QbitAuth::Bearer { token } => builder.bearer_auth(token.trim()),
            QbitAuth::Password { .. } => builder,
        };
        match cookie {
            Some(cookie) => builder.header(reqwest::header::COOKIE, cookie),
            None => builder,
        }
    }

    /// Connexion : cookie de session (mot de passe) puis lecture de la version.
    async fn connect(&self) -> Result<Connection, AdminError> {
        let cookie = match &self.auth {
            QbitAuth::Password { username, password } => {
                let response = self
                    .request(reqwest::Method::POST, "/api/v2/auth/login", None)
                    .form(&[
                        ("username", username.as_str()),
                        ("password", password.as_str()),
                    ])
                    .send()
                    .await
                    .map_err(transport)?;
                let status = response.status().as_u16();
                let cookie = response
                    .headers()
                    .get_all(reqwest::header::SET_COOKIE)
                    .iter()
                    .filter_map(|v| v.to_str().ok())
                    .filter_map(|v| v.split(';').next())
                    .find(|v| v.trim_start().starts_with("SID=") || v.contains("_SID="))
                    .map(|v| v.trim().to_owned());
                let body = response.text().await.map_err(transport)?;
                match status {
                    403 => return Err(AdminError::QbitBanned),
                    401 => return Err(AdminError::QbitAuthFailed),
                    200..=299 if body.trim() == "Fails." => return Err(AdminError::QbitAuthFailed),
                    200..=299 => {}
                    _ => return Err(AdminError::QbitUnreachable),
                }
                // Sans cookie : l'interface web n'exige pas de connexion
                // (réseau de confiance) ; les appels suivants le diront.
                cookie
            }
            QbitAuth::Bearer { .. } => None,
        };
        let response = self
            .request(
                reqwest::Method::GET,
                "/api/v2/app/version",
                cookie.as_deref(),
            )
            .send()
            .await
            .map_err(transport)?;
        match response.status().as_u16() {
            200..=299 => {}
            401 | 403 => return Err(AdminError::QbitAuthFailed),
            _ => return Err(AdminError::QbitUnreachable),
        }
        let raw = response.text().await.map_err(transport)?;
        let version = Version::parse(&raw).ok_or(AdminError::QbitUnreachable)?;
        if !version.supported() {
            return Err(AdminError::QbitUnsupportedVersion);
        }
        Ok(Connection { version, cookie })
    }

    /// Envoie une requête connectée ; sur un 403 (session oubliée), se
    /// reconnecte une fois. Rend la réponse et la version.
    async fn send<F>(&self, build: F) -> Result<(reqwest::Response, Version), AdminError>
    where
        F: Fn(&Self, Version, Option<&str>) -> reqwest::RequestBuilder,
    {
        let mut guard = self.connection.lock().await;
        for attempt in 0..2 {
            let connection = match guard.take() {
                Some(existing) => existing,
                None => self.connect().await?,
            };
            let response = build(self, connection.version, connection.cookie.as_deref())
                .send()
                .await
                .map_err(transport)?;
            let version = connection.version;
            let forbidden = response.status() == reqwest::StatusCode::FORBIDDEN;
            if forbidden && attempt == 0 && matches!(self.auth, QbitAuth::Password { .. }) {
                continue;
            }
            *guard = Some(connection);
            if forbidden || response.status() == reqwest::StatusCode::UNAUTHORIZED {
                *guard = None;
                return Err(AdminError::QbitAuthFailed);
            }
            return Ok((response, version));
        }
        Err(AdminError::QbitAuthFailed)
    }

    /// Teste la connexion ; rend la version lue (`5.0`).
    pub(crate) async fn test(&self) -> Result<String, AdminError> {
        let connection = self.connect().await?;
        let version = format!("{}.{}", connection.version.major, connection.version.minor);
        *self.connection.lock().await = Some(connection);
        Ok(version)
    }

    /// Ajoute un torrent dans sa catégorie (créée au besoin), avec les étiquettes de l'app.
    pub(crate) async fn add(&self, torrent: &NewTorrent<'_>) -> Result<(), AdminError> {
        // La catégorie existe peut-être déjà (409) : seul un échec de transport compte.
        let category = torrent.category.to_owned();
        let _ = self
            .send(|q, _, cookie| {
                q.request(
                    reqwest::Method::POST,
                    "/api/v2/torrents/createCategory",
                    cookie,
                )
                .form(&[("category", category.as_str()), ("savePath", "")])
            })
            .await?;
        let mut tags = APP_TAG.to_owned();
        if let Some(id) = torrent.request_id {
            tags.push(',');
            tags.push_str(REQUEST_TAG_PREFIX);
            tags.push_str(id);
        }
        let (response, _) = self
            .send(|q, _, cookie| {
                let form = reqwest::multipart::Form::new()
                    .text("urls", torrent.link.to_owned())
                    .text("category", torrent.category.to_owned())
                    .text("tags", tags.clone());
                q.request(reqwest::Method::POST, "/api/v2/torrents/add", cookie)
                    .multipart(form)
            })
            .await?;
        let status = response.status().as_u16();
        let body = response.text().await.map_err(transport)?;
        match status {
            200..=299 if body.trim() == "Fails." => Err(AdminError::QbitRejected),
            200..=299 => Ok(()),
            400 | 409 | 415 => Err(AdminError::QbitRejected),
            _ => Err(AdminError::QbitUnreachable),
        }
    }

    /// Téléchargements des catégories données, les plus récents d'abord.
    pub(crate) async fn downloads(&self, categories: &[&str]) -> Result<Vec<Download>, AdminError> {
        let (response, _) = self
            .send(|q, _, cookie| q.request(reqwest::Method::GET, "/api/v2/torrents/info", cookie))
            .await?;
        if !response.status().is_success() {
            return Err(AdminError::QbitUnreachable);
        }
        let bytes = response.bytes().await.map_err(transport)?;
        // Tolérant : un torrent illisible est ignoré, pas toute la liste.
        let values: Vec<serde_json::Value> =
            serde_json::from_slice(&bytes).map_err(|_| AdminError::Unexpected)?;
        let mut downloads: Vec<Download> = values
            .into_iter()
            .filter_map(|v| serde_json::from_value::<WireTorrent>(v).ok())
            .filter(|t| categories.contains(&t.category.as_str()))
            .filter_map(WireTorrent::into_download)
            .collect();
        downloads.sort_by_key(|d| std::cmp::Reverse(d.added_at));
        Ok(downloads)
    }

    async fn action(
        &self,
        route: fn(Version) -> &'static str,
        fields: &[(&str, &str)],
    ) -> Result<(), AdminError> {
        let (response, _) = self
            .send(|q, version, cookie| {
                q.request(reqwest::Method::POST, route(version), cookie)
                    .form(fields)
            })
            .await?;
        match response.status().as_u16() {
            200..=299 => Ok(()),
            404 => Err(AdminError::TorrentNotFound),
            _ => Err(AdminError::QbitUnreachable),
        }
    }

    pub(crate) async fn pause(&self, hash: &str) -> Result<(), AdminError> {
        self.action(Version::pause_route, &[("hashes", valid_hash(hash)?)])
            .await
    }

    pub(crate) async fn resume(&self, hash: &str) -> Result<(), AdminError> {
        self.action(Version::resume_route, &[("hashes", valid_hash(hash)?)])
            .await
    }

    pub(crate) async fn delete(&self, hash: &str, delete_files: bool) -> Result<(), AdminError> {
        let files = if delete_files { "true" } else { "false" };
        self.action(
            |_| "/api/v2/torrents/delete",
            &[("hashes", valid_hash(hash)?), ("deleteFiles", files)],
        )
        .await
    }
}

/// Une empreinte reçue de l'app : 40 caractères hexadécimaux, rien d'autre
/// (pas de `all` ni de liste séparée par `|`).
fn valid_hash(hash: &str) -> Result<&str, AdminError> {
    if hash.len() == 40 && hash.bytes().all(|b| b.is_ascii_hexdigit()) {
        Ok(hash)
    } else {
        Err(AdminError::TorrentNotFound)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_are_read() {
        assert_eq!(
            Version::parse("v4.6.7"),
            Some(Version { major: 4, minor: 6 })
        );
        assert_eq!(
            Version::parse("v5.0.0beta1"),
            Some(Version { major: 5, minor: 0 })
        );
        assert_eq!(Version::parse("5.1"), Some(Version { major: 5, minor: 1 }));
        assert_eq!(Version::parse("pas une version"), None);
        assert!(!Version { major: 4, minor: 0 }.supported());
        assert!(Version { major: 4, minor: 1 }.supported());
    }

    #[test]
    fn routes_follow_the_version() {
        let v4 = Version { major: 4, minor: 6 };
        let v5 = Version { major: 5, minor: 0 };
        assert_eq!(v4.pause_route(), "/api/v2/torrents/pause");
        assert_eq!(v5.pause_route(), "/api/v2/torrents/stop");
        assert_eq!(v4.resume_route(), "/api/v2/torrents/resume");
        assert_eq!(v5.resume_route(), "/api/v2/torrents/start");
    }

    #[test]
    fn states_of_both_versions() {
        assert_eq!(state("pausedDL"), DownloadState::Paused);
        assert_eq!(state("stoppedDL"), DownloadState::Paused);
        assert_eq!(state("pausedUP"), DownloadState::Completed);
        assert_eq!(state("stoppedUP"), DownloadState::Completed);
        assert_eq!(state("stalledDL"), DownloadState::Stalled);
        assert_eq!(state("metaDL"), DownloadState::Downloading);
        assert_eq!(state("nouveau"), DownloadState::Unknown);
    }

    #[test]
    fn hashes_are_strict() {
        assert!(valid_hash(&"a".repeat(40)).is_ok());
        assert!(valid_hash("all").is_err());
        assert!(valid_hash(&format!("{}|{}", "a".repeat(40), "b".repeat(40))).is_err());
    }
}
