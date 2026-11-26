//! Compte HeyWatchlist : connexion Apple ou Google sur invitation, session
//! (jeton d'accès court, jeton de rafraîchissement renouvelé automatiquement),
//! déconnexion et suppression du compte.
//!
//! Les jetons ne sont jamais journalisés. Le jeton de rafraîchissement et le
//! profil sont confiés à un [`SessionStore`] fourni par l'app (Keychain sur
//! iPhone, Keystore sur Android) ; le jeton d'accès ne vit qu'en mémoire.

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::Mutex;

use crate::api::{
    self, ApiFailure, BaseUrl, ConfigResponse, MeResponse, SignInResponse, TokenPair, WireUser,
};

/// Erreur d'une opération de compte. L'interface traduit chaque cas en
/// français ; le texte ci-dessous ne sert qu'aux journaux.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AccountError {
    /// Pas de réseau, serveur injoignable ou délai dépassé.
    #[error("network unavailable")]
    Network,
    /// Le serveur a refusé le jeton d'Apple ou de Google : recommencer.
    #[error("identity token rejected")]
    InvalidIdentity,
    /// Personne inconnue : un code d'invitation est nécessaire.
    #[error("invitation required")]
    InvitationRequired,
    /// Code d'invitation inconnu, expiré ou déjà utilisé.
    #[error("invitation invalid")]
    InvitationInvalid,
    /// Session absente, expirée ou révoquée : se reconnecter.
    #[error("signed out")]
    SignedOut,
    /// Action réservée à l'administrateur.
    #[error("forbidden")]
    Forbidden,
    /// Connexion Google pas encore configurée sur le serveur, ou service
    /// d'Apple ou de Google injoignable.
    #[error("provider unavailable")]
    ProviderUnavailable,
    /// La personne a fermé la fenêtre de connexion.
    #[error("cancelled")]
    Cancelled,
    /// Erreur du serveur (5xx).
    #[error("server error")]
    Server,
    /// Adresse du serveur absente ou invalide dans l'app.
    #[error("invalid configuration")]
    InvalidConfiguration,
    /// Réponse imprévue (format, statut).
    #[error("unexpected response")]
    Unexpected,
    /// Défaillance interne.
    #[error("internal error")]
    Internal,
}

/// Rôle du compte.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum UserRole {
    Admin,
    Member,
}

/// Ce que l'app affiche du compte connecté.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct UserProfile {
    pub id: String,
    /// Prénom, s'il est connu (Apple ne le donne qu'à la première connexion).
    pub display_name: Option<String>,
    pub role: UserRole,
    /// Compte rattaché à Apple : sa suppression demande de révoquer Apple.
    pub has_apple: bool,
}

/// Fournisseur d'identité.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum IdentityProvider {
    Apple,
    Google,
}

impl IdentityProvider {
    fn wire(self) -> &'static str {
        match self {
            Self::Apple => "apple",
            Self::Google => "google",
        }
    }
}

/// Nonce d'une connexion. `raw` part au serveur avec le jeton ; le
/// fournisseur reçoit `sha256` (Apple) ou `raw` (Google), et le recopie dans
/// le jeton qu'il signe.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SignInNonce {
    pub raw: String,
    /// SHA-256 hexadécimal de `raw`, à passer à « Se connecter avec Apple ».
    pub sha256: String,
}

/// Nouveau nonce aléatoire pour une connexion.
#[uniffi::export]
pub fn new_sign_in_nonce() -> Result<SignInNonce, AccountError> {
    let raw = random_token(32)?;
    let sha256 = sha256_hex(&raw);
    Ok(SignInNonce { raw, sha256 })
}

pub(crate) fn random_token(bytes: usize) -> Result<String, AccountError> {
    use base64::Engine;
    let mut buffer = vec![0u8; bytes];
    getrandom::fill(&mut buffer).map_err(|_| AccountError::Internal)?;
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(buffer))
}

pub(crate) fn sha256_hex(value: &str) -> String {
    Sha256::digest(value.as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// Demande de connexion, après la fenêtre d'Apple ou de Google.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SignInRequest {
    pub provider: IdentityProvider,
    /// Jeton d'identité (JWT) délivré par Apple ou Google.
    pub id_token: String,
    /// `SignInNonce::raw` utilisé pour obtenir ce jeton.
    pub nonce: String,
    /// Code saisi, après une réponse [`AccountError::InvitationRequired`].
    pub invitation_code: Option<String>,
    /// Prénom donné par Apple (seulement à la première autorisation).
    pub given_name: Option<String>,
}

/// Identifiants OAuth publics de Google, donnés par le serveur.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GoogleClientIds {
    /// Client « Application Web » : `serverClientId` du Credential Manager d'Android.
    pub web: Option<String>,
    /// Client « iOS » : connexion Google sur iPhone.
    pub ios: Option<String>,
}

/// Lieu où l'app garde la session entre deux lancements (Keychain, Keystore).
/// La valeur est opaque : l'app la range telle quelle, chiffrée.
#[uniffi::export(with_foreign)]
pub trait SessionStore: Send + Sync {
    fn load(&self) -> Option<String>;
    fn save(&self, value: String);
    fn clear(&self);
}

/// Ce qui est confié au [`SessionStore`].
#[derive(Serialize, Deserialize)]
struct StoredSession {
    refresh_token: String,
    user: WireUser,
}

struct AccessToken {
    value: String,
    /// Moment à partir duquel on le renouvelle (un peu avant son expiration).
    renew_at: Instant,
}

struct Session {
    refresh_token: String,
    user: WireUser,
    access: Option<AccessToken>,
}

/// Marge avant expiration à partir de laquelle le jeton d'accès est renouvelé.
const RENEW_MARGIN: Duration = Duration::from_secs(60);

/// Compte de l'app. Un seul par processus suffit ; ses méthodes peuvent être
/// appelées en parallèle (le rafraîchissement est sérialisé).
#[derive(uniffi::Object)]
pub struct Account {
    pub(crate) base: BaseUrl,
    http: reqwest::Client,
    store: Arc<dyn SessionStore>,
    session: Mutex<Option<Session>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl Account {
    /// `base_url` : adresse HTTPS de l'API (`https://…workers.dev`).
    /// La session gardée dans `store` est reprise, sans appel réseau.
    #[uniffi::constructor]
    pub fn new(base_url: String, store: Arc<dyn SessionStore>) -> Result<Arc<Self>, AccountError> {
        let base = BaseUrl::parse(&base_url)?;
        let http = api::http_client()?;
        // Une valeur illisible (ancienne version, corruption) est oubliée.
        let session = match store.load() {
            Some(raw) => match serde_json::from_str::<StoredSession>(&raw) {
                Ok(stored) => Some(Session {
                    refresh_token: stored.refresh_token,
                    user: stored.user,
                    access: None,
                }),
                Err(_) => {
                    store.clear();
                    None
                }
            },
            None => None,
        };
        Ok(Arc::new(Self {
            base,
            http,
            store,
            session: Mutex::new(session),
        }))
    }

    /// Compte connecté d'après la session gardée, sans appel réseau.
    pub async fn current_user(&self) -> Option<UserProfile> {
        self.session.lock().await.as_ref().map(|s| s.user.profile())
    }

    /// Identifiants OAuth de Google que le serveur accepte.
    pub async fn google_client_ids(&self) -> Result<GoogleClientIds, AccountError> {
        let response = self
            .http
            .get(self.base.join("/v1/config"))
            .send()
            .await
            .map_err(api::transport_error)?;
        let config: ConfigResponse = api::read(response).await?;
        Ok(GoogleClientIds {
            web: config.google_web_client_id.filter(|id| !id.is_empty()),
            ios: config.google_ios_client_id.filter(|id| !id.is_empty()),
        })
    }

    /// Connexion avec le jeton d'Apple ou de Google. En cas de
    /// [`AccountError::InvitationRequired`], redemander avec le code saisi et
    /// le même jeton.
    pub async fn sign_in(&self, request: SignInRequest) -> Result<UserProfile, AccountError> {
        let body = serde_json::json!({
            "provider": request.provider.wire(),
            "id_token": request.id_token,
            "nonce": request.nonce,
            "invitation_code": request.invitation_code.filter(|c| !c.trim().is_empty()),
            "given_name": request.given_name.filter(|n| !n.trim().is_empty()),
        });
        let response = self
            .http
            .post(self.base.join("/v1/auth/sign-in"))
            .json(&body)
            .send()
            .await
            .map_err(api::transport_error)?;
        let signed_in: SignInResponse = api::read(response).await?;
        let profile = signed_in.user.profile();
        let mut guard = self.session.lock().await;
        *guard = Some(self.session_from(signed_in.tokens, signed_in.user));
        self.persist(guard.as_ref());
        Ok(profile)
    }

    /// Profil à jour depuis le serveur (le prénom, le rôle). Renouvelle la
    /// session si besoin ; [`AccountError::SignedOut`] si elle a expiré.
    pub async fn refresh_profile(&self) -> Result<UserProfile, AccountError> {
        let response = self
            .authorized(|http, token| http.get(self.base.join("/v1/me")).bearer_auth(token))
            .await?;
        let me: MeResponse = api::read(response).await?;
        let profile = me.user.profile();
        let mut guard = self.session.lock().await;
        if let Some(session) = guard.as_mut() {
            session.user = me.user;
        }
        self.persist(guard.as_ref());
        Ok(profile)
    }

    /// Déconnexion de cet appareil. La session locale est toujours effacée,
    /// même sans réseau ; le serveur est prévenu si possible.
    pub async fn sign_out(&self) {
        let session = self.session.lock().await.take();
        self.store.clear();
        if let Some(session) = session {
            let body = serde_json::json!({ "refresh_token": session.refresh_token });
            // Sans réseau, la session expirera d'elle-même côté serveur.
            let _ = self
                .http
                .post(self.base.join("/v1/auth/sign-out"))
                .json(&body)
                .send()
                .await;
        }
    }

    /// Supprime définitivement le compte et ses données sur le serveur, puis
    /// la session locale. Sur iPhone, `apple_authorization_code` (une nouvelle
    /// autorisation Apple) permet au serveur de révoquer la connexion Apple.
    pub async fn delete_account(
        &self,
        apple_authorization_code: Option<String>,
    ) -> Result<(), AccountError> {
        let body = serde_json::json!({
            "apple_authorization_code": apple_authorization_code.filter(|c| !c.is_empty()),
        });
        let response = self
            .authorized(|http, token| {
                http.delete(self.base.join("/v1/me"))
                    .bearer_auth(token)
                    .json(&body)
            })
            .await?;
        api::read_empty(response).await?;
        self.session.lock().await.take();
        self.store.clear();
        Ok(())
    }
}

impl Account {
    fn session_from(&self, tokens: TokenPair, user: WireUser) -> Session {
        let lifetime = Duration::from_secs(tokens.access_expires_in).saturating_sub(RENEW_MARGIN);
        Session {
            refresh_token: tokens.refresh_token,
            user,
            access: Some(AccessToken {
                value: tokens.access_token,
                renew_at: Instant::now() + lifetime,
            }),
        }
    }

    fn persist(&self, session: Option<&Session>) {
        match session {
            Some(session) => {
                let stored = StoredSession {
                    refresh_token: session.refresh_token.clone(),
                    user: session.user.clone(),
                };
                match serde_json::to_string(&stored) {
                    Ok(value) => self.store.save(value),
                    Err(_) => self.store.clear(),
                }
            }
            None => self.store.clear(),
        }
    }

    /// Jeton d'accès valide, renouvelé si besoin (`force` : même s'il semble
    /// encore valable, après un refus du serveur).
    async fn access_token(&self, force: bool) -> Result<String, AccountError> {
        let mut guard = self.session.lock().await;
        let Some(session) = guard.as_mut() else {
            return Err(AccountError::SignedOut);
        };
        if !force
            && let Some(access) = &session.access
            && Instant::now() < access.renew_at
        {
            return Ok(access.value.clone());
        }

        let body = serde_json::json!({ "refresh_token": session.refresh_token });
        let response = self
            .http
            .post(self.base.join("/v1/auth/refresh"))
            .json(&body)
            .send()
            .await
            .map_err(api::transport_error)?;
        match api::read::<TokenPair>(response).await {
            Ok(tokens) => {
                let user = session.user.clone();
                let renewed = self.session_from(tokens, user);
                let token = renewed.access.as_ref().map(|a| a.value.clone());
                *session = renewed;
                self.persist(Some(session));
                token.ok_or(AccountError::Internal)
            }
            Err(AccountError::SignedOut) => {
                // Session révoquée ou expirée : on l'oublie.
                *guard = None;
                self.store.clear();
                Err(AccountError::SignedOut)
            }
            Err(other) => Err(other),
        }
    }

    /// GET authentifié sur le Worker : corps brut de la réponse. Sert aux
    /// modules qui lisent l'API avec la session du compte (même client HTTP,
    /// même jeton, même renouvellement) sans second client.
    ///
    /// `target` est le chemin absolu avec sa requête déjà encodée
    /// (`/v1/...?page=1`).
    pub(crate) async fn get_bytes(&self, target: &str) -> Result<Vec<u8>, ApiFailure> {
        let url = self.base.join(target);
        let response = self
            .authorized(|http, token| http.get(&url).bearer_auth(token))
            .await?;
        api::read_bytes(response).await
    }

    /// Écriture authentifiée sur le Worker (`PUT`, `DELETE`…), avec un corps
    /// JSON facultatif : corps brut de la réponse (vide pour un 204).
    pub(crate) async fn send_bytes(
        &self,
        method: reqwest::Method,
        target: &str,
        body: Option<&serde_json::Value>,
    ) -> Result<Vec<u8>, ApiFailure> {
        let url = self.base.join(target);
        let response = self
            .authorized(|http, token| {
                let request = http.request(method.clone(), &url).bearer_auth(token);
                match body {
                    Some(body) => request.json(body),
                    None => request,
                }
            })
            .await?;
        api::read_bytes(response).await
    }

    /// Identifiant du compte connecté, sans appel réseau.
    pub(crate) async fn user_id(&self) -> Option<String> {
        self.session
            .lock()
            .await
            .as_ref()
            .map(|s| s.user.id.clone())
    }

    /// Envoie une requête authentifiée ; sur un 401, renouvelle le jeton une
    /// fois et recommence.
    pub(crate) async fn authorized<F>(&self, build: F) -> Result<reqwest::Response, AccountError>
    where
        F: Fn(&reqwest::Client, &str) -> reqwest::RequestBuilder,
    {
        let token = self.access_token(false).await?;
        let response = build(&self.http, &token)
            .send()
            .await
            .map_err(api::transport_error)?;
        if response.status() != reqwest::StatusCode::UNAUTHORIZED {
            return Ok(response);
        }
        let token = self.access_token(true).await?;
        build(&self.http, &token)
            .send()
            .await
            .map_err(api::transport_error)
    }
}

#[cfg(feature = "admin")]
#[uniffi::export(async_runtime = "tokio")]
impl Account {
    /// Crée un code d'invitation (administrateur seulement), valable 14 jours.
    pub async fn create_invitation(&self) -> Result<crate::admin::Invitation, AccountError> {
        let response = self
            .authorized(|http, token| {
                http.post(self.base.join("/v1/invitations"))
                    .bearer_auth(token)
                    .json(&serde_json::json!({}))
            })
            .await?;
        let invitation: api::InvitationResponse = api::read(response).await?;
        Ok(crate::admin::Invitation {
            code: invitation.code,
            expires_at: invitation.expires_at,
        })
    }
}
