//! Réglages des serveurs de l'administrateur : sources Torznab, qBittorrent,
//! catégories et expressions exclues.
//!
//! Le cœur les reçoit de l'app (copie locale du Keystore) ou du Worker, qui
//! les garde chiffrés ; il ne les écrit jamais en clair ailleurs que dans le
//! coffre fourni par l'app. Rien ici n'est journalisé.

use serde::{Deserialize, Serialize};

use super::AdminError;
use crate::api::BaseUrl;

/// Catégories qBittorrent par défaut (dossiers de destination).
pub(crate) const DEFAULT_MOVIE_CATEGORY: &str = "films";
pub(crate) const DEFAULT_SERIES_CATEGORY: &str = "series";
const MAX_SOURCES: usize = 20;
const MAX_EXCLUDED: usize = 50;
const MAX_FIELD: usize = 512;
/// Longueur maximale des règles de choix (une ou deux phrases).
const MAX_RULES: usize = 500;

/// Une source Torznab ajoutée par l'administrateur (un indexeur de son
/// Prowlarr, ou tout service compatible). Aucune n'est fournie par l'app.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct TorznabSource {
    /// Identifiant stable choisi par l'app (UUID).
    pub id: String,
    /// Nom donné par l'administrateur, affiché quand la source ne répond pas.
    pub name: String,
    /// Adresse du flux Torznab (`https://…/1/api` pour Prowlarr). Secret : jamais journalisée.
    pub url: String,
    /// Clé d'API du service. Secret.
    pub api_key: String,
    pub enabled: bool,
}

/// Connexion à la Web API de qBittorrent.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum QbitAuth {
    /// Identifiant et mot de passe de l'interface web (connexion par cookie).
    Password { username: String, password: String },
    /// Jeton envoyé dans l'en-tête `Authorization: Bearer`, pour un
    /// qBittorrent placé derrière un proxy qui l'exige.
    Bearer { token: String },
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct QbitSettings {
    /// Adresse HTTPS de l'interface web. Secret.
    pub url: String,
    pub auth: QbitAuth,
}

/// Connexion à Plex Media Server, lue par le Worker pour marquer
/// « disponible » ce qui est déjà dans la bibliothèque.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct PlexSettings {
    /// Adresse HTTPS du serveur Plex, joignable depuis Internet. Secret.
    pub url: String,
    /// Jeton `X-Plex-Token` du compte propriétaire. Secret.
    pub token: String,
}

/// Tous les réglages des serveurs, gardés ensemble.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct ServerSettings {
    #[serde(default)]
    pub sources: Vec<TorznabSource>,
    #[serde(default)]
    pub qbittorrent: Option<QbitSettings>,
    /// Catégorie qBittorrent des films.
    #[serde(default = "default_movie_category")]
    pub movie_category: String,
    /// Catégorie qBittorrent des séries.
    #[serde(default = "default_series_category")]
    pub series_category: String,
    /// Mots écartés des résultats (mots entiers, sans casse), par exemple `HC`.
    #[serde(default)]
    pub excluded_terms: Vec<String>,
    /// Clé d'API Anthropic de l'administrateur : recommandation des releases
    /// et recherche automatique par le Worker. Secret.
    #[serde(default)]
    #[uniffi(default = None)]
    pub anthropic_api_key: Option<String>,
    /// Règles de choix en une phrase libre (« 4K léger en MULTi VFF, 15 Go
    /// maximum »), appliquées par Claude.
    #[serde(default)]
    #[uniffi(default = "")]
    pub release_rules: String,
    #[serde(default)]
    #[uniffi(default = None)]
    pub plex: Option<PlexSettings>,
}

fn default_movie_category() -> String {
    DEFAULT_MOVIE_CATEGORY.to_owned()
}

fn default_series_category() -> String {
    DEFAULT_SERIES_CATEGORY.to_owned()
}

impl Default for ServerSettings {
    fn default() -> Self {
        Self {
            sources: Vec::new(),
            qbittorrent: None,
            movie_category: default_movie_category(),
            series_category: default_series_category(),
            excluded_terms: Vec::new(),
            anthropic_api_key: None,
            release_rules: String::new(),
            plex: None,
        }
    }
}

// Les réglages contiennent des secrets : leur forme de débogage n'en montre rien.
impl std::fmt::Debug for TorznabSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TorznabSource")
            .field("id", &self.id)
            .field("enabled", &self.enabled)
            .finish_non_exhaustive()
    }
}

impl std::fmt::Debug for QbitSettings {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("QbitSettings").finish_non_exhaustive()
    }
}

impl std::fmt::Debug for QbitAuth {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Password { .. } => "QbitAuth::Password",
            Self::Bearer { .. } => "QbitAuth::Bearer",
        })
    }
}

impl std::fmt::Debug for PlexSettings {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PlexSettings").finish_non_exhaustive()
    }
}

impl std::fmt::Debug for ServerSettings {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ServerSettings")
            .field("sources", &self.sources)
            .field("qbittorrent", &self.qbittorrent)
            .field(
                "anthropic_api_key",
                &self.anthropic_api_key.as_ref().map(|_| "…"),
            )
            .field("plex", &self.plex)
            .finish_non_exhaustive()
    }
}

/// Catégorie qBittorrent acceptable : lettres, chiffres, espaces, `-` et `_`.
fn valid_category(value: &str) -> bool {
    let value = value.trim();
    !value.is_empty()
        && value.chars().count() <= 50
        && value
            .chars()
            .all(|c| c.is_alphanumeric() || c == ' ' || c == '-' || c == '_')
}

fn bounded(value: &str) -> bool {
    !value.trim().is_empty() && value.len() <= MAX_FIELD
}

impl PlexSettings {
    /// Adresse HTTPS et jeton rempli ; [`AdminError::InvalidSettings`] sinon.
    pub(crate) fn validate(&self) -> Result<(), AdminError> {
        BaseUrl::parse(&self.url).map_err(|_| AdminError::InvalidSettings)?;
        if bounded(&self.token) {
            Ok(())
        } else {
            Err(AdminError::InvalidSettings)
        }
    }
}

impl ServerSettings {
    /// Vérifie les réglages avant de les garder : adresses HTTPS, champs
    /// remplis, catégories valides. [`AdminError::InvalidSettings`] sinon.
    pub(crate) fn validate(&self) -> Result<(), AdminError> {
        let invalid = AdminError::InvalidSettings;
        if self.sources.len() > MAX_SOURCES || self.excluded_terms.len() > MAX_EXCLUDED {
            return Err(invalid);
        }
        let mut ids = std::collections::HashSet::new();
        for source in &self.sources {
            if !bounded(&source.id)
                || !bounded(&source.name)
                || !bounded(&source.api_key)
                || !ids.insert(&source.id)
            {
                return Err(invalid);
            }
            BaseUrl::parse(&source.url).map_err(|_| AdminError::InvalidSettings)?;
        }
        if let Some(qbit) = &self.qbittorrent {
            BaseUrl::parse(&qbit.url).map_err(|_| AdminError::InvalidSettings)?;
            let ok = match &qbit.auth {
                QbitAuth::Password { username, password } => bounded(username) && bounded(password),
                QbitAuth::Bearer { token } => bounded(token),
            };
            if !ok {
                return Err(invalid);
            }
        }
        if !valid_category(&self.movie_category) || !valid_category(&self.series_category) {
            return Err(invalid);
        }
        if self.excluded_terms.iter().any(|t| t.len() > 100) {
            return Err(invalid);
        }
        if let Some(key) = &self.anthropic_api_key
            && !bounded(key)
        {
            return Err(invalid);
        }
        if self.release_rules.len() > MAX_RULES {
            return Err(invalid);
        }
        if let Some(plex) = &self.plex {
            plex.validate()?;
        }
        Ok(())
    }

    /// Forme gardée (Keystore, Worker) : JSON.
    pub(crate) fn to_stored(&self) -> Result<String, AdminError> {
        serde_json::to_string(self).map_err(|_| AdminError::Internal)
    }

    /// Lecture d'une forme gardée ; `None` si elle est illisible.
    pub(crate) fn from_stored(raw: &str) -> Option<Self> {
        serde_json::from_str(raw).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn source(id: &str, url: &str) -> TorznabSource {
        TorznabSource {
            id: id.into(),
            name: "Prowlarr".into(),
            url: url.into(),
            api_key: "k".repeat(32),
            enabled: true,
        }
    }

    #[test]
    fn defaults_have_french_categories() {
        let settings = ServerSettings::default();
        assert_eq!(settings.movie_category, "films");
        assert_eq!(settings.series_category, "series");
        assert!(settings.validate().is_ok());
    }

    #[test]
    fn https_is_required() {
        let mut settings = ServerSettings::default();
        settings
            .sources
            .push(source("a", "http://indexer.example.com/1/api"));
        assert!(matches!(
            settings.validate(),
            Err(AdminError::InvalidSettings)
        ));
        settings.sources = vec![source("a", "https://indexer.example.com/1/api")];
        assert!(settings.validate().is_ok());
    }

    #[test]
    fn duplicate_ids_and_empty_fields_are_refused() {
        let mut settings = ServerSettings {
            sources: vec![
                source("a", "https://x.example.com/api"),
                source("a", "https://y.example.com/api"),
            ],
            ..ServerSettings::default()
        };
        assert!(settings.validate().is_err());
        settings.sources = vec![];
        settings.qbittorrent = Some(QbitSettings {
            url: "https://qbit.example.com".into(),
            auth: QbitAuth::Password {
                username: "admin".into(),
                password: " ".into(),
            },
        });
        assert!(settings.validate().is_err());
        settings.qbittorrent = None;
        settings.movie_category = "films/../..".into();
        assert!(settings.validate().is_err());
    }

    #[test]
    fn assistant_and_plex_settings_are_checked() {
        let mut settings = ServerSettings {
            anthropic_api_key: Some("cle".into()),
            release_rules: "MULTi VFF, 15 Go maximum".into(),
            plex: Some(PlexSettings {
                url: "https://plex.example.com".into(),
                token: "jeton".into(),
            }),
            ..ServerSettings::default()
        };
        assert!(settings.validate().is_ok());
        settings.release_rules = "x".repeat(501);
        assert!(settings.validate().is_err());
        settings.release_rules = String::new();
        settings.anthropic_api_key = Some(" ".into());
        assert!(settings.validate().is_err());
        settings.anthropic_api_key = None;
        settings.plex = Some(PlexSettings {
            url: "http://plex.example.com".into(),
            token: "jeton".into(),
        });
        assert!(settings.validate().is_err());
    }

    #[test]
    fn stored_form_round_trips_and_tolerates_missing_fields() {
        let settings = ServerSettings {
            qbittorrent: Some(QbitSettings {
                url: "https://qbit.example.com".into(),
                auth: QbitAuth::Bearer { token: "t".into() },
            }),
            ..ServerSettings::default()
        };
        let raw = settings.to_stored().unwrap();
        assert_eq!(ServerSettings::from_stored(&raw), Some(settings));
        let old = ServerSettings::from_stored("{}").unwrap();
        assert_eq!(old.movie_category, "films");
        assert!(ServerSettings::from_stored("pas du json").is_none());
    }

    #[test]
    fn debug_never_shows_secrets() {
        let mut settings = ServerSettings::default();
        settings
            .sources
            .push(source("a", "https://secret-host.example.com/api"));
        settings.qbittorrent = Some(QbitSettings {
            url: "https://qbit.example.com".into(),
            auth: QbitAuth::Password {
                username: "admin".into(),
                password: "hunter2".into(),
            },
        });
        settings.anthropic_api_key = Some("cle-anthropic-secrete".into());
        settings.plex = Some(PlexSettings {
            url: "https://plex.example.com".into(),
            token: "jeton-plex".into(),
        });
        let shown = format!("{settings:?}");
        assert!(!shown.contains("cle-anthropic"));
        assert!(!shown.contains("jeton-plex"));
        assert!(!shown.contains("plex.example.com"));
        assert!(!shown.contains("secret-host"));
        assert!(!shown.contains("kkkk"));
        assert!(!shown.contains("hunter2"));
        assert!(!shown.contains("qbit.example.com"));
    }
}
