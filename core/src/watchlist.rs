//! Watchlist et demandes : ajouter un titre crée sa demande (ou rattache à la
//! demande existante) sur le Worker (`/v1/watchlist`).
//!
//! D1 est la source de vérité. Le téléphone en garde une copie dans SQLite et
//! une **file d'attente** des changements faits hors ligne : un ajout ou un
//! retrait est d'abord écrit en local (l'interface le montre tout de suite),
//! puis envoyé au Worker dès que possible. Une seule modification en attente
//! par titre : la dernière remplace la précédente. Les envois sont idempotents
//! et la dernière modification reçue par le Worker l'emporte.
//!
//! La copie locale appartient à un compte : un autre compte connecté sur le
//! même téléphone repart d'une watchlist vide.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use rusqlite::{Connection, OptionalExtension, params};
use serde::Deserialize;
use serde_json::Value;
use tokio::sync::{Mutex as AsyncMutex, OnceCell};

use crate::account::Account;
use crate::api::ApiFailure;
use crate::cache::{open_connection, unix_now};
use crate::catalog::{MAX_ID, MAX_SEASON, MediaType, TitleSummary};
use crate::tmdb;

const LIST_PATH: &str = "/v1/watchlist";

/// État d'une demande, partagé par toutes les personnes qui l'attendent.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RequestStatus {
    /// Demandée, pas encore traitée par l'administrateur.
    Requested,
    /// Téléchargement lancé par l'administrateur.
    InProgress,
    /// Disponible sur le serveur Plex.
    Available,
    /// Refusée par l'administrateur (motif facultatif).
    Refused,
}

impl RequestStatus {
    fn wire(self) -> &'static str {
        match self {
            Self::Requested => "requested",
            Self::InProgress => "in_progress",
            Self::Available => "available",
            Self::Refused => "refused",
        }
    }

    /// Un état inconnu (version future du serveur) vaut « demandée ».
    fn from_wire(raw: &str) -> Self {
        match raw {
            "in_progress" => Self::InProgress,
            "available" => Self::Available,
            "refused" => Self::Refused,
            _ => Self::Requested,
        }
    }
}

/// Un titre de la watchlist et l'état de sa demande.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WatchlistItem {
    pub media_type: MediaType,
    pub tmdb_id: u64,
    pub title: String,
    pub year: Option<u32>,
    pub poster_url: Option<String>,
    /// Série : saisons demandées ; `None` : film ou série entière.
    pub seasons: Option<Vec<u32>>,
    /// Date d'ajout, en secondes Unix.
    pub added_at: i64,
    pub status: RequestStatus,
    /// Motif d'un refus, écrit par l'administrateur (donnée, pas un texte de l'app).
    pub refusal_reason: Option<String>,
    /// Changement fait sur ce téléphone, pas encore reçu par le serveur.
    pub pending: bool,
}

/// La watchlist après une synchronisation.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WatchlistSnapshot {
    /// La plus récente d'abord.
    pub items: Vec<WatchlistItem>,
    /// Le serveur n'a pas pu être joint : la liste est la copie locale, et les
    /// changements en attente partiront plus tard.
    pub offline: bool,
}

/// Erreur d'une opération sur la watchlist. Le réseau n'en est jamais une :
/// les changements attendent dans la file. L'interface traduit chaque cas en
/// français ; le texte ci-dessous ne sert qu'aux journaux.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum WatchlistError {
    /// Session absente, expirée ou révoquée : se reconnecter.
    #[error("signed out")]
    SignedOut,
    /// Le serveur refuse ce changement (titre inconnu, saisons invalides) : il
    /// est abandonné.
    #[error("rejected")]
    Rejected,
    /// Défaillance interne (copie locale inutilisable).
    #[error("internal error")]
    Internal,
}

/// Watchlist de l'app. Une seule par processus, construite avec l'unique
/// [`Account`] : la session et le renouvellement du jeton sont ceux du compte.
#[derive(uniffi::Object)]
pub struct Watchlist {
    account: Arc<Account>,
    store_path: Option<String>,
    /// Ouverte au premier besoin, hors du fil de l'interface.
    store: OnceCell<Option<Store>>,
    /// Une seule synchronisation à la fois : la file part dans l'ordre.
    sync_lock: AsyncMutex<()>,
}

#[uniffi::export(async_runtime = "tokio")]
impl Watchlist {
    /// `store_path` : fichier de la copie locale (dossier privé de l'app).
    /// `None`, ou un fichier inutilisable : copie en mémoire, perdue à la
    /// fermeture de l'app, jamais d'échec.
    #[uniffi::constructor]
    pub fn new(account: Arc<Account>, store_path: Option<String>) -> Arc<Self> {
        Arc::new(Self {
            account,
            store_path,
            store: OnceCell::new(),
            sync_lock: AsyncMutex::new(()),
        })
    }

    /// Watchlist connue sur ce téléphone, changements en attente compris, sans
    /// appel réseau. Vide si personne n'est connecté.
    pub async fn items(&self) -> Vec<WatchlistItem> {
        match self.store_for_user().await {
            Ok(store) => store.view(),
            Err(_) => Vec::new(),
        }
    }

    /// Ce titre, s'il est dans la watchlist (sans appel réseau).
    pub async fn entry(&self, media_type: MediaType, tmdb_id: u64) -> Option<WatchlistItem> {
        self.items()
            .await
            .into_iter()
            .find(|item| item.media_type == media_type && item.tmdb_id == tmdb_id)
    }

    /// Envoie les changements en attente, puis relit la watchlist du serveur.
    pub async fn refresh(&self) -> Result<WatchlistSnapshot, WatchlistError> {
        let store = self.store_for_user().await?;
        let outcome = self.sync(store).await?;
        Ok(WatchlistSnapshot {
            items: store.view(),
            offline: outcome.offline,
        })
    }

    /// Ajoute le titre (ou change les saisons demandées) : sa demande est créée,
    /// ou la personne est rattachée à celle qui existe. `seasons` : `None` pour
    /// un film ou une série entière ; une liste non vide de saisons sinon
    /// (ignorée pour un film).
    ///
    /// Sans réseau, l'ajout attend dans la file et le résultat est `offline`.
    /// [`WatchlistError::Rejected`] : le serveur refuse ce titre ou ces saisons.
    pub async fn add(
        &self,
        title: TitleSummary,
        seasons: Option<Vec<u32>>,
    ) -> Result<WatchlistSnapshot, WatchlistError> {
        if !(1..=MAX_ID).contains(&title.id) {
            return Err(WatchlistError::Rejected);
        }
        let seasons = normalize_seasons(title.media_type, seasons)?;
        let store = self.store_for_user().await?;
        store.queue(&Change {
            media_type: title.media_type,
            tmdb_id: title.id,
            action: Action::Put(Draft {
                title: title.title,
                year: title.year,
                poster_url: title.poster_url,
                seasons,
            }),
        })?;
        self.after_change(store, title.media_type, title.id).await
    }

    /// Retire le titre. La demande disparaît si personne d'autre ne l'attend
    /// et qu'elle n'est pas encore lancée (règle du serveur).
    pub async fn remove(
        &self,
        media_type: MediaType,
        tmdb_id: u64,
    ) -> Result<WatchlistSnapshot, WatchlistError> {
        let store = self.store_for_user().await?;
        store.queue(&Change {
            media_type,
            tmdb_id,
            action: Action::Delete,
        })?;
        self.after_change(store, media_type, tmdb_id).await
    }
}

impl Watchlist {
    /// Copie locale, ouverte au premier besoin : le fichier, sinon la mémoire.
    async fn store(&self) -> Option<&Store> {
        self.store
            .get_or_init(|| async {
                self.store_path
                    .as_deref()
                    .and_then(Store::open)
                    .or_else(|| Store::open(":memory:"))
            })
            .await
            .as_ref()
    }

    /// Copie locale du compte connecté ; vidée si elle était à un autre compte.
    async fn store_for_user(&self) -> Result<&Store, WatchlistError> {
        let user_id = self
            .account
            .user_id()
            .await
            .ok_or(WatchlistError::SignedOut)?;
        let store = self.store().await.ok_or(WatchlistError::Internal)?;
        store.bind_user(&user_id)?;
        Ok(store)
    }

    async fn after_change(
        &self,
        store: &Store,
        media_type: MediaType,
        tmdb_id: u64,
    ) -> Result<WatchlistSnapshot, WatchlistError> {
        let outcome = self.sync(store).await?;
        if outcome.rejected.contains(&(media_type, tmdb_id)) {
            return Err(WatchlistError::Rejected);
        }
        Ok(WatchlistSnapshot {
            items: store.view(),
            offline: outcome.offline,
        })
    }

    /// Vide la file dans l'ordre, puis relit la liste du serveur.
    ///
    /// - Réseau, service ou serveur en échec : arrêt, la file reste (`offline`).
    /// - Changement refusé par le Worker (titre inconnu, requête invalide) :
    ///   abandonné, pour ne pas bloquer la file.
    /// - Réponse imprévue (intermédiaire, limitation) : le changement reste en
    ///   file pour la prochaine fois, les suivants partent (`offline`).
    /// - Session expirée : [`WatchlistError::SignedOut`], la file reste.
    async fn sync(&self, store: &Store) -> Result<SyncOutcome, WatchlistError> {
        let _guard = self.sync_lock.lock().await;
        let mut rejected = Vec::new();
        let mut postponed = Vec::new();
        while let Some(change) = store.next_change(&postponed) {
            let target = format!(
                "{LIST_PATH}/{}/{}",
                change.change.media_type.wire(),
                change.change.tmdb_id
            );
            let result = match &change.change.action {
                Action::Put(draft) => {
                    let body = serde_json::json!({ "seasons": draft.seasons });
                    self.account
                        .send_bytes(reqwest::Method::PUT, &target, Some(&body))
                        .await
                }
                Action::Delete => {
                    self.account
                        .send_bytes(reqwest::Method::DELETE, &target, None)
                        .await
                }
            };
            match result {
                Ok(body) => store.complete(&change, decode_put(&body)),
                Err(ApiFailure::SignedOut) => return Err(WatchlistError::SignedOut),
                Err(ApiFailure::NotFound | ApiFailure::Rejected) => {
                    store.drop_change(&change);
                    rejected.push((change.change.media_type, change.change.tmdb_id));
                }
                Err(ApiFailure::Unexpected) => postponed.push(change.seq),
                Err(_) => {
                    return Ok(SyncOutcome {
                        offline: true,
                        rejected,
                    });
                }
            }
        }
        let offline = match self.account.get_bytes(LIST_PATH).await {
            Ok(body) => match decode_list(&body) {
                Some(items) => {
                    store.replace_items(&items);
                    false
                }
                None => true,
            },
            Err(ApiFailure::SignedOut) => return Err(WatchlistError::SignedOut),
            Err(_) => true,
        };
        Ok(SyncOutcome {
            offline: offline || !postponed.is_empty(),
            rejected,
        })
    }
}

struct SyncOutcome {
    offline: bool,
    rejected: Vec<(MediaType, u64)>,
}

/// Saisons demandées, triées et sans doublon. Un film n'en a pas ; une liste
/// vide ou hors bornes est refusée.
fn normalize_seasons(
    media_type: MediaType,
    seasons: Option<Vec<u32>>,
) -> Result<Option<Vec<u32>>, WatchlistError> {
    match (media_type, seasons) {
        (MediaType::Movie, _) | (MediaType::Tv, None) => Ok(None),
        (MediaType::Tv, Some(mut list)) => {
            list.sort_unstable();
            list.dedup();
            if list.is_empty() || list.iter().any(|season| *season > MAX_SEASON) {
                return Err(WatchlistError::Rejected);
            }
            Ok(Some(list))
        }
    }
}

/// Entrée telle que le Worker la renvoie.
#[derive(Deserialize)]
struct WireItem {
    media_type: String,
    tmdb_id: u64,
    title: String,
    poster_path: Option<String>,
    year: Option<u32>,
    seasons: Option<Vec<u32>>,
    added_at: i64,
    status: String,
    refusal_reason: Option<String>,
}

impl WireItem {
    fn into_item(self) -> Option<WatchlistItem> {
        let title = self.title.trim();
        if title.is_empty() || !(1..=MAX_ID).contains(&self.tmdb_id) {
            return None;
        }
        Some(WatchlistItem {
            media_type: MediaType::from_wire(&self.media_type)?,
            tmdb_id: self.tmdb_id,
            title: title.to_owned(),
            year: self.year,
            poster_url: tmdb::list_poster_url(self.poster_path.as_deref()),
            seasons: self.seasons.filter(|list| !list.is_empty()),
            added_at: self.added_at,
            status: RequestStatus::from_wire(&self.status),
            refusal_reason: self
                .refusal_reason
                .map(|reason| reason.trim().to_owned())
                .filter(|reason| !reason.is_empty()),
            pending: false,
        })
    }
}

/// Liste du Worker ; une entrée illisible est ignorée, un corps illisible
/// donne `None`.
fn decode_list(body: &[u8]) -> Option<Vec<WatchlistItem>> {
    #[derive(Deserialize)]
    struct Envelope {
        items: Vec<Value>,
    }
    let envelope: Envelope = serde_json::from_slice(body).ok()?;
    Some(
        envelope
            .items
            .into_iter()
            .filter_map(|value| serde_json::from_value::<WireItem>(value).ok())
            .filter_map(WireItem::into_item)
            .collect(),
    )
}

/// Entrée renvoyée par un `PUT` ; `None` si elle est illisible (la relecture
/// de la liste corrigera).
fn decode_put(body: &[u8]) -> Option<WatchlistItem> {
    #[derive(Deserialize)]
    struct Envelope {
        item: WireItem,
    }
    serde_json::from_slice::<Envelope>(body)
        .ok()
        .and_then(|envelope| envelope.item.into_item())
}

/// Ce que l'app sait d'un titre ajouté avant que le serveur réponde.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Draft {
    title: String,
    year: Option<u32>,
    poster_url: Option<String>,
    seasons: Option<Vec<u32>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Action {
    Put(Draft),
    Delete,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Change {
    media_type: MediaType,
    tmdb_id: u64,
    action: Action,
}

/// Changement lu dans la file, avec son rang et sa date.
struct Queued {
    seq: i64,
    queued_at: i64,
    change: Change,
}

/// Copie locale : la liste du serveur (`items`), la file des changements
/// (`pending`, un seul par titre) et le compte propriétaire (`meta`).
struct Store {
    connection: Mutex<Connection>,
}

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS items (
    media_type TEXT NOT NULL,
    tmdb_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    year INTEGER,
    poster_url TEXT,
    seasons TEXT,
    added_at INTEGER NOT NULL,
    status TEXT NOT NULL,
    refusal_reason TEXT,
    PRIMARY KEY (media_type, tmdb_id)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS pending (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    media_type TEXT NOT NULL,
    tmdb_id INTEGER NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('put', 'delete')),
    title TEXT,
    year INTEGER,
    poster_url TEXT,
    seasons TEXT,
    queued_at INTEGER NOT NULL,
    UNIQUE (media_type, tmdb_id)
);";

fn seasons_text(seasons: Option<&Vec<u32>>) -> Option<String> {
    seasons.and_then(|list| serde_json::to_string(list).ok())
}

fn seasons_of(raw: Option<String>) -> Option<Vec<u32>> {
    raw.and_then(|text| serde_json::from_str::<Vec<u32>>(&text).ok())
        .filter(|list| !list.is_empty())
}

fn to_i64(value: u64) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

impl Store {
    fn open(path: &str) -> Option<Self> {
        let connection = open_connection(path)?;
        connection.execute_batch(SCHEMA).ok()?;
        Some(Self {
            connection: Mutex::new(connection),
        })
    }

    fn lock(&self) -> Result<std::sync::MutexGuard<'_, Connection>, WatchlistError> {
        self.connection.lock().map_err(|_| WatchlistError::Internal)
    }

    /// Rattache la copie à ce compte ; elle est vidée si elle était à un autre.
    fn bind_user(&self, user_id: &str) -> Result<(), WatchlistError> {
        let mut connection = self.lock()?;
        let owner: Option<String> = connection
            .query_row("SELECT value FROM meta WHERE key = 'user_id'", [], |row| {
                row.get(0)
            })
            .optional()
            .map_err(|_| WatchlistError::Internal)?;
        if owner.as_deref() == Some(user_id) {
            return Ok(());
        }
        let transaction = connection
            .transaction()
            .map_err(|_| WatchlistError::Internal)?;
        transaction
            .execute_batch("DELETE FROM items; DELETE FROM pending;")
            .map_err(|_| WatchlistError::Internal)?;
        transaction
            .execute(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('user_id', ?1)",
                [user_id],
            )
            .map_err(|_| WatchlistError::Internal)?;
        transaction.commit().map_err(|_| WatchlistError::Internal)
    }

    /// Met le changement en file ; il remplace celui qui attendait pour ce titre.
    fn queue(&self, change: &Change) -> Result<(), WatchlistError> {
        let connection = self.lock()?;
        let (action, draft) = match &change.action {
            Action::Put(draft) => ("put", Some(draft)),
            Action::Delete => ("delete", None),
        };
        connection
            .execute(
                "INSERT OR REPLACE INTO pending
                 (media_type, tmdb_id, action, title, year, poster_url, seasons, queued_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                params![
                    change.media_type.wire(),
                    to_i64(change.tmdb_id),
                    action,
                    draft.map(|d| d.title.as_str()),
                    draft.and_then(|d| d.year),
                    draft.and_then(|d| d.poster_url.as_deref()),
                    seasons_text(draft.and_then(|d| d.seasons.as_ref())),
                    unix_now(),
                ],
            )
            .map(|_| ())
            .map_err(|_| WatchlistError::Internal)
    }

    /// Changements en file, les plus anciens d'abord ; une ligne illisible est
    /// ignorée.
    fn queued(&self) -> Vec<Queued> {
        let Ok(connection) = self.lock() else {
            return Vec::new();
        };
        let Ok(mut statement) = connection.prepare(
            "SELECT seq, queued_at, media_type, tmdb_id, action, title, year, poster_url, seasons
             FROM pending ORDER BY seq",
        ) else {
            return Vec::new();
        };
        let rows = statement.query_map([], |row| {
            Ok((
                row.get::<_, i64>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, String>(4)?,
                row.get::<_, Option<String>>(5)?,
                row.get::<_, Option<u32>>(6)?,
                row.get::<_, Option<String>>(7)?,
                row.get::<_, Option<String>>(8)?,
            ))
        });
        let Ok(rows) = rows else {
            return Vec::new();
        };
        rows.filter_map(Result::ok)
            .filter_map(
                |(seq, queued_at, media, id, action, title, year, poster_url, seasons)| {
                    let action = match action.as_str() {
                        "put" => Action::Put(Draft {
                            title: title?,
                            year,
                            poster_url,
                            seasons: seasons_of(seasons),
                        }),
                        "delete" => Action::Delete,
                        _ => return None,
                    };
                    Some(Queued {
                        seq,
                        queued_at,
                        change: Change {
                            media_type: MediaType::from_wire(&media)?,
                            tmdb_id: u64::try_from(id).ok()?,
                            action,
                        },
                    })
                },
            )
            .collect()
    }

    /// Plus ancien changement en file, hors ceux remis à plus tard (`postponed`).
    fn next_change(&self, postponed: &[i64]) -> Option<Queued> {
        self.queued()
            .into_iter()
            .find(|queued| !postponed.contains(&queued.seq))
    }

    /// Retire ce changement de la file, sauf s'il a été remplacé entre-temps.
    fn drop_change(&self, queued: &Queued) {
        if let Ok(connection) = self.lock() {
            let _ = connection.execute("DELETE FROM pending WHERE seq = ?1", [queued.seq]);
        }
    }

    /// Changement accepté par le serveur : retiré de la file, et appliqué à la
    /// copie de la liste (l'entrée renvoyée par un `PUT`, ou le retrait).
    fn complete(&self, queued: &Queued, item: Option<WatchlistItem>) {
        self.drop_change(queued);
        let Ok(connection) = self.lock() else {
            return;
        };
        let change = &queued.change;
        match (&change.action, item) {
            (Action::Put(_), Some(item)) => insert_item(&connection, &item),
            (Action::Put(_), None) => {}
            (Action::Delete, _) => {
                let _ = connection.execute(
                    "DELETE FROM items WHERE media_type = ?1 AND tmdb_id = ?2",
                    params![change.media_type.wire(), to_i64(change.tmdb_id)],
                );
            }
        }
    }

    /// Remplace la copie de la liste par celle du serveur.
    fn replace_items(&self, items: &[WatchlistItem]) {
        let Ok(mut connection) = self.lock() else {
            return;
        };
        let Ok(transaction) = connection.transaction() else {
            return;
        };
        if transaction.execute("DELETE FROM items", []).is_err() {
            return;
        }
        for item in items {
            insert_item(&transaction, item);
        }
        let _ = transaction.commit();
    }

    /// Liste du serveur, avec les changements en attente par-dessus.
    fn view(&self) -> Vec<WatchlistItem> {
        let mut items: HashMap<(MediaType, u64), WatchlistItem> = self
            .server_items()
            .into_iter()
            .map(|item| ((item.media_type, item.tmdb_id), item))
            .collect();
        for queued in self.queued() {
            let key = (queued.change.media_type, queued.change.tmdb_id);
            match queued.change.action {
                Action::Delete => {
                    items.remove(&key);
                }
                Action::Put(draft) => {
                    let item = items.entry(key).or_insert_with(|| WatchlistItem {
                        media_type: key.0,
                        tmdb_id: key.1,
                        title: draft.title.clone(),
                        year: draft.year,
                        poster_url: draft.poster_url.clone(),
                        seasons: None,
                        added_at: queued.queued_at,
                        status: RequestStatus::Requested,
                        refusal_reason: None,
                        pending: true,
                    });
                    item.seasons = draft.seasons;
                    item.pending = true;
                }
            }
        }
        let mut list: Vec<WatchlistItem> = items.into_values().collect();
        list.sort_by(|a, b| b.added_at.cmp(&a.added_at).then(a.tmdb_id.cmp(&b.tmdb_id)));
        list
    }

    fn server_items(&self) -> Vec<WatchlistItem> {
        let Ok(connection) = self.lock() else {
            return Vec::new();
        };
        let Ok(mut statement) = connection.prepare(
            "SELECT media_type, tmdb_id, title, year, poster_url, seasons, added_at, status, refusal_reason
             FROM items",
        ) else {
            return Vec::new();
        };
        let rows = statement.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, Option<u32>>(3)?,
                row.get::<_, Option<String>>(4)?,
                row.get::<_, Option<String>>(5)?,
                row.get::<_, i64>(6)?,
                row.get::<_, String>(7)?,
                row.get::<_, Option<String>>(8)?,
            ))
        });
        let Ok(rows) = rows else {
            return Vec::new();
        };
        rows.filter_map(Result::ok)
            .filter_map(
                |(media, id, title, year, poster_url, seasons, added_at, status, reason)| {
                    Some(WatchlistItem {
                        media_type: MediaType::from_wire(&media)?,
                        tmdb_id: u64::try_from(id).ok()?,
                        title,
                        year,
                        poster_url,
                        seasons: seasons_of(seasons),
                        added_at,
                        status: RequestStatus::from_wire(&status),
                        refusal_reason: reason,
                        pending: false,
                    })
                },
            )
            .collect()
    }
}

fn insert_item(connection: &Connection, item: &WatchlistItem) {
    let _ = connection.execute(
        "INSERT OR REPLACE INTO items
         (media_type, tmdb_id, title, year, poster_url, seasons, added_at, status, refusal_reason)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![
            item.media_type.wire(),
            to_i64(item.tmdb_id),
            item.title,
            item.year,
            item.poster_url,
            seasons_text(item.seasons.as_ref()),
            item.added_at,
            item.status.wire(),
            item.refusal_reason,
        ],
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    fn draft(title: &str, seasons: Option<Vec<u32>>) -> Action {
        Action::Put(Draft {
            title: title.into(),
            year: Some(1999),
            poster_url: None,
            seasons,
        })
    }

    fn change(media_type: MediaType, tmdb_id: u64, action: Action) -> Change {
        Change {
            media_type,
            tmdb_id,
            action,
        }
    }

    fn server(tmdb_id: u64, added_at: i64, status: RequestStatus) -> WatchlistItem {
        WatchlistItem {
            media_type: MediaType::Movie,
            tmdb_id,
            title: format!("Film {tmdb_id}"),
            year: None,
            poster_url: None,
            seasons: None,
            added_at,
            status,
            refusal_reason: None,
            pending: false,
        }
    }

    fn store() -> Store {
        let store = Store::open(":memory:").unwrap();
        store.bind_user("u1").unwrap();
        store
    }

    #[test]
    fn seasons_are_sorted_deduplicated_and_ignored_for_movies() {
        assert_eq!(
            normalize_seasons(MediaType::Tv, Some(vec![3, 1, 3])).unwrap(),
            Some(vec![1, 3])
        );
        assert_eq!(normalize_seasons(MediaType::Tv, None).unwrap(), None);
        assert_eq!(
            normalize_seasons(MediaType::Movie, Some(vec![1])).unwrap(),
            None
        );
        assert!(normalize_seasons(MediaType::Tv, Some(vec![])).is_err());
        assert!(normalize_seasons(MediaType::Tv, Some(vec![100])).is_err());
    }

    #[test]
    fn unknown_statuses_read_as_requested() {
        for status in [
            RequestStatus::Requested,
            RequestStatus::InProgress,
            RequestStatus::Available,
            RequestStatus::Refused,
        ] {
            assert_eq!(RequestStatus::from_wire(status.wire()), status);
        }
        assert_eq!(
            RequestStatus::from_wire("archived"),
            RequestStatus::Requested
        );
    }

    #[test]
    fn a_list_skips_unreadable_entries() {
        let body = br#"{"items":[
            {"media_type":"movie","tmdb_id":603,"title":"Matrix","poster_path":"/m.jpg","year":1999,
             "seasons":null,"added_at":10,"status":"in_progress","refusal_reason":null},
            {"media_type":"book","tmdb_id":1,"title":"Livre","poster_path":null,"year":null,
             "seasons":null,"added_at":10,"status":"requested","refusal_reason":null},
            {"media_type":"tv","tmdb_id":2,"title":"  ","poster_path":null,"year":null,
             "seasons":null,"added_at":10,"status":"requested","refusal_reason":null},
            {"media_type":"tv","tmdb_id":"3"},
            {"media_type":"tv","tmdb_id":4,"title":"Serie","poster_path":"../x","year":null,
             "seasons":[],"added_at":5,"status":"refused","refusal_reason":"  "}
        ]}"#;
        let items = decode_list(body).unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(
            items[0].poster_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w342/m.jpg")
        );
        assert_eq!(items[0].status, RequestStatus::InProgress);
        assert_eq!(items[1].poster_url, None);
        assert_eq!(items[1].seasons, None);
        assert_eq!(items[1].refusal_reason, None);
        assert!(decode_list(b"[]").is_none());
        assert!(decode_list(b"pas du json").is_none());
    }

    #[test]
    fn the_view_lays_pending_changes_over_the_server_list() {
        let store = store();
        store.replace_items(&[
            server(1, 100, RequestStatus::Available),
            server(2, 200, RequestStatus::Requested),
        ]);
        store
            .queue(&change(MediaType::Movie, 2, Action::Delete))
            .unwrap();
        store
            .queue(&change(MediaType::Tv, 3, draft("Série", Some(vec![1]))))
            .unwrap();
        let view = store.view();
        assert_eq!(view.len(), 2);
        // L'ajout en attente est daté de maintenant : en tête.
        assert_eq!((view[0].tmdb_id, view[0].pending), (3, true));
        assert_eq!(view[0].status, RequestStatus::Requested);
        assert_eq!(view[0].seasons, Some(vec![1]));
        assert_eq!((view[1].tmdb_id, view[1].pending), (1, false));
    }

    #[test]
    fn a_pending_change_keeps_the_known_request_status() {
        let store = store();
        store.replace_items(&[server(1, 100, RequestStatus::InProgress)]);
        store
            .queue(&change(MediaType::Movie, 1, draft("Film 1", None)))
            .unwrap();
        let view = store.view();
        assert_eq!(view[0].status, RequestStatus::InProgress);
        assert_eq!(view[0].added_at, 100);
        assert!(view[0].pending);
    }

    #[test]
    fn the_last_change_of_a_title_replaces_the_previous_one() {
        let store = store();
        store
            .queue(&change(MediaType::Movie, 1, draft("Film", None)))
            .unwrap();
        store
            .queue(&change(MediaType::Movie, 2, draft("Autre", None)))
            .unwrap();
        store
            .queue(&change(MediaType::Movie, 1, Action::Delete))
            .unwrap();
        let queued = store.queued();
        assert_eq!(queued.len(), 2);
        // Le remplaçant prend sa place en fin de file.
        assert_eq!(queued[0].change.tmdb_id, 2);
        assert_eq!(queued[1].change.action, Action::Delete);
        assert!(store.view().iter().all(|item| item.tmdb_id != 1));
    }

    #[test]
    fn a_change_replaced_meanwhile_stays_queued() {
        let store = store();
        store
            .queue(&change(MediaType::Movie, 1, draft("Film", None)))
            .unwrap();
        let sent = store.next_change(&[]).unwrap();
        store
            .queue(&change(MediaType::Movie, 1, Action::Delete))
            .unwrap();
        store.complete(&sent, Some(server(1, 50, RequestStatus::Requested)));
        let queued = store.queued();
        assert_eq!(queued.len(), 1);
        assert_eq!(queued[0].change.action, Action::Delete);
        assert!(store.view().is_empty());
    }

    #[test]
    fn another_account_starts_from_an_empty_copy() {
        let store = store();
        store.replace_items(&[server(1, 100, RequestStatus::Requested)]);
        store
            .queue(&change(MediaType::Movie, 2, draft("Film", None)))
            .unwrap();
        store.bind_user("u1").unwrap();
        assert_eq!(store.view().len(), 2);
        store.bind_user("u2").unwrap();
        assert!(store.view().is_empty());
        assert!(store.queued().is_empty());
    }

    #[test]
    fn the_copy_survives_a_restart() {
        let path = std::env::temp_dir().join(format!(
            "heywatchlist-watchlist-{}-{}.sqlite",
            std::process::id(),
            unix_now()
        ));
        let path = path.to_str().unwrap().to_owned();
        {
            let store = Store::open(&path).unwrap();
            store.bind_user("u1").unwrap();
            store
                .queue(&change(MediaType::Tv, 7, draft("Série", Some(vec![2]))))
                .unwrap();
        }
        let store = Store::open(&path).unwrap();
        store.bind_user("u1").unwrap();
        assert_eq!(store.view()[0].seasons, Some(vec![2]));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn an_unusable_path_gives_no_store() {
        assert!(Store::open("").is_none());
        assert!(Store::open("/inexistant/dossier/watchlist.sqlite").is_none());
    }
}
