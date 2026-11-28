//! L'espace d'administration tel que l'app le voit : un objet qui relie le
//! compte (Worker), les réglages des serveurs, la recherche et qBittorrent.

use std::collections::HashMap;
use std::sync::Arc;

use tokio::sync::Mutex;

use super::AdminError;
use super::qbittorrent::{Download, NewTorrent, Qbit, valid_category};
use super::search::Release;
use super::search::{
    self, ReleaseLink, SearchObserver, SearchOutcome, SearchQuery, SourceFailure, year_of_unix,
};
use super::settings::{PlexSettings, QbitSettings, ServerSettings, TorznabSource};
use super::torznab;
use super::worker::{
    self, AdminRequest, AllowedEmail, AutomationStatus, Member, Recommendation, RequestFilter,
};
use crate::account::{Account, SessionStore};
use crate::cache::unix_now;

/// Espace d'administration. Un par processus suffit ; ses méthodes peuvent
/// être appelées en parallèle.
#[derive(uniffi::Object)]
pub struct AdminSpace {
    account: Arc<Account>,
    /// Copie locale des réglages (Keystore), pour travailler hors ligne.
    store: Arc<dyn SessionStore>,
    http: reqwest::Client,
    settings: Mutex<ServerSettings>,
    /// Client qBittorrent des réglages courants (et sa session).
    qbit: Mutex<Option<Arc<Qbit>>>,
    /// Liens des releases de la dernière recherche, par identifiant.
    links: Mutex<HashMap<String, ReleaseLink>>,
}

/// Empreinte d'un lien magnet (`xt=urn:btih:` en hexadécimal), si elle y est.
fn magnet_hash(link: &str) -> Option<String> {
    let query = link.strip_prefix("magnet:?")?;
    query.split('&').find_map(|pair| {
        let value = pair.strip_prefix("xt=urn:btih:")?;
        (value.len() == 40 && value.bytes().all(|b| b.is_ascii_hexdigit()))
            .then(|| value.to_ascii_lowercase())
    })
}

impl AdminSpace {
    async fn qbit(&self) -> Result<Arc<Qbit>, AdminError> {
        let mut guard = self.qbit.lock().await;
        if let Some(existing) = guard.as_ref() {
            return Ok(Arc::clone(existing));
        }
        let settings = self.settings.lock().await;
        let qbit_settings = settings
            .qbittorrent
            .as_ref()
            .ok_or(AdminError::NotConfigured)?;
        let client = Arc::new(Qbit::new(self.http.clone(), qbit_settings)?);
        *guard = Some(Arc::clone(&client));
        Ok(client)
    }

    /// Adopte des réglages : en mémoire, dans le coffre local, nouveau client qBittorrent.
    async fn adopt(&self, settings: ServerSettings) -> Result<(), AdminError> {
        self.store.save(settings.to_stored()?);
        *self.settings.lock().await = settings;
        *self.qbit.lock().await = None;
        Ok(())
    }

    async fn categories(&self) -> (String, String) {
        let settings = self.settings.lock().await;
        (
            settings.movie_category.clone(),
            settings.series_category.clone(),
        )
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl AdminSpace {
    /// `store` : coffre des réglages (Keystore), distinct de celui de la
    /// session. Les réglages qui s'y trouvent sont repris sans réseau.
    #[uniffi::constructor]
    pub fn new(
        account: Arc<Account>,
        store: Arc<dyn SessionStore>,
    ) -> Result<Arc<Self>, AdminError> {
        let http = crate::api::http_client().map_err(|_| AdminError::Internal)?;
        let settings = match store.load() {
            Some(raw) => ServerSettings::from_stored(&raw).unwrap_or_else(|| {
                store.clear();
                ServerSettings::default()
            }),
            None => ServerSettings::default(),
        };
        Ok(Arc::new(Self {
            account,
            store,
            http,
            settings: Mutex::new(settings),
            qbit: Mutex::new(None),
            links: Mutex::new(HashMap::new()),
        }))
    }

    /// Réglages courants (copie locale), sans réseau.
    pub async fn settings(&self) -> ServerSettings {
        self.settings.lock().await.clone()
    }

    /// Relit les réglages gardés chiffrés sur le Worker (nouvel appareil,
    /// réglages changés ailleurs). Sans réglages sur le Worker, la copie
    /// locale est gardée telle quelle.
    pub async fn sync_settings(&self) -> Result<ServerSettings, AdminError> {
        if let Some(raw) = worker::load_settings(&self.account).await? {
            let settings = ServerSettings::from_stored(&raw).ok_or(AdminError::Unexpected)?;
            self.adopt(settings).await?;
        }
        Ok(self.settings().await)
    }

    /// Vérifie, envoie au Worker (chiffrés là-bas) puis garde en local.
    pub async fn save_settings(&self, settings: ServerSettings) -> Result<(), AdminError> {
        settings.validate()?;
        let stored = settings.to_stored()?;
        worker::save_settings(&self.account, &stored).await?;
        self.adopt(settings).await
    }

    /// Teste une source (`t=caps`) : `None` si elle répond, sinon la raison.
    pub async fn test_source(&self, source: TorznabSource) -> Option<SourceFailure> {
        let url = match torznab::caps_url(&source) {
            Ok(url) => url,
            Err(failure) => return Some(failure),
        };
        let result = torznab::fetch(&self.http, url)
            .await
            .and_then(|body| torznab::parse_caps(&body));
        match result {
            Ok(()) => None,
            Err(torznab::FeedError::Failure(failure)) => Some(failure),
            Err(torznab::FeedError::Unsupported) => Some(SourceFailure::InvalidResponse),
        }
    }

    /// Teste une connexion à qBittorrent ; rend sa version (`5.0`).
    pub async fn test_qbittorrent(&self, settings: QbitSettings) -> Result<String, AdminError> {
        Qbit::new(self.http.clone(), &settings)?.test().await
    }

    /// File des demandes.
    pub async fn requests(&self, filter: RequestFilter) -> Result<Vec<AdminRequest>, AdminError> {
        worker::requests(&self.account, filter).await
    }

    /// Cherche sur toutes les sources actives en parallèle (6 s au plus
    /// chacune). `observer` reçoit les résultats au fil de l'eau.
    pub async fn search(
        &self,
        query: SearchQuery,
        observer: Option<Arc<dyn SearchObserver>>,
    ) -> Result<SearchOutcome, AdminError> {
        let settings = self.settings().await;
        if !settings.sources.iter().any(|s| s.enabled) {
            return Err(AdminError::NotConfigured);
        }
        if query.title.trim().is_empty() {
            return Err(AdminError::InvalidSettings);
        }
        let found = search::run(
            &self.http,
            &settings,
            &query,
            observer.as_deref(),
            year_of_unix(unix_now()),
        )
        .await;
        *self.links.lock().await = found.links;
        Ok(found.outcome)
    }

    /// Avis de Claude sur une recherche : la release la plus adaptée aux
    /// règles de l'administrateur parmi `releases` (les 40 premières), et
    /// pourquoi. Un seul appel ; à faire une fois la recherche finie.
    pub async fn recommend(
        &self,
        query: SearchQuery,
        releases: Vec<Release>,
    ) -> Result<Recommendation, AdminError> {
        if releases.is_empty() {
            return Ok(Recommendation {
                release_id: None,
                reason: String::new(),
            });
        }
        worker::recommend(&self.account, &query, &releases).await
    }

    /// Où en sont la recherche automatique et la lecture de Plex, vues du Worker.
    pub async fn automation_status(&self) -> Result<AutomationStatus, AdminError> {
        worker::automation_status(&self.account).await
    }

    /// Teste une connexion Plex depuis le Worker (c'est lui qui la lira) ;
    /// rend le nombre de bibliothèques films et séries.
    pub async fn test_plex(&self, settings: PlexSettings) -> Result<u32, AdminError> {
        settings.validate()?;
        worker::test_plex(&self.account, &settings).await
    }

    /// Catégories qui existent déjà dans qBittorrent, pour choisir celle de
    /// l'envoi. Vide si qBittorrent n'en a aucune.
    pub async fn qbit_categories(&self) -> Result<Vec<String>, AdminError> {
        self.qbit().await?.categories().await
    }

    /// Envoie la release choisie à qBittorrent, dans `category` (créée au
    /// besoin), puis passe la demande « en cours ».
    ///
    /// [`AdminError::StatusNotSaved`] : le torrent est parti mais le Worker n'a
    /// pas enregistré l'état ; [`AdminSpace::confirm_release`] réessaie sans
    /// renvoyer le torrent.
    pub async fn send_release(
        &self,
        request_id: String,
        release_id: String,
        category: String,
    ) -> Result<AdminRequest, AdminError> {
        if !valid_category(&category) {
            return Err(AdminError::InvalidSettings);
        }
        let category = category.trim();
        let link = self
            .links
            .lock()
            .await
            .get(&release_id)
            .cloned()
            .ok_or(AdminError::ReleaseExpired)?;
        let qbit = self.qbit().await?;
        qbit.add(&NewTorrent {
            link: &link.link,
            category,
            request_id: Some(&request_id),
        })
        .await?;
        self.confirm_release(request_id, release_id).await
    }

    /// Enregistre « en cours » pour une release déjà envoyée.
    pub async fn confirm_release(
        &self,
        request_id: String,
        release_id: String,
    ) -> Result<AdminRequest, AdminError> {
        let link = self
            .links
            .lock()
            .await
            .get(&release_id)
            .cloned()
            .ok_or(AdminError::ReleaseExpired)?;
        let hash = link.infohash.clone().or_else(|| magnet_hash(&link.link));
        worker::mark_started(&self.account, &request_id, &link.title, hash.as_deref())
            .await
            .map_err(|error| match error {
                AdminError::Network | AdminError::Server | AdminError::Unexpected => {
                    AdminError::StatusNotSaved
                }
                other => other,
            })
    }

    /// Refuse la demande, avec un motif court facultatif montré aux personnes qui l'attendent.
    pub async fn refuse(
        &self,
        request_id: String,
        reason: Option<String>,
    ) -> Result<AdminRequest, AdminError> {
        worker::refuse(&self.account, &request_id, reason.as_deref()).await
    }

    /// Remet la demande à « demandée » (annule un refus ou un envoi).
    pub async fn reopen(&self, request_id: String) -> Result<AdminRequest, AdminError> {
        worker::reopen(&self.account, &request_id).await
    }

    /// Marque la demande « disponible » : déjà sur Plex sans que les webhooks
    /// l'aient vue arriver en entier. Personne n'est prévenu.
    pub async fn mark_available(&self, request_id: String) -> Result<AdminRequest, AdminError> {
        worker::mark_available(&self.account, &request_id).await
    }

    /// Téléchargements envoyés par l'app ou rangés dans les catégories films
    /// et séries par défaut, les plus récents d'abord.
    pub async fn downloads(&self) -> Result<Vec<Download>, AdminError> {
        let (movies, series) = self.categories().await;
        self.qbit()
            .await?
            .downloads(&[movies.as_str(), series.as_str()])
            .await
    }

    pub async fn pause_download(&self, hash: String) -> Result<(), AdminError> {
        self.qbit().await?.pause(&hash).await
    }

    pub async fn resume_download(&self, hash: String) -> Result<(), AdminError> {
        self.qbit().await?.resume(&hash).await
    }

    /// Retire le torrent de qBittorrent ; `delete_files` efface aussi les fichiers.
    pub async fn delete_download(
        &self,
        hash: String,
        delete_files: bool,
    ) -> Result<(), AdminError> {
        self.qbit().await?.delete(&hash, delete_files).await
    }

    /// Comptes, ceux en attente de validation d'abord.
    pub async fn members(&self) -> Result<Vec<Member>, AdminError> {
        worker::members(&self.account).await
    }

    /// Accepte un compte en attente (ou rend l'accès) ; la personne est prévenue.
    pub async fn approve_member(&self, id: String) -> Result<(), AdminError> {
        worker::decide(&self.account, &id, true).await
    }

    /// Refuse un compte en attente, ou retire l'accès d'un membre.
    pub async fn refuse_member(&self, id: String) -> Result<(), AdminError> {
        worker::decide(&self.account, &id, false).await
    }

    /// Adresses acceptées d'avance.
    pub async fn allowed_emails(&self) -> Result<Vec<AllowedEmail>, AdminError> {
        worker::allowed_emails(&self.account).await
    }

    /// Accepte d'avance cette adresse (proche, compte de démonstration).
    /// Rend le nombre de comptes en attente acceptés du même coup.
    pub async fn allow_email(&self, email: String) -> Result<u32, AdminError> {
        worker::allow_email(&self.account, &email).await
    }

    pub async fn remove_allowed_email(&self, email: String) -> Result<(), AdminError> {
        worker::remove_allowed_email(&self.account, &email).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn magnet_hashes_are_read() {
        let hash = "0123456789abcdef0123456789ABCDEF01234567";
        assert_eq!(
            magnet_hash(&format!("magnet:?dn=x&xt=urn:btih:{hash}&tr=udp")).as_deref(),
            Some("0123456789abcdef0123456789abcdef01234567")
        );
        assert_eq!(magnet_hash("https://indexer.example.com/dl/1"), None);
        assert_eq!(
            magnet_hash("magnet:?xt=urn:btih:BASE32BASE32BASE32BASE32BASE32BA"),
            None
        );
    }
}
