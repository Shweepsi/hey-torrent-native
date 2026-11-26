//! Catalogue TMDB : tendances, listes, genres, recherche, fiches et saisons,
//! lus par le relais du Worker (`/v1/tmdb/…`) avec la session du compte.
//!
//! Le cœur ne produit aucun texte affiché : il rend des types, et les textes
//! venant de TMDB (titres, synopsis, noms) sont des données. Les réponses sont
//! décodées avec tolérance (voir `tmdb`) et gardées dans un cache SQLite pour
//! le hors ligne (voir `cache`).

use std::sync::Arc;

use tokio::sync::OnceCell;

use crate::account::Account;
use crate::api::ApiFailure;
use crate::cache::{Cache, unix_now};
use crate::tmdb;

/// Dernière page acceptée par le Worker (et par TMDB).
const MAX_PAGE: u32 = 500;
/// Longueur maximale d'une recherche acceptée par le Worker, en caractères.
const MAX_QUERY_CHARS: usize = 100;
/// Plus grand identifiant accepté par le Worker (10 chiffres).
pub(crate) const MAX_ID: u64 = 9_999_999_999;
/// Plus grand numéro de saison accepté par le Worker.
pub(crate) const MAX_SEASON: u32 = 99;

/// Film ou série.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum MediaType {
    Movie,
    Tv,
}

impl MediaType {
    pub(crate) fn wire(self) -> &'static str {
        match self {
            Self::Movie => "movie",
            Self::Tv => "tv",
        }
    }

    pub(crate) fn from_wire(raw: &str) -> Option<Self> {
        match raw {
            "movie" => Some(Self::Movie),
            "tv" => Some(Self::Tv),
            _ => None,
        }
    }
}

/// Liste du catalogue, une rangée de l'écran Découvrir.
///
/// `TrendingWeek` mêle films et séries ; les autres sont les listes TMDB
/// correspondantes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Shelf {
    TrendingWeek,
    PopularMovies,
    NowPlayingMovies,
    UpcomingMovies,
    TopRatedMovies,
    PopularSeries,
    OnTheAirSeries,
    TopRatedSeries,
}

impl Shelf {
    /// Chemin relayé (après `/v1/tmdb/`) et type des titres de la liste
    /// (`None` : mélange, chaque titre porte le sien).
    fn route(self) -> (&'static str, Option<MediaType>) {
        match self {
            Self::TrendingWeek => ("trending/all/week", None),
            Self::PopularMovies => ("movie/popular", Some(MediaType::Movie)),
            Self::NowPlayingMovies => ("movie/now_playing", Some(MediaType::Movie)),
            Self::UpcomingMovies => ("movie/upcoming", Some(MediaType::Movie)),
            Self::TopRatedMovies => ("movie/top_rated", Some(MediaType::Movie)),
            Self::PopularSeries => ("tv/popular", Some(MediaType::Tv)),
            Self::OnTheAirSeries => ("tv/on_the_air", Some(MediaType::Tv)),
            Self::TopRatedSeries => ("tv/top_rated", Some(MediaType::Tv)),
        }
    }
}

/// Un titre dans une liste (affiche, titre, année, note).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TitleSummary {
    pub id: u64,
    pub media_type: MediaType,
    pub title: String,
    pub year: Option<u32>,
    /// `AAAA-MM-JJ`.
    pub release_date: Option<String>,
    pub overview: String,
    pub poster_url: Option<String>,
    pub backdrop_url: Option<String>,
    /// Note moyenne sur 10.
    pub rating: f64,
    pub vote_count: u32,
    pub genre_ids: Vec<u32>,
}

/// Une page de titres. `stale` : lue dans le cache faute de réseau.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TitlePage {
    pub items: Vec<TitleSummary>,
    pub page: u32,
    pub total_pages: u32,
    pub stale: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Genre {
    pub id: u32,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CastMember {
    pub id: u64,
    pub name: String,
    pub character: Option<String>,
    pub profile_url: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum VideoKind {
    Trailer,
    Teaser,
    Clip,
    Featurette,
    Other,
}

/// Vidéo hébergée par YouTube (les autres hébergeurs sont écartés).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Video {
    /// Identifiant de la vidéo : `https://www.youtube.com/watch?v=<clé>`.
    pub youtube_key: String,
    pub name: String,
    pub kind: VideoKind,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Provider {
    pub id: u32,
    pub name: String,
    pub logo_url: Option<String>,
}

/// Où regarder le titre en France.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Providers {
    /// Inclus dans un abonnement.
    pub flatrate: Vec<Provider>,
    pub rent: Vec<Provider>,
    pub buy: Vec<Provider>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SeasonSummary {
    pub number: u32,
    pub name: String,
    pub episode_count: u32,
    pub air_date: Option<String>,
    pub poster_url: Option<String>,
}

/// Fiche complète d'un film ou d'une série.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TitleDetail {
    pub summary: TitleSummary,
    pub tagline: Option<String>,
    pub genres: Vec<Genre>,
    /// Film : durée ; série : durée d'un épisode.
    pub runtime_minutes: Option<u32>,
    pub status: Option<String>,
    pub seasons_count: Option<u32>,
    pub episodes_count: Option<u32>,
    pub seasons: Vec<SeasonSummary>,
    /// Vingt acteurs au plus, dans l'ordre du générique.
    pub cast: Vec<CastMember>,
    /// Réalisateurs (film) ou créateurs (série).
    pub directors: Vec<String>,
    /// Bandes-annonces d'abord.
    pub videos: Vec<Video>,
    pub providers: Providers,
    pub similar: Vec<TitleSummary>,
    pub stale: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Episode {
    pub number: u32,
    pub name: String,
    pub overview: String,
    pub air_date: Option<String>,
    pub runtime_minutes: Option<u32>,
    pub still_url: Option<String>,
    pub rating: f64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SeasonDetail {
    pub number: u32,
    pub name: String,
    pub overview: String,
    pub episodes: Vec<Episode>,
    pub stale: bool,
}

/// Erreur d'une lecture du catalogue. L'interface traduit chaque cas en
/// français ; le texte ci-dessous ne sert qu'aux journaux.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CatalogError {
    /// Pas de réseau, serveur injoignable ou délai dépassé (et rien en cache).
    #[error("network unavailable")]
    Network,
    /// Session absente, expirée ou révoquée : se reconnecter.
    #[error("signed out")]
    SignedOut,
    /// Titre, saison ou page inconnus.
    #[error("not found")]
    NotFound,
    /// Le service du catalogue (TMDB) ne répond pas (et rien en cache).
    #[error("catalog unavailable")]
    Unavailable,
    /// Erreur du serveur (5xx) (et rien en cache).
    #[error("server error")]
    Server,
    /// Réponse imprévue : statut, format illisible.
    #[error("unexpected response")]
    Unexpected,
    /// Défaillance interne.
    #[error("internal error")]
    Internal,
}

impl From<ApiFailure> for CatalogError {
    fn from(failure: ApiFailure) -> Self {
        match failure {
            ApiFailure::Network => Self::Network,
            ApiFailure::SignedOut => Self::SignedOut,
            ApiFailure::NotFound => Self::NotFound,
            ApiFailure::Unavailable => Self::Unavailable,
            ApiFailure::Server => Self::Server,
            // Le catalogue n'envoie que des requêtes valides : un refus est imprévu.
            ApiFailure::Rejected | ApiFailure::Unexpected => Self::Unexpected,
            ApiFailure::Internal => Self::Internal,
        }
    }
}

/// Catalogue de l'app. Un seul par processus suffit, construit avec l'unique
/// [`Account`] : la session et le renouvellement du jeton sont ceux du compte.
#[derive(uniffi::Object)]
pub struct Catalog {
    account: Arc<Account>,
    cache_path: Option<String>,
    /// Ouvert à la première lecture, hors du fil de l'interface. `None` dedans :
    /// ouverture impossible, on se passe de cache.
    cache: OnceCell<Option<Cache>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl Catalog {
    /// `cache_path` : fichier du cache hors ligne (dossier privé de l'app).
    /// `None`, ou un fichier inutilisable : pas de cache, jamais d'échec.
    #[uniffi::constructor]
    pub fn new(account: Arc<Account>, cache_path: Option<String>) -> Arc<Self> {
        Arc::new(Self {
            account,
            cache_path,
            cache: OnceCell::new(),
        })
    }

    /// Une page d'une liste du catalogue.
    pub async fn shelf(&self, shelf: Shelf, page: u32) -> Result<TitlePage, CatalogError> {
        let (path, context) = shelf.route();
        self.titles(path, context, &[], page, true).await
    }

    /// Genres de films ou de séries, en français.
    pub async fn genres(&self, media_type: MediaType) -> Result<Vec<Genre>, CatalogError> {
        let target = target(&format!("genre/{}/list", media_type.wire()), &[]);
        let (genres, _) = self.fetch(target, true, tmdb::decode_genres).await?;
        Ok(genres)
    }

    /// Titres d'un genre, du plus populaire au moins populaire.
    ///
    /// `genre_id` est toujours un identifiant de genre de **film** (celui de
    /// `genres(MediaType::Movie)`), y compris pour les séries : le cœur le
    /// traduit en genre de série, les interfaces n'ont aucune correspondance
    /// à faire. Un genre de film sans équivalent série (Horreur, Musique,
    /// Romance, Thriller, Histoire, Téléfilm) rend une page vide
    /// (`total_pages` à 0) sans appel réseau.
    pub async fn by_genre(
        &self,
        media_type: MediaType,
        genre_id: u32,
        page: u32,
    ) -> Result<TitlePage, CatalogError> {
        let genre_id = match media_type {
            MediaType::Movie => genre_id,
            MediaType::Tv => match tv_genre(genre_id) {
                Some(tv_id) => tv_id,
                None => return Ok(empty_page(page.max(1), 0)),
            },
        };
        let genre = genre_id.to_string();
        let params = [
            ("sort_by", "popularity.desc"),
            ("with_genres", genre.as_str()),
        ];
        self.titles(
            &format!("discover/{}", media_type.wire()),
            Some(media_type),
            &params,
            page,
            true,
        )
        .await
    }

    /// Recherche de films et de séries. Une requête vide ou blanche rend une
    /// page vide sans appel réseau. Jamais mise en cache.
    pub async fn search(&self, query: String, page: u32) -> Result<TitlePage, CatalogError> {
        let query: String = query.trim().chars().take(MAX_QUERY_CHARS).collect();
        let query = query.trim_end();
        if query.is_empty() {
            return Ok(empty_page(page.max(1), 1));
        }
        self.titles("search/multi", None, &[("query", query)], page, false)
            .await
    }

    /// Fiche complète : casting, vidéos, plateformes en France, similaires.
    pub async fn detail(
        &self,
        media_type: MediaType,
        id: u64,
    ) -> Result<TitleDetail, CatalogError> {
        if !(1..=MAX_ID).contains(&id) {
            return Err(CatalogError::NotFound);
        }
        let target = target(&format!("{}/{id}", media_type.wire()), &[]);
        let (mut detail, stale) = self
            .fetch(target, true, |body| tmdb::decode_detail(body, media_type))
            .await?;
        detail.stale = stale;
        Ok(detail)
    }

    /// Épisodes d'une saison d'une série (`0` : épisodes spéciaux).
    pub async fn season(
        &self,
        tv_id: u64,
        season_number: u32,
    ) -> Result<SeasonDetail, CatalogError> {
        if !(1..=MAX_ID).contains(&tv_id) || season_number > MAX_SEASON {
            return Err(CatalogError::NotFound);
        }
        let target = target(&format!("tv/{tv_id}/season/{season_number}"), &[]);
        let (mut season, stale) = self
            .fetch(target, true, |body| {
                tmdb::decode_season(body, season_number)
            })
            .await?;
        season.stale = stale;
        Ok(season)
    }
}

impl Catalog {
    /// Cache ouvert au premier besoin, `None` s'il est inutilisable.
    async fn cache(&self) -> Option<&Cache> {
        self.cache
            .get_or_init(|| async {
                self.cache_path
                    .as_deref()
                    .and_then(|path| Cache::open(path, unix_now()))
            })
            .await
            .as_ref()
    }

    /// Page de titres : `page` est ramenée à 1 au moins ; au-delà de la
    /// dernière page du Worker, page vide sans appel réseau.
    async fn titles(
        &self,
        path: &str,
        context: Option<MediaType>,
        params: &[(&str, &str)],
        page: u32,
        cacheable: bool,
    ) -> Result<TitlePage, CatalogError> {
        let page = page.max(1);
        if page > MAX_PAGE {
            return Ok(empty_page(page, MAX_PAGE));
        }
        let page_text = page.to_string();
        let mut query = vec![("page", page_text.as_str())];
        query.extend_from_slice(params);
        let (mut titles, stale) = self
            .fetch(target(path, &query), cacheable, |body| {
                tmdb::decode_page(body, context, page)
            })
            .await?;
        titles.stale = stale;
        Ok(titles)
    }

    /// Lit `target` et le décode. Rend aussi `true` si la valeur vient du cache
    /// faute de réseau.
    ///
    /// - Entrée de moins de 5 minutes : servie sans réseau.
    /// - Sinon réseau ; réponse décodée avec succès : mise en cache (corps
    ///   brut) ; réponse illisible : [`CatalogError::Unexpected`], jamais
    ///   mise en cache.
    /// - Réseau, service ou serveur en échec : l'entrée en cache, si elle
    ///   existe et se lit encore, est servie comme périmée.
    async fn fetch<T>(
        &self,
        target: String,
        cacheable: bool,
        decode: impl Fn(&[u8]) -> Option<T>,
    ) -> Result<(T, bool), CatalogError> {
        let cache = if cacheable { self.cache().await } else { None };
        let cached = cache.and_then(|cache| cache.get(&target));
        if let Some(entry) = &cached
            && entry.is_fresh(unix_now())
            && let Some(value) = decode(&entry.body)
        {
            return Ok((value, false));
        }
        match self.account.get_bytes(&target).await {
            Ok(body) => {
                let value = decode(&body).ok_or(CatalogError::Unexpected)?;
                if let Some(cache) = cache {
                    cache.put(&target, &body, unix_now());
                }
                Ok((value, false))
            }
            Err(failure @ (ApiFailure::Network | ApiFailure::Unavailable | ApiFailure::Server)) => {
                cached
                    .and_then(|entry| decode(&entry.body))
                    .map(|value| (value, true))
                    .ok_or_else(|| CatalogError::from(failure))
            }
            Err(failure) => Err(failure.into()),
        }
    }
}

/// Genre de série correspondant à un genre de film, `None` s'il n'y en a pas.
///
/// TMDB a deux listes de genres : celle des films (`genre/movie/list`) et
/// celle des séries (`genre/tv/list`), dont les identifiants sont stables. Les
/// interfaces ne connaissent que les genres de films ; cette table est la
/// seule correspondance, voulue par le cœur (règle métier) :
///
/// - Action (28) et Aventure (12) → Action et aventure (10759) ;
/// - Fantastique (14) et Science-fiction (878) → Science-fiction et
///   fantastique (10765) ;
/// - Guerre (10752) → Guerre et politique (10768) ;
/// - Animation (16), Comédie (35), Crime (80), Documentaire (99), Drame (18),
///   Familial (10751), Mystère (9648) et Western (37) existent à l'identique ;
/// - Horreur (27), Musique (10402), Romance (10749), Thriller (53), Histoire
///   (36) et Téléfilm (10770) n'ont aucun équivalent série, comme tout
///   identifiant qui n'est pas un genre de film.
fn tv_genre(movie_genre_id: u32) -> Option<u32> {
    match movie_genre_id {
        28 | 12 => Some(10759),
        14 | 878 => Some(10765),
        10752 => Some(10768),
        16 | 35 | 80 | 99 | 18 | 10751 | 9648 | 37 => Some(movie_genre_id),
        _ => None,
    }
}

/// Page sans titre.
fn empty_page(page: u32, total_pages: u32) -> TitlePage {
    TitlePage {
        items: Vec::new(),
        page,
        total_pages,
        stale: false,
    }
}

/// Chemin et requête envoyés au Worker, aussi clé du cache : les paramètres
/// sont triés et encodés, la clé d'API TMDB n'en fait jamais partie (c'est le
/// Worker qui l'ajoute).
fn target(path: &str, params: &[(&str, &str)]) -> String {
    let mut target = format!("/v1/tmdb/{path}");
    if !params.is_empty() {
        let mut sorted = params.to_vec();
        sorted.sort_unstable();
        let query = url::form_urlencoded::Serializer::new(String::new())
            .extend_pairs(sorted)
            .finish();
        target.push('?');
        target.push_str(&query);
    }
    target
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn targets_sort_and_encode_their_parameters() {
        assert_eq!(target("genre/tv/list", &[]), "/v1/tmdb/genre/tv/list");
        assert_eq!(
            target(
                "discover/movie",
                &[
                    ("with_genres", "28"),
                    ("sort_by", "popularity.desc"),
                    ("page", "2")
                ]
            ),
            "/v1/tmdb/discover/movie?page=2&sort_by=popularity.desc&with_genres=28"
        );
        assert_eq!(
            target("search/multi", &[("query", "l'été & co/é"), ("page", "1")]),
            "/v1/tmdb/search/multi?page=1&query=l%27%C3%A9t%C3%A9+%26+co%2F%C3%A9"
        );
    }

    #[test]
    fn shelves_map_to_the_relayed_lists() {
        assert_eq!(Shelf::TrendingWeek.route(), ("trending/all/week", None));
        assert_eq!(
            Shelf::NowPlayingMovies.route(),
            ("movie/now_playing", Some(MediaType::Movie))
        );
        assert_eq!(
            Shelf::OnTheAirSeries.route(),
            ("tv/on_the_air", Some(MediaType::Tv))
        );
    }

    #[test]
    fn every_movie_genre_maps_to_its_series_genre_or_to_none() {
        let table = [
            (28, Some(10759)),
            (12, Some(10759)),
            (14, Some(10765)),
            (878, Some(10765)),
            (10752, Some(10768)),
            (16, Some(16)),
            (35, Some(35)),
            (80, Some(80)),
            (99, Some(99)),
            (18, Some(18)),
            (10751, Some(10751)),
            (9648, Some(9648)),
            (37, Some(37)),
            (27, None),
            (10402, None),
            (10749, None),
            (53, None),
            (36, None),
            (10770, None),
            // Pas un genre de film : un genre de série, zéro, hors plage.
            (10759, None),
            (0, None),
            (u32::MAX, None),
        ];
        for (movie_genre, expected) in table {
            assert_eq!(tv_genre(movie_genre), expected, "{movie_genre}");
        }
    }

    #[test]
    fn catalog_errors_have_english_log_messages() {
        assert_eq!(CatalogError::Network.to_string(), "network unavailable");
        assert_eq!(CatalogError::NotFound.to_string(), "not found");
    }
}
