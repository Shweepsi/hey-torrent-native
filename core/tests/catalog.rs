//! Catalogue face à un Worker simulé (wiremock) : jamais le vrai relais, jamais
//! TMDB. Les réponses de `fixtures/` sont inventées mais ont la forme des
//! réponses réelles de TMDB.

// Fichier de test : les assistants hors `#[test]` ont droit à `unwrap`.
#![allow(clippy::unwrap_used)]

use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use heywatchlist_core::{
    Account, Catalog, CatalogError, IdentityProvider, MediaType, SessionStore, Shelf,
    SignInRequest, TitlePage, VideoKind,
};
use rusqlite::Connection;
use serde_json::json;
use wiremock::matchers::{any, header, method, path, query_param};
use wiremock::{Mock, MockBuilder, MockServer, ResponseTemplate};

const TRENDING: &str = include_str!("fixtures/trending_all_week.json");
const TV_POPULAR: &str = include_str!("fixtures/tv_popular.json");
const GENRES: &str = include_str!("fixtures/genres_movie.json");
const SEARCH: &str = include_str!("fixtures/search_multi.json");
const MOVIE_DETAIL: &str = include_str!("fixtures/movie_detail.json");
const TV_DETAIL: &str = include_str!("fixtures/tv_detail.json");
const TV_SEASON: &str = include_str!("fixtures/tv_season.json");

/// Chemin du cache, avec la clé de la première page des tendances.
const TRENDING_KEY: &str = "/v1/tmdb/trending/all/week?page=1";

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
            "catalog-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&directory).unwrap();
        Self(directory)
    }

    fn cache_file(&self) -> String {
        self.0.join("cache.sqlite").to_str().unwrap().to_owned()
    }

    fn directory(&self) -> String {
        self.0.to_str().unwrap().to_owned()
    }

    /// Connexion directe au fichier du cache, pour l'inspecter ou le vieillir.
    fn database(&self) -> Connection {
        Connection::open(self.cache_file()).unwrap()
    }

    fn entries(&self) -> i64 {
        self.database()
            .query_row("SELECT COUNT(*) FROM responses", [], |row| row.get(0))
            .unwrap()
    }

    fn body_of(&self, key: &str) -> Option<String> {
        self.database()
            .query_row(
                "SELECT CAST(body AS TEXT) FROM responses WHERE cache_key = ?1",
                [key],
                |row| row.get(0),
            )
            .ok()
    }

    /// Fait vieillir toutes les entrées de `seconds`.
    fn age_entries(&self, seconds: i64) {
        self.database()
            .execute(
                "UPDATE responses SET fetched_at = fetched_at - ?1",
                [seconds],
            )
            .unwrap();
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

fn session_body(access: &str, refresh: &str) -> serde_json::Value {
    json!({
        "access_token": access,
        "access_expires_in": 900,
        "refresh_token": refresh,
        "user": { "id": "u-1", "role": "member", "display_name": "Camille", "providers": ["apple"] },
    })
}

/// Compte connecté au serveur simulé (jeton d'accès `acces-1`), et son coffre.
async fn signed_in(server: &MockServer) -> (Arc<Account>, Arc<MemoryStore>) {
    Mock::given(method("POST"))
        .and(path("/v1/auth/sign-in"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(session_body("acces-1", "refresh-1")),
        )
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

/// Même session, mais le serveur ne répond plus (le jeton d'accès, gardé en
/// mémoire seulement, est à renouveler : l'échec de transport est immédiat).
fn offline_copy(store: &Arc<MemoryStore>) -> Arc<Account> {
    Account::new(unreachable_uri(), store.clone()).unwrap()
}

/// Un `GET /v1/tmdb/<route>` avec le jeton d'accès de `signed_in`.
fn route(route: &str) -> MockBuilder {
    Mock::given(method("GET"))
        .and(path(format!("/v1/tmdb/{route}")))
        .and(header("authorization", "Bearer acces-1"))
}

fn ok(body: &str) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_raw(body, "application/json")
}

fn failure(status: u16, code: &str) -> ResponseTemplate {
    ResponseTemplate::new(status).set_body_json(json!({ "error": code }))
}

fn ids(page: &TitlePage) -> Vec<u64> {
    page.items.iter().map(|item| item.id).collect()
}

/// Corps d'une liste d'un seul titre, pour les tests qui ne regardent pas le détail.
fn one_title_page(page: u32) -> String {
    json!({
        "page": page,
        "results": [{ "id": 1, "title": "Titre", "name": "Titre", "media_type": "movie" }],
        "total_pages": 10
    })
    .to_string()
}

/// Serveur simulé, compte connecté, catalogue sans cache.
async fn setup() -> (MockServer, Arc<Catalog>) {
    let server = MockServer::start().await;
    let (account, _) = signed_in(&server).await;
    (server, Catalog::new(account, None))
}

// ------------------------------------------------------------------- listes

#[tokio::test]
async fn trending_is_read_with_the_session_and_unreadable_entries_are_skipped() {
    let (server, catalog) = setup().await;
    route("trending/all/week")
        .and(query_param("page", "1"))
        .respond_with(ok(TRENDING))
        .expect(1)
        .mount(&server)
        .await;

    let page = catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    assert_eq!((page.page, page.total_pages, page.stale), (1, 500, false));
    // Personne, entrées sans id, sans titre, au type inattendu : écartées.
    assert_eq!(ids(&page), vec![910001, 920002, 910004]);

    let movie = page.items.first().unwrap();
    assert_eq!(movie.media_type, MediaType::Movie);
    assert_eq!(movie.title, "Le Phare des brumes");
    assert_eq!(movie.year, Some(2026));
    assert_eq!(movie.release_date.as_deref(), Some("2026-10-14"));
    assert_eq!(movie.genre_ids, vec![18, 53]);
    assert_eq!(movie.vote_count, 1280);
    assert!((movie.rating - 7.412).abs() < 1e-9);
    assert_eq!(
        movie.poster_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w342/affichePhareBrumes01.jpg")
    );
    assert_eq!(
        movie.backdrop_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w780/fondPhareBrumes01.jpg")
    );

    let series = page.items.get(1).unwrap();
    assert_eq!(series.media_type, MediaType::Tv);
    assert_eq!(series.title, "L'Île aux marées");
    assert_eq!(series.year, Some(2026));

    // Tout est optionnel sauf l'identifiant et le titre.
    let bare = page.items.get(2).unwrap();
    assert_eq!((bare.year, bare.release_date.clone()), (None, None));
    assert_eq!(
        (bare.poster_url.clone(), bare.backdrop_url.clone()),
        (None, None)
    );
    assert_eq!(bare.overview, "");
}

#[tokio::test]
async fn every_shelf_calls_its_own_route_with_the_page() {
    let (server, catalog) = setup().await;
    let shelves = [
        (Shelf::TrendingWeek, "trending/all/week", MediaType::Movie),
        (Shelf::PopularMovies, "movie/popular", MediaType::Movie),
        (
            Shelf::NowPlayingMovies,
            "movie/now_playing",
            MediaType::Movie,
        ),
        (Shelf::UpcomingMovies, "movie/upcoming", MediaType::Movie),
        (Shelf::TopRatedMovies, "movie/top_rated", MediaType::Movie),
        (Shelf::PopularSeries, "tv/popular", MediaType::Tv),
        (Shelf::OnTheAirSeries, "tv/on_the_air", MediaType::Tv),
        (Shelf::TopRatedSeries, "tv/top_rated", MediaType::Tv),
    ];
    for (_, relayed, _) in shelves {
        route(relayed)
            .and(query_param("page", "3"))
            .respond_with(ok(&one_title_page(3)))
            .expect(1)
            .mount(&server)
            .await;
    }
    for (shelf, relayed, media_type) in shelves {
        let page = catalog.shelf(shelf, 3).await.unwrap();
        assert_eq!(page.page, 3, "{relayed}");
        let item = page.items.first().unwrap();
        // Une liste de séries donne des séries, même sans `media_type` ; les
        // tendances gardent le type de chaque titre (ici `movie`).
        assert_eq!(item.media_type, media_type, "{relayed}");
    }
}

#[tokio::test]
async fn series_lists_use_names_and_first_air_dates() {
    let (server, catalog) = setup().await;
    route("tv/popular")
        .and(query_param("page", "2"))
        .respond_with(ok(TV_POPULAR))
        .mount(&server)
        .await;
    let page = catalog.shelf(Shelf::PopularSeries, 2).await.unwrap();
    assert_eq!((page.page, page.total_pages), (2, 44));
    // La dernière entrée n'a pas de nom.
    assert_eq!(ids(&page), vec![920101, 920102]);
    let first = page.items.first().unwrap();
    assert_eq!(first.media_type, MediaType::Tv);
    assert_eq!(first.title, "L'Orbite lente");
    assert_eq!(first.year, Some(2025));
}

#[tokio::test]
async fn page_zero_is_the_first_page_and_pages_past_the_last_are_empty_without_a_request() {
    let (server, catalog) = setup().await;
    route("movie/popular")
        .and(query_param("page", "1"))
        .respond_with(ok(&one_title_page(1)))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/tmdb/movie/popular"))
        .and(query_param("page", "501"))
        .respond_with(failure(400, "invalid_request"))
        .expect(0)
        .mount(&server)
        .await;
    assert_eq!(
        catalog.shelf(Shelf::PopularMovies, 0).await.unwrap().page,
        1
    );
    let beyond = catalog.shelf(Shelf::PopularMovies, 501).await.unwrap();
    assert!(beyond.items.is_empty());
    assert_eq!(
        (beyond.page, beyond.total_pages, beyond.stale),
        (501, 500, false)
    );
}

#[tokio::test]
async fn genres_are_listed_for_movies_and_series() {
    let (server, catalog) = setup().await;
    route("genre/movie/list")
        .respond_with(ok(GENRES))
        .expect(1)
        .mount(&server)
        .await;
    route("genre/tv/list")
        .respond_with(ok(
            r#"{"genres":[{"id":10759,"name":"Action et aventure"}]}"#,
        ))
        .expect(1)
        .mount(&server)
        .await;
    let movies = catalog.genres(MediaType::Movie).await.unwrap();
    let names: Vec<&str> = movies.iter().map(|genre| genre.name.as_str()).collect();
    assert_eq!(
        names,
        vec!["Action", "Aventure", "Animation", "Comédie", "Drame"]
    );
    assert_eq!(movies.first().unwrap().id, 28);
    let series = catalog.genres(MediaType::Tv).await.unwrap();
    assert_eq!(series.first().unwrap().id, 10759);
}

#[tokio::test]
async fn a_genre_is_browsed_by_popularity() {
    let (server, catalog) = setup().await;
    route("discover/movie")
        .and(query_param("with_genres", "28"))
        .and(query_param("sort_by", "popularity.desc"))
        .and(query_param("page", "2"))
        .respond_with(ok(&one_title_page(2)))
        .expect(1)
        .mount(&server)
        .await;
    route("discover/tv")
        .and(query_param("with_genres", "18"))
        .and(query_param("sort_by", "popularity.desc"))
        .and(query_param("page", "1"))
        .respond_with(ok(&one_title_page(1)))
        .expect(1)
        .mount(&server)
        .await;
    let movies = catalog.by_genre(MediaType::Movie, 28, 2).await.unwrap();
    assert_eq!(movies.items.first().unwrap().media_type, MediaType::Movie);
    let series = catalog.by_genre(MediaType::Tv, 18, 1).await.unwrap();
    assert_eq!(series.items.first().unwrap().media_type, MediaType::Tv);
}

/// Valeurs de `with_genres` reçues par `discover/<kind>`, dans l'ordre.
async fn requested_genres(server: &MockServer, kind: &str) -> Vec<String> {
    let discover = format!("/v1/tmdb/discover/{kind}");
    server
        .received_requests()
        .await
        .unwrap()
        .iter()
        .filter(|request| request.url.path() == discover)
        .filter_map(|request| {
            request
                .url
                .query_pairs()
                .find(|(key, _)| key == "with_genres")
                .map(|(_, value)| value.into_owned())
        })
        .collect()
}

#[tokio::test]
async fn a_movie_genre_is_translated_for_series() {
    let (server, catalog) = setup().await;
    route("discover/tv")
        .respond_with(ok(&one_title_page(1)))
        .mount(&server)
        .await;
    // (genre de film reçu, genre de série demandé à TMDB)
    let table = [
        (28, 10759),
        (12, 10759),
        (14, 10765),
        (878, 10765),
        (10752, 10768),
        (16, 16),
        (35, 35),
        (80, 80),
        (99, 99),
        (18, 18),
        (10751, 10751),
        (9648, 9648),
        (37, 37),
    ];
    for (movie_genre, _) in table {
        let page = catalog
            .by_genre(MediaType::Tv, movie_genre, 1)
            .await
            .unwrap();
        assert_eq!(
            page.items.first().unwrap().media_type,
            MediaType::Tv,
            "{movie_genre}"
        );
    }
    let expected: Vec<String> = table.iter().map(|(_, tv)| tv.to_string()).collect();
    assert_eq!(requested_genres(&server, "tv").await, expected);
}

#[tokio::test]
async fn a_movie_genre_without_a_series_equivalent_gives_an_empty_page_without_a_request() {
    let (server, catalog) = setup().await;
    Mock::given(method("GET"))
        .and(any())
        .respond_with(failure(500, "internal"))
        .expect(0)
        .mount(&server)
        .await;
    // Horreur, Musique, Romance, Thriller, Histoire, Téléfilm ; puis des
    // identifiants qui ne sont pas des genres de film (dont un genre de série).
    for genre in [27, 10402, 10749, 53, 36, 10770, 0, 10759] {
        for (asked, expected) in [(1, 1), (3, 3), (0, 1)] {
            let page = catalog.by_genre(MediaType::Tv, genre, asked).await.unwrap();
            assert!(page.items.is_empty(), "{genre}");
            assert_eq!(
                (page.page, page.total_pages, page.stale),
                (expected, 0, false),
                "{genre} page {asked}"
            );
        }
    }
}

#[tokio::test]
async fn a_movie_genre_is_never_translated_for_movies() {
    let (server, catalog) = setup().await;
    route("discover/movie")
        .respond_with(ok(&one_title_page(1)))
        .mount(&server)
        .await;
    // Même les genres sans équivalent série, et un identifiant de série, sont
    // transmis tels quels.
    let genres = [
        28, 12, 14, 878, 10752, 27, 10402, 10749, 53, 36, 10770, 10759,
    ];
    for genre in genres {
        let page = catalog.by_genre(MediaType::Movie, genre, 1).await.unwrap();
        assert_eq!(page.items.len(), 1, "{genre}");
    }
    let expected: Vec<String> = genres.iter().map(u32::to_string).collect();
    assert_eq!(requested_genres(&server, "movie").await, expected);
}

// ----------------------------------------------------------------- recherche

#[tokio::test]
async fn search_encodes_the_query_and_keeps_only_movies_and_series() {
    let (server, catalog) = setup().await;
    route("search/multi")
        .and(query_param("query", "lac & forêt"))
        .and(query_param("page", "1"))
        .respond_with(ok(SEARCH))
        .expect(1)
        .mount(&server)
        .await;
    // Les blancs de bord sont retirés.
    let page = catalog.search("  lac & forêt ".into(), 1).await.unwrap();
    // La personne et le film sans titre sont écartés.
    assert_eq!(ids(&page), vec![940001, 950002]);
    assert_eq!(page.total_pages, 3);
    assert_eq!(page.items.first().unwrap().media_type, MediaType::Movie);
    assert_eq!(page.items.get(1).unwrap().media_type, MediaType::Tv);
    assert!(!page.stale);
}

#[tokio::test]
async fn an_empty_search_gives_an_empty_page_without_a_request() {
    let (server, catalog) = setup().await;
    Mock::given(method("GET"))
        .and(any())
        .respond_with(failure(500, "internal"))
        .expect(0)
        .mount(&server)
        .await;
    for query in ["", "   ", "\n\t"] {
        let page = catalog.search(query.into(), 1).await.unwrap();
        assert!(page.items.is_empty(), "{query:?}");
        assert_eq!((page.page, page.total_pages, page.stale), (1, 1, false));
    }
}

#[tokio::test]
async fn a_long_search_is_cut_to_what_the_worker_accepts() {
    let (server, catalog) = setup().await;
    route("search/multi")
        .and(query_param("query", "é".repeat(100)))
        .respond_with(ok(SEARCH))
        .expect(1)
        .mount(&server)
        .await;
    catalog.search("é".repeat(150), 1).await.unwrap();
}

#[tokio::test]
async fn searches_are_never_cached_nor_read_from_the_cache() {
    let workspace = Workspace::new();
    let (server, store, catalog) = cached_setup(&workspace).await;
    route("trending/all/week")
        .respond_with(ok(TRENDING))
        .expect(1)
        .mount(&server)
        .await;
    route("search/multi")
        .respond_with(ok(SEARCH))
        .expect(2)
        .mount(&server)
        .await;
    // Un premier appel crée le cache ; une entrée de recherche toute fraîche
    // y est posée à la main.
    catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    workspace
        .database()
        .execute(
            "INSERT INTO responses (cache_key, body, fetched_at)
             VALUES ('/v1/tmdb/search/multi?page=1&query=lac', CAST(?1 AS BLOB), strftime('%s','now'))",
            [SEARCH],
        )
        .unwrap();

    // Chaque recherche va au réseau, et n'enregistre rien.
    catalog.search("lac".into(), 1).await.unwrap();
    catalog.search("lac".into(), 1).await.unwrap();
    assert_eq!(workspace.entries(), 2);

    // Hors ligne, la recherche échoue au lieu de servir le cache.
    let offline = Catalog::new(offline_copy(&store), Some(workspace.cache_file()));
    assert!(matches!(
        offline.search("lac".into(), 1).await,
        Err(CatalogError::Network)
    ));
}

// -------------------------------------------------------------------- fiches

#[tokio::test]
async fn a_movie_detail_is_complete() {
    let (server, catalog) = setup().await;
    route("movie/940001")
        .respond_with(ok(MOVIE_DETAIL))
        .expect(1)
        .mount(&server)
        .await;
    let detail = catalog.detail(MediaType::Movie, 940001).await.unwrap();

    let summary = &detail.summary;
    assert_eq!((summary.id, summary.media_type), (940001, MediaType::Movie));
    assert_eq!(summary.title, "La Maison du lac");
    assert_eq!(summary.year, Some(2019));
    assert_eq!(summary.genre_ids, vec![18, 53]);
    assert_eq!(
        summary.poster_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w500/afficheRecherche01.jpg")
    );
    assert_eq!(
        summary.backdrop_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w1280/fondMaisonLac01.jpg")
    );
    assert_eq!(
        detail.tagline.as_deref(),
        Some("Certains étés ne finissent jamais.")
    );
    let genres: Vec<&str> = detail
        .genres
        .iter()
        .map(|genre| genre.name.as_str())
        .collect();
    assert_eq!(genres, vec!["Drame", "Thriller"]);
    assert_eq!(detail.runtime_minutes, Some(112));
    assert_eq!(detail.status.as_deref(), Some("Released"));
    assert_eq!((detail.seasons_count, detail.episodes_count), (None, None));
    assert!(detail.seasons.is_empty());
    assert!(!detail.stale);

    // Casting : l'entrée sans nom et celle sans identifiant sont écartées.
    assert_eq!(detail.cast.len(), 2);
    let lead = detail.cast.first().unwrap();
    assert_eq!((lead.id, lead.name.as_str()), (5001, "Camille Exemple"));
    assert_eq!(lead.character.as_deref(), Some("Léa"));
    assert_eq!(
        lead.profile_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w185/profilCamille.jpg")
    );
    let second = detail.cast.get(1).unwrap();
    assert_eq!(
        (second.character.clone(), second.profile_url.clone()),
        (None, None)
    );
    assert_eq!(detail.directors, vec!["Alix Réalisateur"]);

    // Vidéos : YouTube seulement, clé piégée écartée, bande-annonce d'abord.
    let videos: Vec<(&str, VideoKind)> = detail
        .videos
        .iter()
        .map(|video| (video.youtube_key.as_str(), video.kind))
        .collect();
    assert_eq!(
        videos,
        vec![
            ("trailer-Key_01", VideoKind::Trailer),
            ("teaserKey001", VideoKind::Teaser)
        ]
    );
    assert_eq!(
        detail.videos.first().unwrap().name,
        "Bande-annonce officielle"
    );

    // Similaires : de petites affiches, du même type ; sans titre : écarté.
    assert_eq!(detail.similar.len(), 1);
    let similar = detail.similar.first().unwrap();
    assert_eq!((similar.id, similar.media_type), (940010, MediaType::Movie));
    assert_eq!(
        similar.poster_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w342/afficheSimilaire1.jpg")
    );
}

#[tokio::test]
async fn a_series_detail_has_seasons_and_creators() {
    let (server, catalog) = setup().await;
    route("tv/920002")
        .respond_with(ok(TV_DETAIL))
        .expect(1)
        .mount(&server)
        .await;
    let detail = catalog.detail(MediaType::Tv, 920002).await.unwrap();
    assert_eq!(detail.summary.media_type, MediaType::Tv);
    assert_eq!(detail.summary.title, "L'Île aux marées");
    assert_eq!(detail.summary.year, Some(2026));
    // Durée d'un épisode : `episode_run_time` est vide, repli sur le dernier.
    assert_eq!(detail.runtime_minutes, Some(51));
    assert_eq!(
        (detail.seasons_count, detail.episodes_count),
        (Some(1), Some(6))
    );
    assert_eq!(detail.status.as_deref(), Some("Returning Series"));
    assert_eq!(detail.tagline, None);
    assert_eq!(detail.directors, vec!["Inès Créatrice"]);
    let numbers: Vec<u32> = detail.seasons.iter().map(|season| season.number).collect();
    // Les épisodes spéciaux (saison 0) ne sont pas proposés.
    assert_eq!(numbers, vec![1]);
    let season = detail.seasons.first().unwrap();
    assert_eq!(
        (season.name.as_str(), season.episode_count),
        ("Saison 1", 6)
    );
    assert_eq!(season.air_date.as_deref(), Some("2026-09-03"));
    assert_eq!(
        season.poster_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w342/afficheSaison1.jpg")
    );
    assert!(detail.videos.is_empty());
    assert_eq!(detail.similar.first().unwrap().media_type, MediaType::Tv);
}

#[tokio::test]
async fn a_detail_cuts_the_cast_to_twenty() {
    let (server, catalog) = setup().await;
    let mut root: serde_json::Value = serde_json::from_str(MOVIE_DETAIL).unwrap();
    root["credits"]["cast"] = (0..35)
        .map(|i| json!({ "id": 100 + i, "name": format!("Acteur {i}"), "order": i }))
        .collect();
    route("movie/940001")
        .respond_with(ok(&root.to_string()))
        .mount(&server)
        .await;
    let detail = catalog.detail(MediaType::Movie, 940001).await.unwrap();
    assert_eq!(detail.cast.len(), 20);
    assert_eq!(detail.cast.first().unwrap().name, "Acteur 0");
    assert_eq!(detail.cast.get(19).unwrap().name, "Acteur 19");
}

#[tokio::test]
async fn a_detail_without_a_title_is_unexpected() {
    let (server, catalog) = setup().await;
    route("movie/5")
        .respond_with(ok(r#"{"id": 5, "overview": "Sans titre."}"#))
        .mount(&server)
        .await;
    assert!(matches!(
        catalog.detail(MediaType::Movie, 5).await,
        Err(CatalogError::Unexpected)
    ));
}

#[tokio::test]
async fn a_season_lists_its_episodes() {
    let (server, catalog) = setup().await;
    route("tv/920002/season/1")
        .respond_with(ok(TV_SEASON))
        .expect(1)
        .mount(&server)
        .await;
    let season = catalog.season(920002, 1).await.unwrap();
    assert_eq!((season.number, season.name.as_str()), (1, "Saison 1"));
    assert_eq!(season.overview, "La première saison.");
    assert!(!season.stale);
    // Les deux entrées sans numéro lisible sont écartées.
    assert_eq!(season.episodes.len(), 2);
    let first = season.episodes.first().unwrap();
    assert_eq!((first.number, first.name.as_str()), (1, "Marée basse"));
    assert_eq!(first.runtime_minutes, Some(52));
    assert_eq!(first.air_date.as_deref(), Some("2026-09-03"));
    assert_eq!(
        first.still_url.as_deref(),
        Some("https://image.tmdb.org/t/p/w300/stillEpisode1.jpg")
    );
    assert!((first.rating - 7.9).abs() < 1e-9);
    let second = season.episodes.get(1).unwrap();
    assert_eq!(
        (second.runtime_minutes, second.still_url.clone()),
        (None, None)
    );
    assert_eq!(second.overview, "");
}

#[tokio::test]
async fn identifiers_the_worker_would_refuse_are_not_found_without_a_request() {
    let (server, catalog) = setup().await;
    Mock::given(method("GET"))
        .and(any())
        .respond_with(failure(400, "invalid_request"))
        .expect(0)
        .mount(&server)
        .await;
    assert!(matches!(
        catalog.detail(MediaType::Movie, 0).await,
        Err(CatalogError::NotFound)
    ));
    assert!(matches!(
        catalog.detail(MediaType::Tv, 10_000_000_000).await,
        Err(CatalogError::NotFound)
    ));
    assert!(matches!(
        catalog.season(0, 1).await,
        Err(CatalogError::NotFound)
    ));
    assert!(matches!(
        catalog.season(920002, 100).await,
        Err(CatalogError::NotFound)
    ));
}

// ------------------------------------------------------------------- erreurs

#[tokio::test]
async fn server_failures_map_to_catalog_errors() {
    let (server, catalog) = setup().await;
    route("movie/1")
        .respond_with(failure(404, "not_found"))
        .mount(&server)
        .await;
    route("movie/2")
        .respond_with(failure(503, "provider_unavailable"))
        .mount(&server)
        .await;
    route("movie/3")
        .respond_with(failure(500, "internal"))
        .mount(&server)
        .await;
    route("movie/4")
        .respond_with(ResponseTemplate::new(502).set_body_string("<html>"))
        .mount(&server)
        .await;
    route("movie/5")
        .respond_with(failure(400, "invalid_request"))
        .mount(&server)
        .await;
    route("movie/6")
        .respond_with(ResponseTemplate::new(200).set_body_string("<html>"))
        .mount(&server)
        .await;
    route("movie/7")
        .respond_with(ok(r#"{"partiel": true}"#))
        .mount(&server)
        .await;
    route("movie/8")
        .respond_with(ResponseTemplate::new(404).set_body_string("Not Found"))
        .mount(&server)
        .await;

    let detail = |id| catalog.detail(MediaType::Movie, id);
    assert!(matches!(detail(1).await, Err(CatalogError::NotFound)));
    assert!(matches!(detail(2).await, Err(CatalogError::Unavailable)));
    assert!(matches!(detail(3).await, Err(CatalogError::Server)));
    assert!(matches!(detail(4).await, Err(CatalogError::Server)));
    assert!(matches!(detail(5).await, Err(CatalogError::Unexpected)));
    assert!(matches!(detail(6).await, Err(CatalogError::Unexpected)));
    assert!(matches!(detail(7).await, Err(CatalogError::Unexpected)));
    assert!(matches!(detail(8).await, Err(CatalogError::Unexpected)));
}

#[tokio::test]
async fn lists_and_genres_with_an_unreadable_body_are_unexpected() {
    let (server, catalog) = setup().await;
    route("movie/popular")
        .respond_with(ok(r#"{"page": 1}"#))
        .mount(&server)
        .await;
    route("genre/tv/list")
        .respond_with(ok("[]"))
        .mount(&server)
        .await;
    route("tv/9/season/1")
        .respond_with(ok("{}"))
        .mount(&server)
        .await;
    assert!(matches!(
        catalog.shelf(Shelf::PopularMovies, 1).await,
        Err(CatalogError::Unexpected)
    ));
    assert!(matches!(
        catalog.genres(MediaType::Tv).await,
        Err(CatalogError::Unexpected)
    ));
    assert!(matches!(
        catalog.season(9, 1).await,
        Err(CatalogError::Unexpected)
    ));
}

#[tokio::test]
async fn without_a_session_the_catalog_is_signed_out_and_sends_nothing() {
    let server = MockServer::start().await;
    Mock::given(any())
        .respond_with(failure(500, "internal"))
        .expect(0)
        .mount(&server)
        .await;
    let account = Account::new(server.uri(), Arc::new(MemoryStore::default())).unwrap();
    let catalog = Catalog::new(account, None);
    assert!(matches!(
        catalog.shelf(Shelf::TrendingWeek, 1).await,
        Err(CatalogError::SignedOut)
    ));
}

#[tokio::test]
async fn a_rejected_token_is_renewed_once_and_the_call_retried() {
    let (server, catalog) = setup().await;
    route("trending/all/week")
        .respond_with(failure(401, "unauthorized"))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/refresh"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "access_token": "acces-2", "access_expires_in": 900, "refresh_token": "refresh-2"
        })))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/tmdb/trending/all/week"))
        .and(header("authorization", "Bearer acces-2"))
        .respond_with(ok(TRENDING))
        .expect(1)
        .mount(&server)
        .await;
    let page = catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    assert_eq!(page.items.len(), 3);
}

#[tokio::test]
async fn a_revoked_session_is_signed_out() {
    let (server, catalog) = setup().await;
    route("trending/all/week")
        .respond_with(failure(401, "unauthorized"))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/refresh"))
        .respond_with(failure(401, "session_expired"))
        .expect(1)
        .mount(&server)
        .await;
    assert!(matches!(
        catalog.shelf(Shelf::TrendingWeek, 1).await,
        Err(CatalogError::SignedOut)
    ));
}

#[tokio::test]
async fn an_unreachable_server_is_a_network_error() {
    let server = MockServer::start().await;
    let (_, store) = signed_in(&server).await;
    let catalog = Catalog::new(offline_copy(&store), None);
    assert!(matches!(
        catalog.shelf(Shelf::TrendingWeek, 1).await,
        Err(CatalogError::Network)
    ));
    assert!(matches!(
        catalog.detail(MediaType::Movie, 1).await,
        Err(CatalogError::Network)
    ));
}

// --------------------------------------------------------------------- cache

/// Catalogue avec cache dans `workspace`, sur un serveur simulé.
async fn cached_setup(workspace: &Workspace) -> (MockServer, Arc<MemoryStore>, Arc<Catalog>) {
    let server = MockServer::start().await;
    let (account, store) = signed_in(&server).await;
    let catalog = Catalog::new(account, Some(workspace.cache_file()));
    (server, store, catalog)
}

#[tokio::test]
async fn a_fresh_entry_is_served_without_the_network_even_after_a_restart() {
    let workspace = Workspace::new();
    let (server, _, catalog) = cached_setup(&workspace).await;
    route("trending/all/week")
        .respond_with(ok(TRENDING))
        .expect(1)
        .mount(&server)
        .await;

    let first = catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    let second = catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    assert_eq!(first, second);
    assert!(!second.stale);
    assert_eq!(workspace.entries(), 1);
    assert_eq!(workspace.body_of(TRENDING_KEY).as_deref(), Some(TRENDING));

    // Nouveau lancement : même fichier, même réponse sans réseau.
    let (account, _) = signed_in(&server).await;
    let relaunched = Catalog::new(account, Some(workspace.cache_file()));
    assert_eq!(
        relaunched.shelf(Shelf::TrendingWeek, 1).await.unwrap(),
        first
    );
}

#[tokio::test]
async fn each_page_and_parameter_has_its_own_entry() {
    let workspace = Workspace::new();
    let (server, _, catalog) = cached_setup(&workspace).await;
    route("movie/popular")
        .respond_with(ok(&one_title_page(1)))
        .expect(2)
        .mount(&server)
        .await;
    route("discover/movie")
        .respond_with(ok(&one_title_page(1)))
        .expect(2)
        .mount(&server)
        .await;
    catalog.shelf(Shelf::PopularMovies, 1).await.unwrap();
    catalog.shelf(Shelf::PopularMovies, 2).await.unwrap();
    catalog.by_genre(MediaType::Movie, 28, 1).await.unwrap();
    catalog.by_genre(MediaType::Movie, 35, 1).await.unwrap();
    assert_eq!(workspace.entries(), 4);
    assert!(workspace.body_of("/v1/tmdb/movie/popular?page=2").is_some());
    assert!(
        workspace
            .body_of("/v1/tmdb/discover/movie?page=1&sort_by=popularity.desc&with_genres=35")
            .is_some()
    );
}

#[tokio::test]
async fn an_entry_older_than_five_minutes_is_refreshed_from_the_network() {
    let workspace = Workspace::new();
    let (server, _, catalog) = cached_setup(&workspace).await;
    route("trending/all/week")
        .respond_with(ok(&one_title_page(1)))
        .up_to_n_times(1)
        .expect(1)
        .mount(&server)
        .await;
    route("trending/all/week")
        .respond_with(ok(TRENDING))
        .expect(1)
        .mount(&server)
        .await;

    assert_eq!(
        catalog
            .shelf(Shelf::TrendingWeek, 1)
            .await
            .unwrap()
            .items
            .len(),
        1
    );
    workspace.age_entries(301);
    let refreshed = catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    assert_eq!(refreshed.items.len(), 3);
    assert!(!refreshed.stale);
    // L'entrée remplacée est neuve : un troisième appel ne va plus au réseau.
    assert_eq!(workspace.body_of(TRENDING_KEY).as_deref(), Some(TRENDING));
    assert_eq!(
        catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap(),
        refreshed
    );
}

#[tokio::test]
async fn a_stale_entry_is_served_when_the_service_or_the_server_fails() {
    for (status, code) in [
        (503, "provider_unavailable"),
        (500, "internal"),
        (502, "bad_gateway"),
    ] {
        let workspace = Workspace::new();
        let (server, _, catalog) = cached_setup(&workspace).await;
        route("trending/all/week")
            .respond_with(ok(TRENDING))
            .up_to_n_times(1)
            .mount(&server)
            .await;
        route("trending/all/week")
            .respond_with(failure(status, code))
            .mount(&server)
            .await;

        let fresh = catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
        workspace.age_entries(600);
        let stale = catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
        assert!(stale.stale, "{status}");
        assert_eq!(stale.items, fresh.items);
        // Le périmé n'est pas retouché : il reste périmé.
        assert_eq!(workspace.entries(), 1);
    }
}

#[tokio::test]
async fn a_stale_entry_is_served_without_network_and_the_flag_reaches_details_and_seasons() {
    let workspace = Workspace::new();
    let (server, store, catalog) = cached_setup(&workspace).await;
    route("movie/940001")
        .respond_with(ok(MOVIE_DETAIL))
        .expect(1)
        .mount(&server)
        .await;
    route("tv/920002/season/1")
        .respond_with(ok(TV_SEASON))
        .expect(1)
        .mount(&server)
        .await;
    route("genre/movie/list")
        .respond_with(ok(GENRES))
        .expect(1)
        .mount(&server)
        .await;
    let detail = catalog.detail(MediaType::Movie, 940001).await.unwrap();
    let season = catalog.season(920002, 1).await.unwrap();
    let genres = catalog.genres(MediaType::Movie).await.unwrap();
    workspace.age_entries(3_600);

    // Le serveur ne répond plus du tout.
    let offline = Catalog::new(offline_copy(&store), Some(workspace.cache_file()));
    let stale_detail = offline.detail(MediaType::Movie, 940001).await.unwrap();
    assert!(stale_detail.stale);
    assert_eq!(stale_detail.summary, detail.summary);
    assert_eq!(stale_detail.cast, detail.cast);
    let stale_season = offline.season(920002, 1).await.unwrap();
    assert!(stale_season.stale);
    assert_eq!(stale_season.episodes, season.episodes);
    assert_eq!(offline.genres(MediaType::Movie).await.unwrap(), genres);
}

#[tokio::test]
async fn without_a_cached_copy_a_failure_stays_an_error() {
    let workspace = Workspace::new();
    let (server, store, catalog) = cached_setup(&workspace).await;
    route("trending/all/week")
        .respond_with(failure(503, "provider_unavailable"))
        .mount(&server)
        .await;
    assert!(matches!(
        catalog.shelf(Shelf::TrendingWeek, 1).await,
        Err(CatalogError::Unavailable)
    ));
    let offline = Catalog::new(offline_copy(&store), Some(workspace.cache_file()));
    assert!(matches!(
        offline.shelf(Shelf::PopularMovies, 1).await,
        Err(CatalogError::Network)
    ));
}

#[tokio::test]
async fn other_failures_never_fall_back_to_the_cache() {
    let workspace = Workspace::new();
    let (server, _, catalog) = cached_setup(&workspace).await;
    route("trending/all/week")
        .respond_with(ok(TRENDING))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();

    // Introuvable : l'entrée en cache ne ressuscite pas un titre disparu.
    workspace.age_entries(600);
    route("trending/all/week")
        .respond_with(failure(404, "not_found"))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    assert!(matches!(
        catalog.shelf(Shelf::TrendingWeek, 1).await,
        Err(CatalogError::NotFound)
    ));

    // Réponse illisible : erreur, et le bon corps en cache est conservé.
    route("trending/all/week")
        .respond_with(ok(r#"{"autre": true}"#))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    assert!(matches!(
        catalog.shelf(Shelf::TrendingWeek, 1).await,
        Err(CatalogError::Unexpected)
    ));
    assert_eq!(workspace.body_of(TRENDING_KEY).as_deref(), Some(TRENDING));

    // Session expirée : se reconnecter, pas de contenu périmé.
    route("trending/all/week")
        .respond_with(failure(401, "unauthorized"))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/refresh"))
        .respond_with(failure(401, "session_expired"))
        .mount(&server)
        .await;
    assert!(matches!(
        catalog.shelf(Shelf::TrendingWeek, 1).await,
        Err(CatalogError::SignedOut)
    ));
}

#[tokio::test]
async fn an_unreadable_body_is_never_cached() {
    let workspace = Workspace::new();
    let (server, _, catalog) = cached_setup(&workspace).await;
    route("trending/all/week")
        .respond_with(ResponseTemplate::new(200).set_body_string("<html>maintenance</html>"))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    route("trending/all/week")
        .respond_with(ok(TRENDING))
        .mount(&server)
        .await;

    assert!(matches!(
        catalog.shelf(Shelf::TrendingWeek, 1).await,
        Err(CatalogError::Unexpected)
    ));
    assert_eq!(workspace.entries(), 0);
    // Le bon corps qui suit est, lui, enregistré.
    catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    assert_eq!(workspace.entries(), 1);
}

#[tokio::test]
async fn an_unreadable_cached_body_is_ignored() {
    let workspace = Workspace::new();
    let (server, store, catalog) = cached_setup(&workspace).await;
    route("trending/all/week")
        .respond_with(ok(TRENDING))
        .expect(2)
        .mount(&server)
        .await;
    catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    // Corps illisible en cache (ancienne version de l'app, corruption).
    workspace
        .database()
        .execute(
            "UPDATE responses SET body = CAST('pas du json' AS BLOB)",
            [],
        )
        .unwrap();

    // Frais mais illisible : le réseau est sollicité, le corps réparé.
    assert_eq!(
        catalog
            .shelf(Shelf::TrendingWeek, 1)
            .await
            .unwrap()
            .items
            .len(),
        3
    );
    assert_eq!(workspace.body_of(TRENDING_KEY).as_deref(), Some(TRENDING));

    // Périmé, illisible et hors ligne : l'erreur, pas du contenu cassé.
    workspace
        .database()
        .execute(
            "UPDATE responses SET body = CAST('pas du json' AS BLOB), fetched_at = 1",
            [],
        )
        .unwrap();
    let offline = Catalog::new(offline_copy(&store), Some(workspace.cache_file()));
    assert!(matches!(
        offline.shelf(Shelf::TrendingWeek, 1).await,
        Err(CatalogError::Network)
    ));
}

#[tokio::test]
async fn old_and_surplus_entries_are_pruned_when_the_cache_opens() {
    let workspace = Workspace::new();
    let (server, _, catalog) = cached_setup(&workspace).await;
    route("trending/all/week")
        .respond_with(ok(TRENDING))
        .expect(1)
        .mount(&server)
        .await;
    catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    drop(catalog);

    let thirty_one_days = 31 * 24 * 3600;
    {
        let database = workspace.database();
        let now: i64 = database
            .query_row("SELECT MAX(fetched_at) FROM responses", [], |row| {
                row.get(0)
            })
            .unwrap();
        database
            .execute(
                "INSERT INTO responses (cache_key, body, fetched_at) VALUES ('vieille', x'00', ?1)",
                [now - thirty_one_days],
            )
            .unwrap();
        // 450 entrées récentes, de plus en plus anciennes : k0 la plus récente.
        for i in 0..450_i64 {
            database
                .execute(
                    "INSERT INTO responses (cache_key, body, fetched_at) VALUES (?1, x'00', ?2)",
                    rusqlite::params![format!("k{i}"), now - 1 - i],
                )
                .unwrap();
        }
    }
    assert_eq!(workspace.entries(), 452);

    // Un nouveau lancement ouvre le cache et l'élague.
    let (account, _) = signed_in(&server).await;
    let relaunched = Catalog::new(account, Some(workspace.cache_file()));
    relaunched.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    assert_eq!(workspace.entries(), 400);
    assert!(workspace.body_of("vieille").is_none());
    assert!(workspace.body_of(TRENDING_KEY).is_some());
    assert!(workspace.body_of("k0").is_some());
    // Les plus anciennes au-delà du plafond sont parties.
    assert!(workspace.body_of("k449").is_none());
}

#[tokio::test]
async fn a_cache_that_cannot_open_is_never_fatal() {
    let workspace = Workspace::new();
    // Un dossier, un chemin sans parent, un chemin vide : aucun n'est une base.
    let unusable = [
        Some(workspace.directory()),
        Some(format!("{}/absent/cache.sqlite", workspace.directory())),
        Some(String::new()),
        None,
    ];
    for cache_path in unusable {
        let server = MockServer::start().await;
        let (account, _) = signed_in(&server).await;
        let catalog = Catalog::new(account, cache_path.clone());
        route("trending/all/week")
            .respond_with(ok(TRENDING))
            .expect(2)
            .mount(&server)
            .await;
        // Sans cache : chaque lecture va au réseau, et réussit.
        assert_eq!(
            catalog
                .shelf(Shelf::TrendingWeek, 1)
                .await
                .unwrap()
                .items
                .len(),
            3,
            "{cache_path:?}"
        );
        let again = catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
        assert!(!again.stale);
    }
    // Rien n'a été créé à la place du dossier.
    assert!(!PathBuf::from(workspace.directory()).join("absent").exists());
}

#[tokio::test]
async fn a_corrupted_cache_file_is_never_fatal() {
    let workspace = Workspace::new();
    fs::write(
        workspace.cache_file(),
        b"ceci n'est pas une base SQLite, loin de l'etre",
    )
    .unwrap();
    let (server, _, catalog) = cached_setup(&workspace).await;
    route("trending/all/week")
        .respond_with(ok(TRENDING))
        .expect(1)
        .mount(&server)
        .await;
    assert_eq!(
        catalog
            .shelf(Shelf::TrendingWeek, 1)
            .await
            .unwrap()
            .items
            .len(),
        3
    );
}

// ------------------------------------------------- langue d'origine (Découvrir)

/// Page de titres `(id, langue d'origine, genres)`.
fn page_with_languages(
    page: u32,
    total_pages: u32,
    titles: &[(u64, Option<&str>, &[u32])],
) -> String {
    let results: Vec<serde_json::Value> = titles
        .iter()
        .map(|(id, language, genres)| {
            json!({
                "id": id, "title": "Titre", "media_type": "movie",
                "original_language": language, "genre_ids": genres
            })
        })
        .collect();
    json!({ "page": page, "results": results, "total_pages": total_pages }).to_string()
}

#[tokio::test]
async fn discover_keeps_european_titles_and_japanese_animation_only() {
    let (server, catalog) = setup().await;
    let body = page_with_languages(
        1,
        5,
        &[
            (1, Some("fr"), &[18]),
            (2, Some("ko"), &[18]),
            (3, Some("en"), &[28]),
            (4, Some("ja"), &[16, 10759]),
            (5, Some("ja"), &[18]),
            (6, Some("hi"), &[16]),
            (7, None, &[35]),
            (8, Some("ES"), &[]),
            (9, Some("zh"), &[]),
        ],
    );
    route("trending/all/week")
        .and(query_param("page", "1"))
        .respond_with(ok(&body))
        .mount(&server)
        .await;
    let page = catalog.shelf(Shelf::TrendingWeek, 1).await.unwrap();
    // Coréen, japonais hors animation, hindi et chinois écartés ; sans langue :
    // gardé.
    assert_eq!(ids(&page), vec![1, 3, 4, 7, 8]);
}

#[tokio::test]
async fn genres_and_similar_titles_are_sorted_by_language_too() {
    let (server, catalog) = setup().await;
    route("discover/movie")
        .respond_with(ok(&page_with_languages(
            1,
            5,
            &[(1, Some("th"), &[28]), (2, Some("it"), &[28])],
        )))
        .mount(&server)
        .await;
    let page = catalog.by_genre(MediaType::Movie, 28, 1).await.unwrap();
    assert_eq!(ids(&page), vec![2]);

    route("movie/42")
        .respond_with(ok(&json!({
            "id": 42, "title": "Fiche", "original_language": "ko",
            "similar": { "results": [
                { "id": 7, "title": "A", "original_language": "ko" },
                { "id": 8, "title": "B", "original_language": "de" }
            ] }
        })
        .to_string()))
        .mount(&server)
        .await;
    // La fiche elle-même reste lisible, quelle que soit sa langue.
    let detail = catalog.detail(MediaType::Movie, 42).await.unwrap();
    assert_eq!(detail.summary.id, 42);
    let similar: Vec<u64> = detail.similar.iter().map(|title| title.id).collect();
    assert_eq!(similar, vec![8]);
}

#[tokio::test]
async fn search_keeps_every_language() {
    let (server, catalog) = setup().await;
    route("search/multi")
        .respond_with(ok(&page_with_languages(
            1,
            1,
            &[(1, Some("ko"), &[18]), (2, Some("ja"), &[18])],
        )))
        .mount(&server)
        .await;
    let page = catalog.search("titre".to_owned(), 1).await.unwrap();
    assert_eq!(ids(&page), vec![1, 2]);
}

#[tokio::test]
async fn a_page_emptied_by_the_language_sort_is_skipped() {
    let (server, catalog) = setup().await;
    for (number, language) in [(2, "ko"), (3, "zh")] {
        route("movie/popular")
            .and(query_param("page", number.to_string()))
            .respond_with(ok(&page_with_languages(
                number,
                9,
                &[(number.into(), Some(language), &[])],
            )))
            .expect(1)
            .mount(&server)
            .await;
    }
    route("movie/popular")
        .and(query_param("page", "4"))
        .respond_with(ok(&page_with_languages(4, 9, &[(40, Some("en"), &[])])))
        .expect(1)
        .mount(&server)
        .await;
    let page = catalog.shelf(Shelf::PopularMovies, 2).await.unwrap();
    // La page rendue porte son vrai numéro : l'interface reprend à la 5.
    assert_eq!((page.page, ids(&page)), (4, vec![40]));
}

#[tokio::test]
async fn skipping_stops_at_the_last_page_and_after_three_pages() {
    let (server, catalog) = setup().await;
    route("tv/popular")
        .and(query_param("page", "9"))
        .respond_with(ok(&page_with_languages(9, 9, &[(1, Some("ko"), &[])])))
        .expect(1)
        .mount(&server)
        .await;
    let last = catalog.shelf(Shelf::PopularSeries, 9).await.unwrap();
    assert_eq!((last.page, last.items.len()), (9, 0));

    for number in 1..=4 {
        route("tv/top_rated")
            .and(query_param("page", number.to_string()))
            .respond_with(ok(&page_with_languages(
                number,
                50,
                &[(1, Some("ja"), &[18])],
            )))
            .expect(1)
            .mount(&server)
            .await;
    }
    let page = catalog.shelf(Shelf::TopRatedSeries, 1).await.unwrap();
    // Trois pages vides sautées, la quatrième est rendue même vide.
    assert_eq!((page.page, page.items.len()), (4, 0));
}
