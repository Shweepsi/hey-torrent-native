//! Espace d'administration face à des serveurs simulés (wiremock) : un Worker,
//! des sources Torznab et un qBittorrent v4 ou v5. Jamais de vrai service.

#![cfg(feature = "admin")]
// Fichier de test : les assistants hors `#[test]` ont droit à `unwrap`.
#![allow(clippy::unwrap_used)]

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use heywatchlist_core::{
    AccessStatus, Account, AdminError, AdminSpace, AssistantStatus, AudioLanguage, DownloadState,
    IdentityProvider, MediaType, PlexSettings, PlexStatus, QbitAuth, QbitSettings, Release,
    RequestFilter, RequestStatus, SearchObserver, SearchQuery, ServerSettings, SessionStore,
    SignInRequest, SourceFailure, SourceReport, TorznabSource,
};
use serde_json::json;
use wiremock::matchers::{body_string_contains, header, method, path, query_param};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

const MOVIE_FEED: &str = include_str!("fixtures/torznab_movie.xml");
const OTHER_FEED: &str = include_str!("fixtures/torznab_other.xml");
const REQUEST_ID: &str = "11111111-1111-4111-8111-111111111111";
const HASH: &str = "1111111111111111111111111111111111111111";

// ---------------------------------------------------------------- assistants

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

/// Observateur qui garde chaque avancement.
#[derive(Default)]
struct Recorder(Mutex<Vec<(usize, Vec<SourceReport>, u32)>>);

impl SearchObserver for Recorder {
    fn on_progress(&self, releases: Vec<Release>, reports: Vec<SourceReport>, pending: u32) {
        self.0
            .lock()
            .unwrap()
            .push((releases.len(), reports, pending));
    }
}

/// Compte administrateur connecté au Worker simulé (jeton `acces-admin`).
async fn admin_account(worker: &MockServer) -> Arc<Account> {
    Mock::given(method("POST"))
        .and(path("/v1/auth/sign-in"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "access_token": "acces-admin",
            "access_expires_in": 900,
            "refresh_token": "refresh-admin",
            "user": { "id": "admin", "role": "admin", "display_name": "Loïc", "providers": ["google"] },
        })))
        .up_to_n_times(1)
        .mount(worker)
        .await;
    let account = Account::new(worker.uri(), Arc::new(MemoryStore::default())).unwrap();
    account
        .sign_in(SignInRequest {
            provider: IdentityProvider::Google,
            id_token: "jeton".into(),
            nonce: "nonce".into(),
            given_name: None,
        })
        .await
        .unwrap();
    account
}

fn source(id: &str, name: &str, server: &MockServer, prefix: &str) -> TorznabSource {
    TorznabSource {
        id: id.into(),
        name: name.into(),
        url: format!("{}/{prefix}/api", server.uri()),
        api_key: format!("cle-{id}"),
        enabled: true,
    }
}

fn qbit_settings(server: &MockServer, auth: QbitAuth) -> QbitSettings {
    QbitSettings {
        url: server.uri(),
        auth,
    }
}

fn password() -> QbitAuth {
    QbitAuth::Password {
        username: "admin".into(),
        password: "mot-de-passe".into(),
    }
}

/// Espace avec ces réglages déjà dans le coffre local (sans passer par le Worker).
fn space(account: Arc<Account>, settings: &ServerSettings) -> (Arc<AdminSpace>, Arc<MemoryStore>) {
    let store = Arc::new(MemoryStore::default());
    store.save(serde_json::to_string(&json!({
        "sources": settings.sources.iter().map(|s| json!({
            "id": s.id, "name": s.name, "url": s.url, "api_key": s.api_key, "enabled": s.enabled
        })).collect::<Vec<_>>(),
        "qbittorrent": settings.qbittorrent.as_ref().map(|q| json!({
            "url": q.url,
            "auth": match &q.auth {
                QbitAuth::Password { username, password } => json!({ "kind": "password", "username": username, "password": password }),
                QbitAuth::Bearer { token } => json!({ "kind": "bearer", "token": token }),
            },
        })),
        "movie_category": settings.movie_category,
        "series_category": settings.series_category,
        "excluded_terms": settings.excluded_terms,
    })).unwrap());
    (AdminSpace::new(account, store.clone()).unwrap(), store)
}

fn dune() -> SearchQuery {
    SearchQuery {
        media_type: MediaType::Movie,
        title: "Dune : Deuxième partie".into(),
        original_title: Some("Dune: Part Two".into()),
        year: Some(2024),
        season: None,
    }
}

fn feed(body: &str) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_raw(body, "application/rss+xml")
}

/// qBittorrent simulé : connexion par mot de passe, version donnée.
async fn mount_qbit(server: &MockServer, version: &str) {
    Mock::given(method("POST"))
        .and(path("/api/v2/auth/login"))
        .and(body_string_contains("username=admin"))
        .and(body_string_contains("password=mot-de-passe"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("set-cookie", "SID=session-qbit; HttpOnly; path=/")
                .set_body_string("Ok."),
        )
        .mount(server)
        .await;
    Mock::given(method("GET"))
        .and(path("/api/v2/app/version"))
        .and(header("cookie", "SID=session-qbit"))
        .respond_with(ResponseTemplate::new(200).set_body_string(version))
        .mount(server)
        .await;
    Mock::given(method("POST"))
        .and(path("/api/v2/torrents/createCategory"))
        .respond_with(ResponseTemplate::new(409))
        .mount(server)
        .await;
}

fn worker_request(status: &str) -> serde_json::Value {
    json!({
        "id": REQUEST_ID, "media_type": "movie", "tmdb_id": 693134, "title": "Dune : Deuxième partie",
        "poster_path": "/dune.jpg", "year": 2024, "status": status, "refusal_reason": null,
        "release_title": null, "torrent_hash": null, "created_at": 1, "updated_at": 2, "handled_at": null,
        "followers": [{ "display_name": "Camille", "seasons": null, "added_at": 1 }, { "pas": "lisible" }],
        "seasons": null,
    })
}

// ---------------------------------------------------------------- recherche

#[tokio::test]
async fn search_runs_sources_in_parallel_and_reports_failures() {
    let worker = MockServer::start().await;
    let indexers = MockServer::start().await;
    // Source A : le flux complet ; deux textes cherchés (titre et titre original).
    Mock::given(method("GET"))
        .and(path("/a/api"))
        .and(query_param("t", "movie"))
        .and(query_param("cat", "2000"))
        .and(query_param("apikey", "cle-a"))
        .respond_with(feed(MOVIE_FEED))
        .mount(&indexers)
        .await;
    // Source B : la même release que A (moins de sources), et une release morte.
    Mock::given(method("GET"))
        .and(path("/b/api"))
        .respond_with(feed(OTHER_FEED))
        .mount(&indexers)
        .await;
    // Source C : clé refusée.
    Mock::given(method("GET"))
        .and(path("/c/api"))
        .respond_with(feed(
            r#"<?xml version="1.0"?><error code="100" description="Incorrect user credentials"/>"#,
        ))
        .mount(&indexers)
        .await;
    // Source D : trop lente.
    Mock::given(method("GET"))
        .and(path("/d/api"))
        .respond_with(feed(OTHER_FEED).set_delay(Duration::from_secs(9)))
        .mount(&indexers)
        .await;

    let settings = ServerSettings {
        sources: vec![
            source("a", "Prowlarr maison", &indexers, "a"),
            source("b", "Deuxième", &indexers, "b"),
            source("c", "Clé périmée", &indexers, "c"),
            source("d", "Lente", &indexers, "d"),
            TorznabSource {
                enabled: false,
                ..source("e", "Éteinte", &indexers, "e")
            },
        ],
        ..ServerSettings::default()
    };
    let (space, _) = space(admin_account(&worker).await, &settings);
    let recorder = Arc::new(Recorder::default());
    let started = Instant::now();
    let outcome = space.search(dune(), Some(recorder.clone())).await.unwrap();
    let elapsed = started.elapsed();

    // La source lente est abandonnée au bout de 6 s, sans attendre ses 9 s.
    assert!(
        elapsed >= Duration::from_secs(6) && elapsed < Duration::from_secs(8),
        "{elapsed:?}"
    );
    let titles: Vec<&str> = outcome.releases.iter().map(|r| r.title.as_str()).collect();
    assert_eq!(
        titles,
        [
            "Dune.Deuxieme.Partie.2024.MULTi.VFF.1080p.BluRay.x265-GRP",
            "Dune.Deuxieme.Partie.2024.FRENCH.720p.WEB.H264-GRP",
            "Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.HDR.H265-GRP",
            "Dune.Deuxieme.Partie.2024.TRUEFRENCH.2160p.BluRay.REMUX.HEVC-GRP",
        ]
    );
    // Doublon fusionné : la version la plus partagée (source A) est gardée.
    let best = &outcome.releases[0];
    assert_eq!(best.seeders, Some(120));
    assert_eq!(best.source_name, "Prowlarr maison");
    assert_eq!(best.indexer.as_deref(), Some("Indexeur A"));
    assert_eq!(best.size_bytes, Some(12_884_901_888));
    assert_eq!(best.published_at, Some(1_718_035_200));
    assert_eq!(best.info.language, AudioLanguage::MultiVff);

    let failures: Vec<(String, Option<SourceFailure>)> = outcome
        .reports
        .iter()
        .map(|r| (r.source_name.clone(), r.failure))
        .collect();
    assert_eq!(failures.len(), 4, "la source éteinte n'est pas interrogée");
    assert!(failures.contains(&("Clé périmée".into(), Some(SourceFailure::AuthFailed))));
    assert!(failures.contains(&("Lente".into(), Some(SourceFailure::Timeout))));
    assert!(failures.contains(&("Prowlarr maison".into(), None)));

    // Progression : une notification par source, la lente en dernier.
    let progress = recorder.0.lock().unwrap();
    assert_eq!(
        progress.iter().map(|p| p.2).collect::<Vec<_>>(),
        [3, 2, 1, 0]
    );
    assert_eq!(
        progress.last().unwrap().1.last().unwrap().source_name,
        "Lente"
    );
    assert!(
        progress[2].0 >= 3,
        "les résultats arrivent avant la source lente"
    );

    // Jamais de lien ni de clé dans ce que voit l'app.
    let shown = format!("{:?}", outcome);
    assert!(
        !shown.contains("CLE-SECRETE")
            && !shown.contains("indexer.invalid/download")
            && !shown.contains("magnet:")
    );
}

#[tokio::test]
async fn search_falls_back_to_generic_search_and_excludes_terms() {
    let worker = MockServer::start().await;
    let indexers = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/a/api"))
        .and(query_param("t", "tvsearch"))
        .and(query_param("season", "2"))
        .respond_with(feed(
            r#"<error code="203" description="Function Not Available"/>"#,
        ))
        .mount(&indexers)
        .await;
    Mock::given(method("GET"))
        .and(path("/a/api"))
        .and(query_param("t", "search"))
        .and(query_param("q", "Severance"))
        .respond_with(feed(
            r#"<rss><channel>
                <item><title>Severance.S02.MULTi.1080p.WEB.H264-GRP</title><link>https://x.invalid/1</link></item>
                <item><title>Severance.S02.MULTi.1080p.WEB.H264.HC-GRP</title><link>https://x.invalid/2</link></item>
                <item><title>Severance.S02E03.MULTi.1080p.WEB.H264-GRP</title><link>https://x.invalid/3</link></item>
                <item><title>Severance.S01.MULTi.1080p.WEB.H264-GRP</title><link>https://x.invalid/4</link></item>
            </channel></rss>"#,
        ))
        .mount(&indexers)
        .await;
    let settings = ServerSettings {
        sources: vec![source("a", "Maison", &indexers, "a")],
        excluded_terms: vec!["hc".into()],
        ..ServerSettings::default()
    };
    let (space, _) = space(admin_account(&worker).await, &settings);
    let query = SearchQuery {
        media_type: MediaType::Tv,
        title: "Severance".into(),
        original_title: None,
        year: Some(2022),
        season: Some(2),
    };
    let outcome = space.search(query, None).await.unwrap();
    let titles: Vec<&str> = outcome.releases.iter().map(|r| r.title.as_str()).collect();
    assert_eq!(titles, ["Severance.S02.MULTi.1080p.WEB.H264-GRP"]);
}

#[tokio::test]
async fn search_needs_an_enabled_source() {
    let worker = MockServer::start().await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());
    assert!(matches!(
        space.search(dune(), None).await,
        Err(AdminError::NotConfigured)
    ));
}

#[tokio::test]
async fn source_tests_report_their_failure() {
    let worker = MockServer::start().await;
    let indexers = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ok/api"))
        .and(query_param("t", "caps"))
        .respond_with(feed("<caps><searching/></caps>"))
        .mount(&indexers)
        .await;
    Mock::given(method("GET"))
        .and(path("/bad/api"))
        .respond_with(ResponseTemplate::new(401))
        .mount(&indexers)
        .await;
    Mock::given(method("GET"))
        .and(path("/html/api"))
        .respond_with(
            ResponseTemplate::new(200).set_body_string("<html><body>Connexion</body></html>"),
        )
        .mount(&indexers)
        .await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());
    assert_eq!(
        space.test_source(source("ok", "Ok", &indexers, "ok")).await,
        None
    );
    assert_eq!(
        space
            .test_source(source("bad", "Ko", &indexers, "bad"))
            .await,
        Some(SourceFailure::AuthFailed)
    );
    assert_eq!(
        space
            .test_source(source("html", "Page", &indexers, "html"))
            .await,
        Some(SourceFailure::InvalidResponse)
    );
    let insecure = TorznabSource {
        url: "http://indexer.example.com/api".into(),
        ..source("x", "X", &indexers, "x")
    };
    assert_eq!(
        space.test_source(insecure).await,
        Some(SourceFailure::InvalidAddress)
    );
}

// ---------------------------------------------------------------- qBittorrent

#[tokio::test]
async fn qbittorrent_connection_tests() {
    let worker = MockServer::start().await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());

    let v5 = MockServer::start().await;
    mount_qbit(&v5, "v5.0.2").await;
    assert_eq!(
        space
            .test_qbittorrent(qbit_settings(&v5, password()))
            .await
            .unwrap(),
        "5.0"
    );

    let refused = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/v2/auth/login"))
        .respond_with(ResponseTemplate::new(200).set_body_string("Fails."))
        .mount(&refused)
        .await;
    assert!(matches!(
        space
            .test_qbittorrent(qbit_settings(&refused, password()))
            .await,
        Err(AdminError::QbitAuthFailed)
    ));

    let banned = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/v2/auth/login"))
        .respond_with(ResponseTemplate::new(403))
        .mount(&banned)
        .await;
    assert!(matches!(
        space
            .test_qbittorrent(qbit_settings(&banned, password()))
            .await,
        Err(AdminError::QbitBanned)
    ));

    let old = MockServer::start().await;
    mount_qbit(&old, "v4.0.4").await;
    assert!(matches!(
        space
            .test_qbittorrent(qbit_settings(&old, password()))
            .await,
        Err(AdminError::QbitUnsupportedVersion)
    ));

    let bearer = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v2/app/version"))
        .and(header("authorization", "Bearer jeton-proxy"))
        .respond_with(ResponseTemplate::new(200).set_body_string("v4.6.7"))
        .mount(&bearer)
        .await;
    let auth = QbitAuth::Bearer {
        token: "jeton-proxy".into(),
    };
    assert_eq!(
        space
            .test_qbittorrent(qbit_settings(&bearer, auth))
            .await
            .unwrap(),
        "4.6"
    );
    let wrong = QbitAuth::Bearer {
        token: "autre".into(),
    };
    assert!(
        space
            .test_qbittorrent(qbit_settings(&bearer, wrong))
            .await
            .is_err()
    );

    let insecure = QbitSettings {
        url: "http://qbit.example.com".into(),
        auth: password(),
    };
    assert!(matches!(
        space.test_qbittorrent(insecure).await,
        Err(AdminError::InvalidSettings)
    ));
}

/// Recherche, envoi de la meilleure release, état « en cours » sur le Worker.
#[tokio::test]
async fn a_request_is_processed_end_to_end() {
    let worker = MockServer::start().await;
    let indexers = MockServer::start().await;
    let qbit = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/a/api"))
        .respond_with(feed(MOVIE_FEED))
        .mount(&indexers)
        .await;
    mount_qbit(&qbit, "v4.6.7").await;
    Mock::given(method("POST"))
        .and(path("/api/v2/torrents/add"))
        .and(header("cookie", "SID=session-qbit"))
        .and(body_string_contains(format!("magnet:?xt=urn:btih:{HASH}")))
        .and(body_string_contains("Films 4K"))
        .and(body_string_contains(format!(
            "heywatchlist,hw-{REQUEST_ID}"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_string("Ok."))
        .expect(1)
        .mount(&qbit)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/admin/requests"))
        .and(header("authorization", "Bearer acces-admin"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "requests": [worker_request("requested"), { "id": "illisible" }]
        })))
        .mount(&worker)
        .await;
    let started = {
        let mut body = worker_request("in_progress");
        body["release_title"] = json!("Dune.Deuxieme.Partie.2024.MULTi.VFF.1080p.BluRay.x265-GRP");
        body
    };
    Mock::given(method("POST"))
        .and(path(format!("/v1/admin/requests/{REQUEST_ID}/start")))
        .and(body_string_contains(
            "\"release_title\":\"Dune.Deuxieme.Partie.2024.MULTi.VFF.1080p.BluRay.x265-GRP\"",
        ))
        .and(body_string_contains(format!("\"torrent_hash\":\"{HASH}\"")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "request": started })))
        .expect(1)
        .mount(&worker)
        .await;

    let settings = ServerSettings {
        sources: vec![source("a", "Maison", &indexers, "a")],
        qbittorrent: Some(qbit_settings(&qbit, password())),
        ..ServerSettings::default()
    };
    let (space, _) = space(admin_account(&worker).await, &settings);

    let queue = space.requests(RequestFilter::Open).await.unwrap();
    assert_eq!(queue.len(), 1, "une demande illisible est ignorée");
    let request = &queue[0];
    assert_eq!(request.followers.len(), 1);
    assert_eq!(
        request.poster_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w342/dune.jpg")
    );

    let outcome = space.search(dune(), None).await.unwrap();
    let best = outcome.releases[0].clone();
    let updated = space
        .send_release(request.id.clone(), best.id, "Films 4K".into())
        .await
        .unwrap();
    assert_eq!(updated.status, RequestStatus::InProgress);
    assert_eq!(updated.release_title.as_deref(), Some(best.title.as_str()));

    // Une catégorie vide ou piégée est refusée avant tout envoi.
    assert!(matches!(
        space
            .send_release(request.id.clone(), "inconnue".into(), " ".into())
            .await,
        Err(AdminError::InvalidSettings)
    ));

    // Une release d'une recherche oubliée ne part pas.
    assert!(matches!(
        space
            .send_release(request.id.clone(), "inconnue".into(), "films".into())
            .await,
        Err(AdminError::ReleaseExpired)
    ));
}

#[tokio::test]
async fn a_torrent_sent_but_not_recorded_can_be_confirmed_later() {
    let worker = MockServer::start().await;
    let indexers = MockServer::start().await;
    let qbit = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/a/api"))
        .respond_with(feed(MOVIE_FEED))
        .mount(&indexers)
        .await;
    mount_qbit(&qbit, "v5.1.0").await;
    Mock::given(method("POST"))
        .and(path("/api/v2/torrents/add"))
        .respond_with(ResponseTemplate::new(200).set_body_string("Ok."))
        .expect(1)
        .mount(&qbit)
        .await;
    Mock::given(method("POST"))
        .and(path(format!("/v1/admin/requests/{REQUEST_ID}/start")))
        .respond_with(ResponseTemplate::new(503).set_body_json(json!({ "error": "internal" })))
        .up_to_n_times(1)
        .mount(&worker)
        .await;
    Mock::given(method("POST"))
        .and(path(format!("/v1/admin/requests/{REQUEST_ID}/start")))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "request": worker_request("in_progress") })),
        )
        .mount(&worker)
        .await;
    let settings = ServerSettings {
        sources: vec![source("a", "Maison", &indexers, "a")],
        qbittorrent: Some(qbit_settings(&qbit, password())),
        ..ServerSettings::default()
    };
    let (space, _) = space(admin_account(&worker).await, &settings);
    let release = space.search(dune(), None).await.unwrap().releases[0]
        .id
        .clone();
    assert!(matches!(
        space
            .send_release(REQUEST_ID.into(), release.clone(), "films".into())
            .await,
        Err(AdminError::StatusNotSaved)
    ));
    // Le torrent n'est pas renvoyé (`expect(1)` sur l'ajout).
    let request = space
        .confirm_release(REQUEST_ID.into(), release)
        .await
        .unwrap();
    assert_eq!(request.status, RequestStatus::InProgress);
}

#[tokio::test]
async fn a_rejected_torrent_leaves_the_request_untouched() {
    let worker = MockServer::start().await;
    let indexers = MockServer::start().await;
    let qbit = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/a/api"))
        .respond_with(feed(MOVIE_FEED))
        .mount(&indexers)
        .await;
    mount_qbit(&qbit, "v4.6.7").await;
    Mock::given(method("POST"))
        .and(path("/api/v2/torrents/add"))
        .respond_with(ResponseTemplate::new(200).set_body_string("Fails."))
        .mount(&qbit)
        .await;
    Mock::given(method("POST"))
        .and(path(format!("/v1/admin/requests/{REQUEST_ID}/start")))
        .respond_with(ResponseTemplate::new(200))
        .expect(0)
        .mount(&worker)
        .await;
    let settings = ServerSettings {
        sources: vec![source("a", "Maison", &indexers, "a")],
        qbittorrent: Some(qbit_settings(&qbit, password())),
        ..ServerSettings::default()
    };
    let (space, _) = space(admin_account(&worker).await, &settings);
    let release = space.search(dune(), None).await.unwrap().releases[0]
        .id
        .clone();
    assert!(matches!(
        space
            .send_release(REQUEST_ID.into(), release, "films".into())
            .await,
        Err(AdminError::QbitRejected)
    ));
}

#[tokio::test]
async fn downloads_are_listed_and_controlled_with_the_routes_of_each_version() {
    for (version, pause, resume) in [
        (
            "v4.6.7",
            "/api/v2/torrents/pause",
            "/api/v2/torrents/resume",
        ),
        ("v5.0.2", "/api/v2/torrents/stop", "/api/v2/torrents/start"),
    ] {
        let worker = MockServer::start().await;
        let qbit = MockServer::start().await;
        mount_qbit(&qbit, version).await;
        Mock::given(method("GET"))
            .and(path("/api/v2/torrents/info"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!([
                { "hash": HASH, "name": "Dune", "category": "films", "state": "downloading", "progress": 0.42,
                  "size": 1000, "dlspeed": 2048, "eta": 120, "tags": format!("heywatchlist, hw-{REQUEST_ID}"), "added_on": 20 },
                { "hash": "2".repeat(40), "name": "Série", "category": "series", "state": "stoppedUP", "progress": 1.0,
                  "size": 10, "dlspeed": 0, "eta": 8640000, "tags": "", "added_on": 30 },
                { "hash": "3".repeat(40), "name": "Linux", "category": "iso", "state": "uploading", "progress": 1.0, "added_on": 40 },
                { "hash": "4".repeat(40), "name": "Concert", "category": "musique", "state": "downloading", "tags": "heywatchlist", "added_on": 10 },
                { "hash": "pas-une-empreinte", "name": "Abîmé", "category": "films" },
                { "name": "Sans empreinte" }
            ])))
            .mount(&qbit)
            .await;
        Mock::given(method("POST"))
            .and(path(pause))
            .and(body_string_contains(format!("hashes={HASH}")))
            .respond_with(ResponseTemplate::new(200))
            .expect(1)
            .mount(&qbit)
            .await;
        Mock::given(method("POST"))
            .and(path(resume))
            .respond_with(ResponseTemplate::new(200))
            .expect(1)
            .mount(&qbit)
            .await;
        Mock::given(method("POST"))
            .and(path("/api/v2/torrents/delete"))
            .and(body_string_contains("deleteFiles=true"))
            .respond_with(ResponseTemplate::new(200))
            .expect(1)
            .mount(&qbit)
            .await;
        let settings = ServerSettings {
            qbittorrent: Some(qbit_settings(&qbit, password())),
            ..ServerSettings::default()
        };
        let (space, _) = space(admin_account(&worker).await, &settings);

        let downloads = space.downloads().await.unwrap();
        assert_eq!(
            downloads.len(),
            3,
            "{version} : autres catégories sans l'étiquette de l'app et entrées illisibles écartées"
        );
        assert_eq!(downloads[2].name, "Concert");
        assert_eq!(downloads[0].name, "Série");
        assert_eq!(downloads[0].state, DownloadState::Completed);
        assert_eq!(downloads[0].eta_seconds, None);
        assert_eq!(downloads[1].request_id.as_deref(), Some(REQUEST_ID));
        assert_eq!(downloads[1].eta_seconds, Some(120));
        assert!((downloads[1].progress - 0.42).abs() < f64::EPSILON);

        space.pause_download(HASH.into()).await.unwrap();
        space.resume_download(HASH.into()).await.unwrap();
        space.delete_download(HASH.into(), true).await.unwrap();
        assert!(matches!(
            space.pause_download("all".into()).await,
            Err(AdminError::TorrentNotFound)
        ));
    }
}

#[tokio::test]
async fn existing_categories_are_listed_sorted() {
    let worker = MockServer::start().await;
    let qbit = MockServer::start().await;
    mount_qbit(&qbit, "v5.0.2").await;
    Mock::given(method("GET"))
        .and(path("/api/v2/torrents/categories"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "series": { "name": "series", "savePath": "/data/series" },
            "Animation": { "name": "Animation", "savePath": "" },
            "films": { "name": "films", "savePath": "/data/films" },
            "": { "name": "", "savePath": "" }
        })))
        .mount(&qbit)
        .await;
    let settings = ServerSettings {
        qbittorrent: Some(qbit_settings(&qbit, password())),
        ..ServerSettings::default()
    };
    let (space, _) = space(admin_account(&worker).await, &settings);
    assert_eq!(
        space.qbit_categories().await.unwrap(),
        vec!["Animation", "films", "series"]
    );
}

#[tokio::test]
async fn categories_need_qbittorrent_settings() {
    let worker = MockServer::start().await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());
    assert!(matches!(
        space.qbit_categories().await,
        Err(AdminError::NotConfigured)
    ));
}

#[tokio::test]
async fn a_forgotten_qbittorrent_session_is_reopened_once() {
    let worker = MockServer::start().await;
    let qbit = MockServer::start().await;
    mount_qbit(&qbit, "v4.6.7").await;
    Mock::given(method("GET"))
        .and(path("/api/v2/torrents/info"))
        .respond_with(ResponseTemplate::new(403))
        .up_to_n_times(1)
        .mount(&qbit)
        .await;
    Mock::given(method("GET"))
        .and(path("/api/v2/torrents/info"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([])))
        .mount(&qbit)
        .await;
    let settings = ServerSettings {
        qbittorrent: Some(qbit_settings(&qbit, password())),
        ..ServerSettings::default()
    };
    let (space, _) = space(admin_account(&worker).await, &settings);
    assert!(space.downloads().await.unwrap().is_empty());
    let logins = qbit
        .received_requests()
        .await
        .unwrap()
        .iter()
        .filter(|r: &&Request| r.url.path() == "/api/v2/auth/login")
        .count();
    assert_eq!(logins, 2);
}

#[tokio::test]
async fn downloads_need_qbittorrent_settings() {
    let worker = MockServer::start().await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());
    assert!(matches!(
        space.downloads().await,
        Err(AdminError::NotConfigured)
    ));
}

// ---------------------------------------------------------------- Worker

#[tokio::test]
async fn settings_are_saved_on_the_worker_and_synced_back() {
    let worker = MockServer::start().await;
    let account = admin_account(&worker).await;
    let saved = Arc::new(Mutex::new(None::<String>));
    let sink = saved.clone();
    Mock::given(method("PUT"))
        .and(path("/v1/admin/settings"))
        .respond_with(move |request: &Request| {
            let body: serde_json::Value = serde_json::from_slice(&request.body).unwrap();
            *sink.lock().unwrap() = body["settings"].as_str().map(str::to_owned);
            ResponseTemplate::new(200).set_body_json(json!({ "updated_at": 1 }))
        })
        .mount(&worker)
        .await;
    let (first, store) = space(account.clone(), &ServerSettings::default());

    // Réglages invalides : refusés avant tout appel.
    let invalid = ServerSettings {
        sources: vec![TorznabSource {
            id: "a".into(),
            name: "Maison".into(),
            url: "http://indexer.example.com/api".into(),
            api_key: "cle".into(),
            enabled: true,
        }],
        ..ServerSettings::default()
    };
    assert!(matches!(
        first.save_settings(invalid).await,
        Err(AdminError::InvalidSettings)
    ));
    assert!(saved.lock().unwrap().is_none());

    let settings = ServerSettings {
        sources: vec![TorznabSource {
            id: "a".into(),
            name: "Maison".into(),
            url: "https://indexer.example.com/1/api".into(),
            api_key: "cle".into(),
            enabled: true,
        }],
        qbittorrent: Some(QbitSettings {
            url: "https://qbit.example.com".into(),
            auth: password(),
        }),
        movie_category: "Films".into(),
        ..ServerSettings::default()
    };
    first.save_settings(settings.clone()).await.unwrap();
    assert!(
        store.load().unwrap().contains("indexer.example.com"),
        "copie locale gardée"
    );
    assert_eq!(first.settings().await, settings);

    // Nouvel appareil : coffre vide, réglages relus sur le Worker.
    let stored = saved.lock().unwrap().clone().unwrap();
    Mock::given(method("GET"))
        .and(path("/v1/admin/settings"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "settings": stored, "updated_at": 1 })),
        )
        .mount(&worker)
        .await;
    let fresh_store = Arc::new(MemoryStore::default());
    let second = AdminSpace::new(account, fresh_store.clone()).unwrap();
    assert_eq!(second.settings().await, ServerSettings::default());
    assert_eq!(second.sync_settings().await.unwrap(), settings);
    assert!(fresh_store.load().is_some());
}

#[tokio::test]
async fn an_unreadable_local_copy_is_forgotten() {
    let worker = MockServer::start().await;
    let store = Arc::new(MemoryStore::default());
    store.save("pas du json".into());
    let space = AdminSpace::new(admin_account(&worker).await, store.clone()).unwrap();
    assert_eq!(space.settings().await, ServerSettings::default());
    assert!(store.load().is_none());
}

#[tokio::test]
async fn worker_refusals_are_typed() {
    let worker = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path(format!("/v1/admin/requests/{REQUEST_ID}/refuse")))
        .and(body_string_contains("\"reason\":\"Pas en français\""))
        .respond_with(ResponseTemplate::new(409).set_body_json(json!({ "error": "conflict" })))
        .mount(&worker)
        .await;
    Mock::given(method("POST"))
        .and(path(format!("/v1/admin/requests/{REQUEST_ID}/reopen")))
        .respond_with(ResponseTemplate::new(403).set_body_json(json!({ "error": "forbidden" })))
        .mount(&worker)
        .await;

    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());
    assert!(matches!(
        space
            .refuse(REQUEST_ID.into(), Some(" Pas en français ".into()))
            .await,
        Err(AdminError::Conflict)
    ));
    assert!(matches!(
        space.reopen(REQUEST_ID.into()).await,
        Err(AdminError::Forbidden)
    ));
    assert!(matches!(
        space.reopen("../settings".into()).await,
        Err(AdminError::NotFound)
    ));
}

#[tokio::test]
async fn a_request_already_on_plex_is_marked_available() {
    let worker = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path(format!("/v1/admin/requests/{REQUEST_ID}/available")))
        .and(header("authorization", "Bearer acces-admin"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "request": worker_request("available") })),
        )
        .expect(1)
        .mount(&worker)
        .await;

    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());
    let request = space.mark_available(REQUEST_ID.into()).await.unwrap();
    assert_eq!(request.status, RequestStatus::Available);
    assert!(matches!(
        space.mark_available("../settings".into()).await,
        Err(AdminError::NotFound)
    ));
}

const MEMBER_ID: &str = "33333333-3333-4333-8333-333333333333";

#[tokio::test]
async fn members_are_listed_approved_and_refused() {
    let worker = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/admin/members"))
        .and(header("authorization", "Bearer acces-admin"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "members": [
                { "id": MEMBER_ID, "role": "member", "display_name": "Lina", "status": "pending",
                  "email": "lina@example.com", "created_at": 10, "status_changed_at": null },
                { "id": "admin", "role": "admin", "display_name": "Loïc", "status": "active",
                  "email": null, "created_at": 1, "status_changed_at": 1 },
                { "id": "x", "status": "banni" }
            ]})),
        )
        .mount(&worker)
        .await;
    Mock::given(method("POST"))
        .and(path(format!("/v1/admin/members/{MEMBER_ID}/approve")))
        .respond_with(ResponseTemplate::new(204))
        .expect(1)
        .mount(&worker)
        .await;
    Mock::given(method("POST"))
        .and(path(format!("/v1/admin/members/{MEMBER_ID}/refuse")))
        .respond_with(ResponseTemplate::new(409).set_body_json(json!({ "error": "conflict" })))
        .mount(&worker)
        .await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());

    let members = space.members().await.unwrap();
    assert_eq!(members.len(), 2);
    assert_eq!(members[0].access, AccessStatus::Pending);
    assert_eq!(members[0].email.as_deref(), Some("lina@example.com"));
    assert!(members[1].is_admin);

    space.approve_member(MEMBER_ID.into()).await.unwrap();
    assert!(matches!(
        space.refuse_member(MEMBER_ID.into()).await,
        Err(AdminError::Conflict)
    ));
    assert!(matches!(
        space.refuse_member("../settings".into()).await,
        Err(AdminError::NotFound)
    ));
}

#[tokio::test]
async fn emails_can_be_allowed_in_advance() {
    let worker = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/admin/allowed-emails"))
        .and(body_string_contains("\"email\":\"demo@example.com\""))
        .respond_with(
            ResponseTemplate::new(201)
                .set_body_json(json!({ "email": "demo@example.com", "approved": 1 })),
        )
        .mount(&worker)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/admin/allowed-emails"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "emails": [
            { "email": "demo@example.com", "created_at": 5 }, { "email": 7 }
        ]})))
        .mount(&worker)
        .await;
    Mock::given(method("DELETE"))
        .and(path("/v1/admin/allowed-emails"))
        .and(body_string_contains("demo@example.com"))
        .respond_with(ResponseTemplate::new(204))
        .expect(1)
        .mount(&worker)
        .await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());

    assert_eq!(
        space
            .allow_email(" demo@example.com ".into())
            .await
            .unwrap(),
        1
    );
    assert!(matches!(
        space.allow_email("pas une adresse".into()).await,
        Err(AdminError::InvalidSettings)
    ));
    let emails = space.allowed_emails().await.unwrap();
    assert_eq!(emails.len(), 1);
    assert_eq!(emails[0].email, "demo@example.com");
    space
        .remove_allowed_email("demo@example.com".into())
        .await
        .unwrap();
}

// ------------------------------------------------- Claude, recherche auto, Plex

#[tokio::test]
async fn claude_recommends_a_release_without_seeing_links() {
    let worker = MockServer::start().await;
    let indexers = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/a/api"))
        .respond_with(feed(MOVIE_FEED))
        .mount(&indexers)
        .await;
    let settings = ServerSettings {
        sources: vec![source("a", "Maison", &indexers, "a")],
        ..ServerSettings::default()
    };
    let (space, _) = space(admin_account(&worker).await, &settings);
    let outcome = space.search(dune(), None).await.unwrap();
    let best = outcome.releases[0].id.clone();
    Mock::given(method("POST"))
        .and(path("/v1/admin/recommend"))
        .and(header("authorization", "Bearer acces-admin"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(
                json!({ "release_id": best, "reason": " MULTi en 4K, sous 15 Go. " }),
            ),
        )
        .expect(1)
        .mount(&worker)
        .await;

    let advice = space
        .recommend(dune(), outcome.releases.clone())
        .await
        .unwrap();
    assert_eq!(advice.release_id.as_deref(), Some(best.as_str()));
    assert_eq!(advice.reason, "MULTi en 4K, sous 15 Go.");

    let sent = worker
        .received_requests()
        .await
        .unwrap()
        .into_iter()
        .find(|r| r.url.path() == "/v1/admin/recommend")
        .unwrap();
    let body: serde_json::Value = serde_json::from_slice(&sent.body).unwrap();
    assert_eq!(body["media_type"], "movie");
    assert_eq!(body["original_title"], "Dune: Part Two");
    assert_eq!(
        body["releases"].as_array().unwrap().len(),
        outcome.releases.len()
    );
    assert_eq!(
        body["releases"][0]["title"],
        outcome.releases[0].title.as_str()
    );
    // Ni lien ni clé de source ne partent vers le Worker.
    let raw = String::from_utf8(sent.body).unwrap();
    assert!(
        !raw.contains("magnet:")
            && !raw.contains("CLE-SECRETE")
            && !raw.contains("indexer.invalid")
    );

    // Sans release, le Worker n'est pas appelé.
    let empty = space.recommend(dune(), Vec::new()).await.unwrap();
    assert_eq!(empty.release_id, None);
}

#[tokio::test]
async fn claude_refusals_are_typed() {
    let worker = MockServer::start().await;
    let indexers = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/a/api"))
        .respond_with(feed(MOVIE_FEED))
        .mount(&indexers)
        .await;
    let settings = ServerSettings {
        sources: vec![source("a", "Maison", &indexers, "a")],
        ..ServerSettings::default()
    };
    let (space, _) = space(admin_account(&worker).await, &settings);
    let releases = space.search(dune(), None).await.unwrap().releases;
    for (status, code, expected) in [
        (
            409,
            "assistant_not_configured",
            AdminError::AssistantNotConfigured,
        ),
        (
            502,
            "assistant_auth_failed",
            AdminError::AssistantAuthFailed,
        ),
        (
            503,
            "assistant_unavailable",
            AdminError::AssistantUnavailable,
        ),
    ] {
        worker.reset().await;
        Mock::given(method("POST"))
            .and(path("/v1/admin/recommend"))
            .respond_with(ResponseTemplate::new(status).set_body_json(json!({ "error": code })))
            .mount(&worker)
            .await;
        let error = space.recommend(dune(), releases.clone()).await.unwrap_err();
        assert_eq!(
            std::mem::discriminant(&error),
            std::mem::discriminant(&expected)
        );
    }
    // Un identifiant qui n'est pas dans la liste ne met rien en avant.
    worker.reset().await;
    Mock::given(method("POST"))
        .and(path("/v1/admin/recommend"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "release_id": "inventee", "reason": "?" })),
        )
        .mount(&worker)
        .await;
    assert!(matches!(
        space.recommend(dune(), releases).await,
        Err(AdminError::Unexpected)
    ));
}

#[tokio::test]
async fn automation_status_is_read_tolerantly() {
    let worker = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/admin/automation"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "searched_at": 100,
            "sources": [
                { "id": "a", "failure": null },
                { "id": "b", "failure": "rate_limited" },
                { "id": "c", "failure": "nouvelle-raison" },
                { "pas": "lisible" },
            ],
            "assistant_status": "auth_failed",
            "plex_synced_at": 90,
            "plex_status": "ok",
            "plex_items": 412,
        })))
        .mount(&worker)
        .await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());
    let status = space.automation_status().await.unwrap();
    assert_eq!(status.searched_at, Some(100));
    let failures: Vec<_> = status
        .sources
        .iter()
        .map(|s| (s.source_id.as_str(), s.failure))
        .collect();
    assert_eq!(
        failures,
        [
            ("a", None),
            ("b", Some(SourceFailure::RateLimited)),
            ("c", Some(SourceFailure::InvalidResponse)),
        ]
    );
    assert_eq!(status.assistant, Some(AssistantStatus::AuthFailed));
    assert_eq!(status.plex, Some(PlexStatus::Ok));
    assert_eq!(status.plex_items, Some(412));
}

#[tokio::test]
async fn plex_is_tested_from_the_worker() {
    let worker = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/admin/plex/test"))
        .and(body_string_contains("jeton-plex"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "sections": 2 })))
        .up_to_n_times(1)
        .mount(&worker)
        .await;
    Mock::given(method("POST"))
        .and(path("/v1/admin/plex/test"))
        .respond_with(
            ResponseTemplate::new(502).set_body_json(json!({ "error": "plex_auth_failed" })),
        )
        .mount(&worker)
        .await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());
    let plex = PlexSettings {
        url: "https://plex.invalid".into(),
        token: "jeton-plex".into(),
    };
    assert_eq!(space.test_plex(plex.clone()).await.unwrap(), 2);
    assert!(matches!(
        space.test_plex(plex.clone()).await,
        Err(AdminError::PlexAuthFailed)
    ));
    // Adresse non HTTPS : refusée sans appel.
    let insecure = PlexSettings {
        url: "http://plex.invalid".into(),
        ..plex
    };
    assert!(matches!(
        space.test_plex(insecure).await,
        Err(AdminError::InvalidSettings)
    ));
}

#[tokio::test]
async fn a_release_found_automatically_comes_with_the_request() {
    let worker = MockServer::start().await;
    let mut found = worker_request("requested");
    found["candidate_title"] = json!("Dune.Part.Two.2024.MULTi.2160p");
    found["candidate_reason"] = json!("MULTi en 4K.");
    found["candidate_found_at"] = json!(50);
    found["auto_checked_at"] = json!(50);
    Mock::given(method("GET"))
        .and(path("/v1/admin/requests"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "requests": [found, worker_request("requested")] })),
        )
        .mount(&worker)
        .await;
    let (space, _) = space(admin_account(&worker).await, &ServerSettings::default());
    let requests = space.requests(RequestFilter::Open).await.unwrap();
    assert_eq!(
        requests[0].candidate_title.as_deref(),
        Some("Dune.Part.Two.2024.MULTi.2160p")
    );
    assert_eq!(
        requests[0].candidate_reason.as_deref(),
        Some("MULTi en 4K.")
    );
    assert_eq!(requests[0].candidate_found_at, Some(50));
    assert_eq!(requests[1].candidate_title, None);
}
