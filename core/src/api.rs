//! Client HTTP de l'API du Worker (`backend/`) : requêtes, réponses et
//! traduction des codes d'erreur du serveur en [`AccountError`].

use std::time::Duration;

use serde::Deserialize;
use serde::de::DeserializeOwned;

use crate::account::{AccessStatus, AccountError, UserProfile, UserRole};

/// Délai maximal d'une requête complète.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const USER_AGENT: &str = concat!("HeyWatchlist/", env!("CARGO_PKG_VERSION"));

/// Client HTTPS commun. Sur Android, les racines de confiance de Mozilla sont
/// embarquées : le vérificateur du système (`rustls-platform-verifier`)
/// demanderait une initialisation JNI par l'app, sans quoi chaque requête
/// HTTPS échouerait.
pub(crate) fn http_client() -> Result<reqwest::Client, AccountError> {
    let builder = reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(REQUEST_TIMEOUT)
        // Jamais de redirection : une API ne redirige pas, et un jeton ne doit
        // pas partir ailleurs.
        .redirect(reqwest::redirect::Policy::none());
    #[cfg(target_os = "android")]
    let builder = builder.tls_certs_only(embedded_roots());
    builder.build().map_err(|_| AccountError::Internal)
}

#[cfg(any(target_os = "android", test))]
fn embedded_roots() -> Vec<reqwest::Certificate> {
    webpki_root_certs::TLS_SERVER_ROOT_CERTS
        .iter()
        .filter_map(|der| reqwest::Certificate::from_der(der).ok())
        .collect()
}

/// Adresse de base de l'API, validée : HTTPS obligatoire, sauf sur la boucle
/// locale (tests et développement).
#[derive(Debug, Clone)]
pub(crate) struct BaseUrl(url::Url);

impl BaseUrl {
    pub(crate) fn parse(raw: &str) -> Result<Self, AccountError> {
        let url = url::Url::parse(raw.trim()).map_err(|_| AccountError::InvalidConfiguration)?;
        let loopback = matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]"));
        let secure = url.scheme() == "https" || (url.scheme() == "http" && loopback);
        if !secure
            || url.host_str().is_none()
            || url.query().is_some()
            || !url.username().is_empty()
        {
            return Err(AccountError::InvalidConfiguration);
        }
        Ok(Self(url))
    }

    /// `chemin` est absolu (`/v1/...`) ; un éventuel préfixe de la base est gardé.
    pub(crate) fn join(&self, path: &str) -> String {
        let base = self.0.as_str().trim_end_matches('/');
        format!("{base}{path}")
    }
}

#[derive(Deserialize)]
struct ErrorBody {
    error: String,
}

/// Code d'erreur du serveur (ou statut sans corps lisible) → erreur typée.
fn server_error(status: u16, code: Option<&str>) -> AccountError {
    match code {
        Some("invalid_token") => AccountError::InvalidIdentity,
        Some("unauthorized" | "session_expired") => AccountError::SignedOut,
        Some("provider_unavailable") => AccountError::ProviderUnavailable,
        Some("forbidden" | "access_pending" | "access_refused") => AccountError::Forbidden,
        _ if status == 401 => AccountError::SignedOut,
        _ if status >= 500 => AccountError::Server,
        _ => AccountError::Unexpected,
    }
}

/// Échec d'une lecture au Worker, avant traduction par le module appelant.
///
/// `AccountError` a des `switch` exhaustifs côté apps : les lectures du
/// catalogue passent par cette énumération interne, qui distingue en plus
/// `NotFound`, plutôt que d'ajouter une variante publique.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ApiFailure {
    /// Pas de réseau, serveur injoignable ou délai dépassé.
    Network,
    /// Session absente, expirée ou révoquée (après le renouvellement automatique).
    SignedOut,
    /// Le serveur ne connaît pas la ressource (`not_found`).
    NotFound,
    /// Service amont indisponible (`provider_unavailable`).
    Unavailable,
    /// Erreur du serveur (5xx).
    Server,
    /// Requête refusée telle quelle (`invalid_request`, `forbidden`) : la
    /// renvoyer ne changerait rien.
    Rejected,
    /// Réponse imprévue (format, statut).
    Unexpected,
    /// Défaillance interne.
    Internal,
}

impl From<AccountError> for ApiFailure {
    /// Échec survenu pendant l'authentification de la requête.
    fn from(error: AccountError) -> Self {
        match error {
            AccountError::Network => Self::Network,
            AccountError::SignedOut => Self::SignedOut,
            AccountError::ProviderUnavailable => Self::Unavailable,
            AccountError::Server => Self::Server,
            AccountError::Internal => Self::Internal,
            _ => Self::Unexpected,
        }
    }
}

/// Code d'erreur du corps d'une réponse en échec, s'il est lisible.
fn error_code(bytes: &[u8]) -> Option<String> {
    serde_json::from_slice::<ErrorBody>(bytes)
        .ok()
        .map(|b| b.error)
}

/// Code d'erreur du serveur (ou statut sans corps lisible) → échec de lecture.
fn read_failure(status: u16, code: Option<&str>) -> ApiFailure {
    match code {
        Some("unauthorized" | "session_expired") => ApiFailure::SignedOut,
        Some("not_found") => ApiFailure::NotFound,
        Some("provider_unavailable") => ApiFailure::Unavailable,
        // Accès en attente ou retiré : l'app relit le profil et change d'écran.
        Some("invalid_request" | "forbidden" | "access_pending" | "access_refused") => {
            ApiFailure::Rejected
        }
        _ if status == 401 => ApiFailure::SignedOut,
        _ if status >= 500 => ApiFailure::Server,
        _ => ApiFailure::Unexpected,
    }
}

/// Échec de transport (pas de réseau, délai dépassé…).
pub(crate) fn transport_error(_error: reqwest::Error) -> AccountError {
    // Le détail n'est pas gardé : il peut contenir l'adresse du serveur.
    AccountError::Network
}

/// Lit une réponse : corps JSON si succès, erreur typée sinon.
pub(crate) async fn read<T: DeserializeOwned>(
    response: reqwest::Response,
) -> Result<T, AccountError> {
    let status = response.status().as_u16();
    let bytes = response.bytes().await.map_err(transport_error)?;
    if (200..300).contains(&status) {
        return serde_json::from_slice(&bytes).map_err(|_| AccountError::Unexpected);
    }
    Err(server_error(status, error_code(&bytes).as_deref()))
}

/// Comme [`read`], pour une réponse sans corps attendu.
pub(crate) async fn read_empty(response: reqwest::Response) -> Result<(), AccountError> {
    let status = response.status().as_u16();
    if (200..300).contains(&status) {
        return Ok(());
    }
    let bytes = response.bytes().await.map_err(transport_error)?;
    Err(server_error(status, error_code(&bytes).as_deref()))
}

/// Comme [`read`], mais rend le corps brut (non décodé) en cas de succès.
pub(crate) async fn read_bytes(response: reqwest::Response) -> Result<Vec<u8>, ApiFailure> {
    let status = response.status().as_u16();
    let bytes = response
        .bytes()
        .await
        .map_err(|error| ApiFailure::from(transport_error(error)))?;
    if (200..300).contains(&status) {
        return Ok(bytes.to_vec());
    }
    Err(read_failure(status, error_code(&bytes).as_deref()))
}

/// Compte tel que l'API le renvoie.
#[derive(Debug, Clone, Deserialize, serde::Serialize, PartialEq, Eq)]
pub(crate) struct WireUser {
    pub id: String,
    pub role: String,
    pub display_name: Option<String>,
    /// Fournisseurs rattachés (`apple`, `google`) ; absent d'un vieux serveur.
    #[serde(default)]
    pub providers: Vec<String>,
    /// `active`, `pending` ou `refused` ; absent d'un serveur d'avant la
    /// validation des comptes, où tout compte était actif.
    #[serde(default)]
    pub status: Option<String>,
}

impl WireUser {
    pub(crate) fn profile(&self) -> UserProfile {
        UserProfile {
            id: self.id.clone(),
            display_name: self
                .display_name
                .clone()
                .filter(|name| !name.trim().is_empty()),
            has_apple: self.providers.iter().any(|p| p == "apple"),
            access: match self.status.as_deref() {
                None | Some("active") => AccessStatus::Active,
                Some("refused") => AccessStatus::Refused,
                // État inconnu (version future du serveur) : pas d'accès.
                Some(_) => AccessStatus::Pending,
            },
            // Rôle inconnu (version future du serveur) : le moins de droits.
            role: if self.role == "admin" {
                UserRole::Admin
            } else {
                UserRole::Member
            },
        }
    }
}

#[derive(Deserialize)]
pub(crate) struct TokenPair {
    pub access_token: String,
    pub access_expires_in: u64,
    pub refresh_token: String,
}

#[derive(Deserialize)]
pub(crate) struct SignInResponse {
    #[serde(flatten)]
    pub tokens: TokenPair,
    pub user: WireUser,
}

#[derive(Deserialize)]
pub(crate) struct MeResponse {
    pub user: WireUser,
}

#[derive(Deserialize)]
pub(crate) struct ConfigResponse {
    pub google_web_client_id: Option<String>,
    pub google_ios_client_id: Option<String>,
}

/// `GET /v1/posters` : chemins TMDB des affiches en tendance. Gardés bruts pour
/// qu'une entrée illisible soit ignorée, pas fatale à la liste.
#[derive(Deserialize)]
pub(crate) struct PostersResponse {
    #[serde(default)]
    pub posters: Vec<serde_json::Value>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn embedded_roots_are_all_readable() {
        let roots = embedded_roots();
        assert!(roots.len() > 100);
        assert_eq!(roots.len(), webpki_root_certs::TLS_SERVER_ROOT_CERTS.len());
        reqwest::Client::builder()
            .tls_certs_only(roots)
            .build()
            .expect("client construit avec les seules racines embarquées");
    }

    #[test]
    fn base_url_requires_https_outside_loopback() {
        assert!(BaseUrl::parse("https://api.example.com").is_ok());
        assert!(BaseUrl::parse("http://127.0.0.1:8787").is_ok());
        assert!(BaseUrl::parse("http://api.example.com").is_err());
        assert!(BaseUrl::parse("ftp://api.example.com").is_err());
        assert!(BaseUrl::parse("https://user@api.example.com").is_err());
        assert!(BaseUrl::parse("https://api.example.com/?k=v").is_err());
        assert!(BaseUrl::parse("pas une url").is_err());
        assert!(BaseUrl::parse("").is_err());
    }

    #[test]
    fn base_url_joins_paths_without_double_slash() {
        let base = BaseUrl::parse("https://api.example.com/").unwrap();
        assert_eq!(base.join("/v1/me"), "https://api.example.com/v1/me");
        let prefixed = BaseUrl::parse("https://example.com/api").unwrap();
        assert_eq!(prefixed.join("/v1/me"), "https://example.com/api/v1/me");
    }

    #[test]
    fn server_codes_map_to_typed_errors() {
        assert!(matches!(
            server_error(403, Some("access_pending")),
            AccountError::Forbidden
        ));
        assert!(matches!(
            server_error(401, Some("invalid_token")),
            AccountError::InvalidIdentity
        ));
        assert!(matches!(
            server_error(401, Some("session_expired")),
            AccountError::SignedOut
        ));
        assert!(matches!(server_error(401, None), AccountError::SignedOut));
        assert!(matches!(
            server_error(503, Some("provider_unavailable")),
            AccountError::ProviderUnavailable
        ));
        assert!(matches!(server_error(502, None), AccountError::Server));
        assert!(matches!(
            server_error(418, Some("inconnu")),
            AccountError::Unexpected
        ));
    }

    #[test]
    fn read_failures_distinguish_not_found() {
        assert_eq!(read_failure(404, Some("not_found")), ApiFailure::NotFound);
        assert_eq!(
            read_failure(401, Some("unauthorized")),
            ApiFailure::SignedOut
        );
        assert_eq!(
            read_failure(401, Some("session_expired")),
            ApiFailure::SignedOut
        );
        assert_eq!(read_failure(401, None), ApiFailure::SignedOut);
        assert_eq!(
            read_failure(503, Some("provider_unavailable")),
            ApiFailure::Unavailable
        );
        assert_eq!(read_failure(500, Some("internal")), ApiFailure::Server);
        assert_eq!(read_failure(502, None), ApiFailure::Server);
        assert_eq!(
            read_failure(400, Some("invalid_request")),
            ApiFailure::Rejected
        );
        assert_eq!(read_failure(403, Some("forbidden")), ApiFailure::Rejected);
        assert_eq!(
            read_failure(403, Some("access_refused")),
            ApiFailure::Rejected
        );
        assert_eq!(read_failure(400, None), ApiFailure::Unexpected);
        // Un 404 sans code reconnu n'est pas un « introuvable » du Worker.
        assert_eq!(read_failure(404, None), ApiFailure::Unexpected);
        assert_eq!(read_failure(418, None), ApiFailure::Unexpected);
    }

    #[test]
    fn authentication_failures_keep_their_meaning() {
        assert_eq!(ApiFailure::from(AccountError::Network), ApiFailure::Network);
        assert_eq!(
            ApiFailure::from(AccountError::SignedOut),
            ApiFailure::SignedOut
        );
        assert_eq!(
            ApiFailure::from(AccountError::ProviderUnavailable),
            ApiFailure::Unavailable
        );
        assert_eq!(ApiFailure::from(AccountError::Server), ApiFailure::Server);
        assert_eq!(
            ApiFailure::from(AccountError::Internal),
            ApiFailure::Internal
        );
        assert_eq!(
            ApiFailure::from(AccountError::Forbidden),
            ApiFailure::Unexpected
        );
    }

    #[test]
    fn unknown_role_gets_member_rights() {
        let user = WireUser {
            id: "u".into(),
            role: "superuser".into(),
            display_name: Some("  ".into()),
            providers: vec!["google".into()],
            status: None,
        };
        let profile = user.profile();
        assert_eq!(profile.role, UserRole::Member);
        assert_eq!(profile.display_name, None);
        assert!(!profile.has_apple);
        // Serveur d'avant la validation : compte actif.
        assert_eq!(profile.access, AccessStatus::Active);
    }

    #[test]
    fn access_status_is_read_and_unknown_means_no_access() {
        let with = |status: &str| WireUser {
            id: "u".into(),
            role: "member".into(),
            display_name: None,
            providers: vec![],
            status: Some(status.into()),
        };
        assert_eq!(with("active").profile().access, AccessStatus::Active);
        assert_eq!(with("pending").profile().access, AccessStatus::Pending);
        assert_eq!(with("refused").profile().access, AccessStatus::Refused);
        assert_eq!(with("suspended").profile().access, AccessStatus::Pending);
    }
}
