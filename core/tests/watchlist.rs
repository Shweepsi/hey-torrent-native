//! Watchlist face à un Worker simulé (wiremock) : jamais le vrai serveur.

// Fichier de test : les assistants hors `#[test]` ont droit à `unwrap`.
#![allow(clippy::unwrap_used)]

use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use heywatchlist_core::{
    Account, IdentityProvider, MediaType, RequestStatus, SessionStore, SignInRequest, TitleSummary,
    Watchlist, WatchlistError,
};
use serde_json::json;
use wiremock::matchers::{body_json, header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

// ---------------------------------------------------------------- assistants

/// Coffre en mémoire, à la place du Keychain ou du Keystore.
#[derive(Default)]
struct MemoryStore(Mutex<Option<String>>);

impl SessionStore for MemoryStore {
    fn load(&self) -> Option<String> {
        self.0.lock().unwrap().clone()
    }
    fn save(&self, value: String) {
        *self.0.lock().unwrap() = Some(value);
    }
    fn clear(&self) {
        *self.0.lock().unwrap() = None;
    }
}

/// Dossier de travail d'un test, dans `target/tmp`, effacé à la fin.
struct Workspace(PathBuf);

impl Workspace {
    fn new() -> Self {
        static COUNTER: AtomicU32 = AtomicU32::new(0);
        let directory = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!(
            "watchlist-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&directory).unwrap();
        Self(directory)
    }

    fn store_file(&self) -> String {
        self.0.join("watchlist.sqlite").to_str().unwrap().to_owned()
    }
}

impl Drop for Workspace {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Adresse locale où rien n'écoute.
fn unreachable_uri() -> String {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    format!("http://127.0.0.1:{port}")
}

fn session_body() -> serde_json::Value {
    json!({
        "access_token": "acces-1",
        "access_expires_in": 900,
        "refresh_token": "refresh-1",
        "user": { "id": "u-1", "role": "member", "display_name": "Camille", "providers": ["apple"] },
    })
}

/// Compte connecté au serveur simulé (jeton d'accès `acces-1`), et son coffre.
async fn signed_in(server: &MockServer) -> (Arc<Account>, Arc<MemoryStore>) {
    Mock::given(method("POST"))
        .and(path("/v1/auth/sign-in"))
        .respond_with(ResponseTemplate::new(200).set_body_json(session_body()))
        .up_to_n_times(1)
        .mount(server)
        .await;
    let store = Arc::new(MemoryStore::default());
    let account = Account::new(server.uri(), store.clone()).unwrap();
    account
        .sign_in(SignInRequest {
            provider: IdentityProvider::Apple,
            id_token: "jeton-apple".into(),
            nonce: "nonce".into(),
            given_name: None,
        })
        .await
        .unwrap();
    (account, store)
}

fn title(media_type: MediaType, id: u64, name: &str) -> TitleSummary {
    TitleSummary {
        id,
        media_type,
        title: name.into(),
        year: Some(1999),
        release_date: Some("1999-03-30".into()),
        overview: String::new(),
        poster_url: Some("https://image.tmdb.org/t/p/w342/local.jpg".into()),
        backdrop_url: None,
        rating: 8.0,
        vote_count: 10,
        genre_ids: vec![],
    }
}

fn wire_item(
    media_type: &str,
    id: u64,
    name: &str,
    status: &str,
    seasons: serde_json::Value,
) -> serde_json::Value {
    json!({
        "media_type": media_type, "tmdb_id": id, "title": name, "poster_path": "/serveur.jpg",
        "year": 1999, "seasons": seasons, "added_at": 1_800_000_000, "status": status,
        "refusal_reason": null,
    })
}

fn list(items: Vec<serde_json::Value>) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(json!({ "items": items }))
}

fn failure(status: u16, code: &str) -> ResponseTemplate {
    ResponseTemplate::new(status).set_body_json(json!({ "error": code }))
}

async fn mount_list(server: &MockServer, items: Vec<serde_json::Value>) {
    Mock::given(method("GET"))
        .and(path("/v1/watchlist"))
        .and(header("authorization", "Bearer acces-1"))
        .respond_with(list(items))
        .mount(server)
        .await;
}

// ------------------------------------------------------------------- tests

#[tokio::test]
async fn adding_a_movie_creates_its_request_on_the_server() {
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    let matrix = wire_item("movie", 603, "Matrix", "requested", json!(null));
    Mock::given(method("PUT"))
        .and(path("/v1/watchlist/movie/603"))
        .and(header("authorization", "Bearer acces-1"))
        .and(body_json(json!({ "seasons": null })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "item": matrix })))
        .expect(1)
        .mount(&server)
        .await;
    mount_list(&server, vec![matrix.clone()]).await;

    let watchlist = Watchlist::new(account, None);
    let snapshot = watchlist
        .add(title(MediaType::Movie, 603, "Matrix"), Some(vec![1]))
        .await
        .unwrap();
    assert!(!snapshot.offline);
    assert_eq!(snapshot.items.len(), 1);
    let item = &snapshot.items[0];
    assert_eq!((item.tmdb_id, item.pending), (603, false));
    assert_eq!(item.status, RequestStatus::Requested);
    // L'affiche vient du serveur, à la taille des listes.
    assert_eq!(
        item.poster_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w342/serveur.jpg")
    );
    assert_eq!(
        watchlist
            .entry(MediaType::Movie, 603)
            .await
            .map(|i| i.title),
        Some("Matrix".to_owned())
    );
}

#[tokio::test]
async fn a_series_sends_the_chosen_seasons() {
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    let severance = wire_item("tv", 95396, "Severance", "in_progress", json!([1, 2]));
    Mock::given(method("PUT"))
        .and(path("/v1/watchlist/tv/95396"))
        .and(body_json(json!({ "seasons": [1, 2] })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "item": severance })))
        .expect(1)
        .mount(&server)
        .await;
    mount_list(&server, vec![severance]).await;

    let watchlist = Watchlist::new(account, None);
    let snapshot = watchlist
        .add(
            title(MediaType::Tv, 95396, "Severance"),
            Some(vec![2, 1, 2]),
        )
        .await
        .unwrap();
    assert_eq!(snapshot.items[0].seasons, Some(vec![1, 2]));
    assert_eq!(snapshot.items[0].status, RequestStatus::InProgress);
}

#[tokio::test]
async fn invalid_input_is_refused_without_network() {
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    let watchlist = Watchlist::new(account, None);
    assert!(matches!(
        watchlist
            .add(title(MediaType::Tv, 1, "Série"), Some(vec![]))
            .await,
        Err(WatchlistError::Rejected)
    ));
    assert!(matches!(
        watchlist
            .add(title(MediaType::Movie, 0, "Zéro"), None)
            .await,
        Err(WatchlistError::Rejected)
    ));
    assert!(watchlist.items().await.is_empty());
}

#[tokio::test]
async fn offline_changes_wait_in_the_queue_and_leave_when_the_network_returns() {
    let workspace = Workspace::new();
    let server = MockServer::start().await;
    let (_, store) = signed_in(&server).await;

    // Sans réseau : l'ajout est montré tout de suite, en attente.
    let offline = Account::new(unreachable_uri(), store.clone()).unwrap();
    let watchlist = Watchlist::new(offline, Some(workspace.store_file()));
    let snapshot = watchlist
        .add(title(MediaType::Movie, 603, "Matrix"), None)
        .await
        .unwrap();
    assert!(snapshot.offline);
    assert_eq!(snapshot.items.len(), 1);
    assert!(snapshot.items[0].pending);
    assert_eq!(snapshot.items[0].title, "Matrix");
    assert_eq!(snapshot.items[0].status, RequestStatus::Requested);
    drop(watchlist);

    // Au lancement suivant, le réseau est revenu : la file part.
    Mock::given(method("POST"))
        .and(path("/v1/auth/refresh"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "access_token": "acces-1", "access_expires_in": 900, "refresh_token": "refresh-2",
        })))
        .mount(&server)
        .await;
    let matrix = wire_item("movie", 603, "Matrix", "requested", json!(null));
    Mock::given(method("PUT"))
        .and(path("/v1/watchlist/movie/603"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "item": matrix })))
        .expect(1)
        .mount(&server)
        .await;
    mount_list(&server, vec![matrix]).await;

    let online = Account::new(server.uri(), store).unwrap();
    let watchlist = Watchlist::new(online, Some(workspace.store_file()));
    assert!(watchlist.items().await[0].pending);
    let snapshot = watchlist.refresh().await.unwrap();
    assert!(!snapshot.offline);
    assert!(!snapshot.items[0].pending);
    // Rien ne repart au rafraîchissement suivant.
    watchlist.refresh().await.unwrap();
}

#[tokio::test]
async fn a_server_failure_keeps_the_change_queued() {
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    Mock::given(method("PUT"))
        .and(path("/v1/watchlist/movie/603"))
        .respond_with(failure(503, "provider_unavailable"))
        .expect(1)
        .mount(&server)
        .await;
    // La liste n'est pas relue tant que la file n'est pas vide.
    Mock::given(method("GET"))
        .and(path("/v1/watchlist"))
        .respond_with(list(vec![]))
        .expect(0)
        .mount(&server)
        .await;

    let watchlist = Watchlist::new(account, None);
    let snapshot = watchlist
        .add(title(MediaType::Movie, 603, "Matrix"), None)
        .await
        .unwrap();
    assert!(snapshot.offline);
    assert!(snapshot.items[0].pending);
}

#[tokio::test]
async fn a_change_refused_by_the_server_is_dropped() {
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    Mock::given(method("PUT"))
        .and(path("/v1/watchlist/movie/999"))
        .respond_with(failure(404, "not_found"))
        .expect(1)
        .mount(&server)
        .await;
    mount_list(&server, vec![]).await;

    let watchlist = Watchlist::new(account, None);
    assert!(matches!(
        watchlist
            .add(title(MediaType::Movie, 999, "Inconnu"), None)
            .await,
        Err(WatchlistError::Rejected)
    ));
    assert!(watchlist.items().await.is_empty());
    // La file est vide : le rafraîchissement ne renvoie rien.
    assert!(watchlist.refresh().await.unwrap().items.is_empty());
}

#[tokio::test]
async fn an_unexpected_answer_keeps_the_change_for_later() {
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    // Réponse d'un intermédiaire (limitation), pas un refus du Worker : une
    // fois, puis le Worker répond.
    Mock::given(method("PUT"))
        .and(path("/v1/watchlist/movie/603"))
        .respond_with(ResponseTemplate::new(429).set_body_string("<html>trop de requêtes</html>"))
        .up_to_n_times(1)
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("PUT"))
        .and(path("/v1/watchlist/movie/604"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "item": wire_item("movie", 604, "Matrix Reloaded", "requested", json!(null))
        })))
        .expect(1)
        .mount(&server)
        .await;
    mount_list(
        &server,
        vec![wire_item(
            "movie",
            604,
            "Matrix Reloaded",
            "requested",
            json!(null),
        )],
    )
    .await;

    let watchlist = Watchlist::new(account, None);
    let snapshot = watchlist
        .add(title(MediaType::Movie, 603, "Matrix"), None)
        .await
        .unwrap();
    // L'ajout n'est pas perdu : il attend, comme hors ligne.
    assert!(snapshot.offline);
    assert!(snapshot.items.iter().any(|i| i.tmdb_id == 603 && i.pending));

    // Il ne bloque pas le changement suivant.
    let snapshot = watchlist
        .add(title(MediaType::Movie, 604, "Matrix Reloaded"), None)
        .await
        .unwrap();
    assert!(
        snapshot
            .items
            .iter()
            .any(|i| i.tmdb_id == 604 && !i.pending)
    );
    assert!(snapshot.items.iter().any(|i| i.tmdb_id == 603 && i.pending));
}

#[tokio::test]
async fn removing_a_title_deletes_it_on_the_server() {
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    let matrix = wire_item("movie", 603, "Matrix", "available", json!(null));
    let list_mock = Mock::given(method("GET"))
        .and(path("/v1/watchlist"))
        .respond_with(list(vec![matrix]))
        .up_to_n_times(1)
        .mount_as_scoped(&server)
        .await;
    let watchlist = Watchlist::new(account, None);
    let snapshot = watchlist.refresh().await.unwrap();
    assert_eq!(snapshot.items[0].status, RequestStatus::Available);
    drop(list_mock);

    Mock::given(method("DELETE"))
        .and(path("/v1/watchlist/movie/603"))
        .and(header("authorization", "Bearer acces-1"))
        .respond_with(ResponseTemplate::new(204))
        .expect(1)
        .mount(&server)
        .await;
    mount_list(&server, vec![]).await;
    let snapshot = watchlist.remove(MediaType::Movie, 603).await.unwrap();
    assert!(snapshot.items.is_empty());
    assert!(watchlist.entry(MediaType::Movie, 603).await.is_none());
}

#[tokio::test]
async fn unreadable_entries_of_the_list_are_skipped() {
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    mount_list(
        &server,
        vec![
            wire_item("movie", 603, "Matrix", "requested", json!(null)),
            json!({ "media_type": "movie", "tmdb_id": "pas un nombre" }),
            wire_item("podcast", 7, "Autre", "requested", json!(null)),
        ],
    )
    .await;
    let watchlist = Watchlist::new(account, None);
    let snapshot = watchlist.refresh().await.unwrap();
    assert_eq!(snapshot.items.len(), 1);
}

#[tokio::test]
async fn without_a_session_the_watchlist_is_empty_and_signed_out() {
    let store = Arc::new(MemoryStore::default());
    let account = Account::new(unreachable_uri(), store).unwrap();
    let watchlist = Watchlist::new(account, None);
    assert!(watchlist.items().await.is_empty());
    assert!(matches!(
        watchlist.refresh().await,
        Err(WatchlistError::SignedOut)
    ));
    assert!(matches!(
        watchlist
            .add(title(MediaType::Movie, 603, "Matrix"), None)
            .await,
        Err(WatchlistError::SignedOut)
    ));
}

#[tokio::test]
async fn an_expired_session_stops_the_sync() {
    let workspace = Workspace::new();
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    Mock::given(method("PUT"))
        .and(path("/v1/watchlist/movie/603"))
        .respond_with(failure(401, "unauthorized"))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/refresh"))
        .respond_with(failure(401, "session_expired"))
        .mount(&server)
        .await;
    let watchlist = Watchlist::new(account, Some(workspace.store_file()));
    assert!(matches!(
        watchlist
            .add(title(MediaType::Movie, 603, "Matrix"), None)
            .await,
        Err(WatchlistError::SignedOut)
    ));
}
