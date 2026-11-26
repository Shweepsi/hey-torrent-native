//! Décodage tolérant des réponses TMDB relayées par le Worker.
//!
//! Chaque `decode_*` rend `None` quand le corps n'a pas la forme attendue : il
//! ne doit alors ni être servi ni être mis en cache. Dans un corps lisible,
//! une entrée illisible (champ essentiel manquant, type inattendu) est
//! ignorée, jamais fatale pour toute la liste ou la fiche.
//!
//! Les images sont des adresses construites ici sur la base publique des
//! images TMDB, à la taille adaptée à l'écran qui les montre.

use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::Value;

use crate::catalog::{
    CastMember, Episode, Genre, MediaType, Provider, Providers, SeasonDetail, SeasonSummary,
    TitleDetail, TitlePage, TitleSummary, Video, VideoKind,
};

const IMAGE_BASE: &str = "https://image.tmdb.org/t/p/";
/// Acteurs gardés dans une fiche.
const MAX_CAST: usize = 20;
/// Région des plateformes, imposée par le Worker (`watch_region`).
const PROVIDER_REGION: &str = "FR";

/// Tailles d'affiche et de fond selon l'écran.
#[derive(Clone, Copy)]
struct Sizes {
    poster: &'static str,
    backdrop: &'static str,
}

/// Listes, grilles, similaires.
const LIST: Sizes = Sizes {
    poster: "w342",
    backdrop: "w780",
};
/// Fiche plein écran.
const DETAIL: Sizes = Sizes {
    poster: "w500",
    backdrop: "w1280",
};
const PROFILE_SIZE: &str = "w185";
const LOGO_SIZE: &str = "w92";
const STILL_SIZE: &str = "w300";

/// Adresse d'une image, ou `None` si le chemin est absent ou suspect (TMDB ne
/// donne que `/nom.jpg`).
fn image_url(path: Option<&str>, size: &str) -> Option<String> {
    let path = path?.trim();
    let plain = path.len() > 1
        && path.starts_with('/')
        && !path.contains("..")
        && path
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '_' | '-'));
    plain.then(|| format!("{IMAGE_BASE}{size}{path}"))
}

/// Texte sans blancs de bord, `None` s'il est absent ou vide.
fn text(raw: Option<&str>) -> Option<String> {
    raw.map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
}

/// Date `AAAA-MM-JJ` ; TMDB envoie `""` pour une date inconnue.
fn date(raw: Option<&str>) -> Option<String> {
    let raw = raw?.trim();
    let shaped = raw.len() == 10
        && raw.bytes().enumerate().all(|(i, b)| match i {
            4 | 7 => b == b'-',
            _ => b.is_ascii_digit(),
        });
    shaped.then(|| raw.to_owned())
}

fn year(date: &str) -> Option<u32> {
    date.get(..4)?.parse().ok()
}

/// Entier positif (durées : TMDB met `0` pour « inconnu »).
fn positive(value: Option<&Value>) -> Option<u32> {
    unsigned(value).filter(|n| *n > 0)
}

fn unsigned(value: Option<&Value>) -> Option<u32> {
    value?.as_u64().and_then(|n| u32::try_from(n).ok())
}

/// Éléments d'un tableau JSON qui se lisent comme `T` ; les autres sont
/// ignorés.
fn lenient<T: DeserializeOwned>(items: &[Value]) -> Vec<T> {
    items
        .iter()
        .filter_map(|item| T::deserialize(item).ok())
        .collect()
}

/// Tableau JSON à `pointer` (RFC 6901), vide s'il est absent ou d'un autre type.
fn array<'a>(root: &'a Value, pointer: &str) -> &'a [Value] {
    root.pointer(pointer)
        .and_then(Value::as_array)
        .map_or(&[], Vec::as_slice)
}

/// Titre tel que TMDB l'envoie dans une liste ou en tête d'une fiche. Seuls
/// `id` et un titre sont indispensables ; un type inattendu rend l'entrée
/// illisible.
#[derive(Deserialize)]
struct WireTitle {
    id: u64,
    media_type: Option<String>,
    /// Films.
    title: Option<String>,
    /// Séries.
    name: Option<String>,
    release_date: Option<String>,
    first_air_date: Option<String>,
    overview: Option<String>,
    poster_path: Option<String>,
    backdrop_path: Option<String>,
    vote_average: Option<f64>,
    vote_count: Option<u32>,
    genre_ids: Option<Vec<u32>>,
}

impl WireTitle {
    /// `context` : type connu par la requête (liste de films, de séries,
    /// similaires…) ; sans lui, le titre doit porter `media_type` `movie` ou
    /// `tv` (tendances, recherche : les personnes sont écartées).
    fn into_summary(self, context: Option<MediaType>, sizes: Sizes) -> Option<TitleSummary> {
        let media_type = context.or(match self.media_type.as_deref() {
            Some("movie") => Some(MediaType::Movie),
            Some("tv") => Some(MediaType::Tv),
            _ => None,
        })?;
        let (name, other_name, release, other_release) = match media_type {
            MediaType::Movie => (
                self.title,
                self.name,
                self.release_date,
                self.first_air_date,
            ),
            MediaType::Tv => (
                self.name,
                self.title,
                self.first_air_date,
                self.release_date,
            ),
        };
        let title = text(name.as_deref()).or_else(|| text(other_name.as_deref()))?;
        let release_date = date(release.as_deref()).or_else(|| date(other_release.as_deref()));
        Some(TitleSummary {
            id: self.id,
            media_type,
            title,
            year: release_date.as_deref().and_then(year),
            release_date,
            overview: text(self.overview.as_deref()).unwrap_or_default(),
            poster_url: image_url(self.poster_path.as_deref(), sizes.poster),
            backdrop_url: image_url(self.backdrop_path.as_deref(), sizes.backdrop),
            rating: self.vote_average.unwrap_or(0.0),
            vote_count: self.vote_count.unwrap_or(0),
            genre_ids: self.genre_ids.unwrap_or_default(),
        })
    }
}

/// Titres lisibles d'un tableau `results`.
fn titles(items: &[Value], context: Option<MediaType>, sizes: Sizes) -> Vec<TitleSummary> {
    lenient::<WireTitle>(items)
        .into_iter()
        .filter_map(|wire| wire.into_summary(context, sizes))
        .collect()
}

/// Page de liste ou de recherche (`results`, `page`, `total_pages`).
/// `requested` complète une réponse sans numéro de page.
pub(crate) fn decode_page(
    body: &[u8],
    context: Option<MediaType>,
    requested: u32,
) -> Option<TitlePage> {
    #[derive(Deserialize)]
    struct Envelope {
        page: Option<u32>,
        total_pages: Option<u32>,
        results: Vec<Value>,
    }
    let envelope: Envelope = serde_json::from_slice(body).ok()?;
    let page = envelope.page.filter(|p| *p > 0).unwrap_or(requested);
    Some(TitlePage {
        items: titles(&envelope.results, context, LIST),
        page,
        total_pages: envelope.total_pages.unwrap_or(page),
        stale: false,
    })
}

#[derive(Deserialize)]
struct WireGenre {
    id: u32,
    name: Option<String>,
}

fn genres(items: &[Value]) -> Vec<Genre> {
    lenient::<WireGenre>(items)
        .into_iter()
        .filter_map(|genre| {
            Some(Genre {
                id: genre.id,
                name: text(genre.name.as_deref())?,
            })
        })
        .collect()
}

/// Liste des genres (`{"genres": [...]}`).
pub(crate) fn decode_genres(body: &[u8]) -> Option<Vec<Genre>> {
    let root: Value = serde_json::from_slice(body).ok()?;
    let items = root.get("genres")?.as_array()?;
    Some(genres(items))
}

#[derive(Deserialize)]
struct WireCast {
    id: u64,
    name: Option<String>,
    character: Option<String>,
    profile_path: Option<String>,
    order: Option<u32>,
}

/// Personne de l'équipe (`crew`) ou créateur d'une série (`created_by`).
#[derive(Deserialize)]
struct WirePerson {
    name: Option<String>,
    job: Option<String>,
}

#[derive(Deserialize)]
struct WireVideo {
    key: Option<String>,
    name: Option<String>,
    site: Option<String>,
    #[serde(rename = "type")]
    kind: Option<String>,
}

#[derive(Deserialize)]
struct WireProvider {
    provider_id: u32,
    provider_name: Option<String>,
    logo_path: Option<String>,
}

#[derive(Deserialize)]
struct WireSeason {
    season_number: u32,
    name: Option<String>,
    episode_count: Option<u32>,
    air_date: Option<String>,
    poster_path: Option<String>,
}

fn cast(root: &Value) -> Vec<CastMember> {
    let mut members = lenient::<WireCast>(array(root, "/credits/cast"));
    // Ordre du générique ; sans rang, à la fin.
    members.sort_by_key(|member| member.order.unwrap_or(u32::MAX));
    members
        .into_iter()
        .filter_map(|member| {
            Some(CastMember {
                id: member.id,
                name: text(member.name.as_deref())?,
                character: text(member.character.as_deref()),
                profile_url: image_url(member.profile_path.as_deref(), PROFILE_SIZE),
            })
        })
        .take(MAX_CAST)
        .collect()
}

/// Réalisateurs d'un film, créateurs d'une série (sans doublon).
fn directors(root: &Value, media_type: MediaType) -> Vec<String> {
    let names: Vec<String> = match media_type {
        MediaType::Movie => lenient::<WirePerson>(array(root, "/credits/crew"))
            .into_iter()
            .filter(|person| person.job.as_deref() == Some("Director"))
            .filter_map(|person| text(person.name.as_deref()))
            .collect(),
        MediaType::Tv => lenient::<WirePerson>(array(root, "/created_by"))
            .into_iter()
            .filter_map(|person| text(person.name.as_deref()))
            .collect(),
    };
    let mut unique: Vec<String> = Vec::with_capacity(names.len());
    for name in names {
        if !unique.contains(&name) {
            unique.push(name);
        }
    }
    unique
}

/// L'identifiant part tel quel dans une adresse YouTube : seuls les caractères
/// d'un identifiant YouTube passent.
fn is_youtube_key(key: &str) -> bool {
    !key.is_empty()
        && key.len() <= 32
        && key
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_'))
}

fn video_kind(raw: Option<&str>) -> VideoKind {
    match raw {
        Some("Trailer") => VideoKind::Trailer,
        Some("Teaser") => VideoKind::Teaser,
        Some("Clip") => VideoKind::Clip,
        Some("Featurette") => VideoKind::Featurette,
        _ => VideoKind::Other,
    }
}

/// Vidéos YouTube, bandes-annonces d'abord (l'ordre de TMDB sinon).
fn videos(root: &Value) -> Vec<Video> {
    let mut videos: Vec<Video> = lenient::<WireVideo>(array(root, "/videos/results"))
        .into_iter()
        .filter(|video| {
            video
                .site
                .as_deref()
                .is_some_and(|site| site.eq_ignore_ascii_case("youtube"))
        })
        .filter_map(|video| {
            let key = video.key?.trim().to_owned();
            is_youtube_key(&key).then(|| Video {
                youtube_key: key,
                name: text(video.name.as_deref()).unwrap_or_default(),
                kind: video_kind(video.kind.as_deref()),
            })
        })
        .collect();
    videos.sort_by_key(|video| video.kind != VideoKind::Trailer);
    videos
}

/// Plateformes d'une catégorie (`flatrate`, `rent`, `buy`) en France.
fn providers_in(root: &Value, category: &str) -> Vec<Provider> {
    // `watch/providers` : la barre du nom de clé s'écrit `~1` dans un pointeur.
    let pointer = format!("/watch~1providers/results/{PROVIDER_REGION}/{category}");
    lenient::<WireProvider>(array(root, &pointer))
        .into_iter()
        .filter_map(|provider| {
            Some(Provider {
                id: provider.provider_id,
                name: text(provider.provider_name.as_deref())?,
                logo_url: image_url(provider.logo_path.as_deref(), LOGO_SIZE),
            })
        })
        .collect()
}

fn seasons(root: &Value) -> Vec<SeasonSummary> {
    lenient::<WireSeason>(array(root, "/seasons"))
        .into_iter()
        .map(|season| SeasonSummary {
            number: season.season_number,
            name: text(season.name.as_deref()).unwrap_or_default(),
            episode_count: season.episode_count.unwrap_or(0),
            air_date: date(season.air_date.as_deref()),
            poster_url: image_url(season.poster_path.as_deref(), LIST.poster),
        })
        .collect()
}

/// Fiche d'un film ou d'une série, avec `append_to_response` (casting,
/// vidéos, plateformes, similaires). Une fiche sans titre est illisible ; une
/// partie illisible (casting, vidéos…) est simplement vide.
pub(crate) fn decode_detail(body: &[u8], media_type: MediaType) -> Option<TitleDetail> {
    let root: Value = serde_json::from_slice(body).ok()?;
    let wire = WireTitle::deserialize(&root).ok()?;
    let genre_list = genres(array(&root, "/genres"));
    let mut summary = wire.into_summary(Some(media_type), DETAIL)?;
    summary.genre_ids = genre_list.iter().map(|genre| genre.id).collect();

    let is_tv = media_type == MediaType::Tv;
    let runtime_minutes = match media_type {
        MediaType::Movie => positive(root.get("runtime")),
        // `episode_run_time` est souvent vide : repli sur le dernier épisode.
        MediaType::Tv => array(&root, "/episode_run_time")
            .iter()
            .find_map(|minutes| positive(Some(minutes)))
            .or_else(|| positive(root.pointer("/last_episode_to_air/runtime"))),
    };
    Some(TitleDetail {
        summary,
        tagline: text(root.get("tagline").and_then(Value::as_str)),
        genres: genre_list,
        runtime_minutes,
        status: text(root.get("status").and_then(Value::as_str)),
        seasons_count: if is_tv {
            unsigned(root.get("number_of_seasons"))
        } else {
            None
        },
        episodes_count: if is_tv {
            unsigned(root.get("number_of_episodes"))
        } else {
            None
        },
        seasons: if is_tv { seasons(&root) } else { Vec::new() },
        cast: cast(&root),
        directors: directors(&root, media_type),
        videos: videos(&root),
        providers: Providers {
            flatrate: providers_in(&root, "flatrate"),
            rent: providers_in(&root, "rent"),
            buy: providers_in(&root, "buy"),
        },
        similar: titles(array(&root, "/similar/results"), Some(media_type), LIST),
        stale: false,
    })
}

#[derive(Deserialize)]
struct WireEpisode {
    episode_number: u32,
    name: Option<String>,
    overview: Option<String>,
    air_date: Option<String>,
    runtime: Option<u32>,
    still_path: Option<String>,
    vote_average: Option<f64>,
}

/// Saison d'une série et ses épisodes. `requested` complète une réponse sans
/// numéro de saison.
pub(crate) fn decode_season(body: &[u8], requested: u32) -> Option<SeasonDetail> {
    let root: Value = serde_json::from_slice(body).ok()?;
    let items = root.get("episodes")?.as_array()?;
    let episodes = lenient::<WireEpisode>(items)
        .into_iter()
        .map(|episode| Episode {
            number: episode.episode_number,
            name: text(episode.name.as_deref()).unwrap_or_default(),
            overview: text(episode.overview.as_deref()).unwrap_or_default(),
            air_date: date(episode.air_date.as_deref()),
            runtime_minutes: episode.runtime.filter(|minutes| *minutes > 0),
            still_url: image_url(episode.still_path.as_deref(), STILL_SIZE),
            rating: episode.vote_average.unwrap_or(0.0),
        })
        .collect();
    Some(SeasonDetail {
        number: unsigned(root.get("season_number")).unwrap_or(requested),
        name: text(root.get("name").and_then(Value::as_str)).unwrap_or_default(),
        overview: text(root.get("overview").and_then(Value::as_str)).unwrap_or_default(),
        episodes,
        stale: false,
    })
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn bytes(value: &Value) -> Vec<u8> {
        serde_json::to_vec(value).unwrap()
    }

    fn page_of(results: Value) -> Vec<u8> {
        bytes(&json!({ "page": 3, "total_pages": 9, "results": results }))
    }

    #[test]
    fn image_urls_use_the_size_of_their_screen() {
        assert_eq!(
            image_url(Some("/abc.jpg"), "w342").as_deref(),
            Some("https://image.tmdb.org/t/p/w342/abc.jpg")
        );
        assert_eq!(
            image_url(Some(" /a-b_c.png "), "w92").as_deref(),
            Some("https://image.tmdb.org/t/p/w92/a-b_c.png")
        );
    }

    #[test]
    fn suspect_image_paths_give_no_url() {
        for path in [
            None,
            Some(""),
            Some("/"),
            Some("abc.jpg"),
            Some("/a b.jpg"),
            Some("/a?x=1.jpg"),
            Some("/../secret.jpg"),
            Some("/é.jpg"),
        ] {
            assert_eq!(image_url(path, "w342"), None, "chemin accepté : {path:?}");
        }
    }

    #[test]
    fn dates_must_be_complete_iso_dates() {
        assert_eq!(date(Some("2026-10-14")).as_deref(), Some("2026-10-14"));
        for raw in [
            None,
            Some(""),
            Some("2026"),
            Some("2026-10"),
            Some("14/10/2026"),
            Some("2026-1-14"),
        ] {
            assert_eq!(date(raw), None, "date acceptée : {raw:?}");
        }
        assert_eq!(year("2026-10-14"), Some(2026));
    }

    #[test]
    fn list_entries_decode_with_the_context_media_type() {
        let body = page_of(json!([
            {
                "id": 11, "title": "Un film", "release_date": "2025-03-02",
                "overview": "  Synopsis.  ", "poster_path": "/p.jpg", "backdrop_path": "/b.jpg",
                "vote_average": 7.5, "vote_count": 120, "genre_ids": [18, 53]
            },
            { "id": 12, "title": "Autre film", "vote_average": 6 }
        ]));
        let page = decode_page(&body, Some(MediaType::Movie), 1).unwrap();
        assert_eq!((page.page, page.total_pages, page.stale), (3, 9, false));
        let first = page.items.first().unwrap();
        assert_eq!(first.id, 11);
        assert_eq!(first.media_type, MediaType::Movie);
        assert_eq!(first.title, "Un film");
        assert_eq!(first.year, Some(2025));
        assert_eq!(first.release_date.as_deref(), Some("2025-03-02"));
        assert_eq!(first.overview, "Synopsis.");
        assert_eq!(
            first.poster_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w342/p.jpg")
        );
        assert_eq!(
            first.backdrop_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w780/b.jpg")
        );
        assert_eq!((first.rating, first.vote_count), (7.5, 120));
        assert_eq!(first.genre_ids, vec![18, 53]);
        // Champs absents : valeurs neutres, jamais d'échec.
        let second = page.items.get(1).unwrap();
        assert_eq!(second.rating, 6.0);
        assert_eq!(second.overview, "");
        assert_eq!((second.year, second.poster_url.clone()), (None, None));
        assert!(second.genre_ids.is_empty());
    }

    #[test]
    fn series_use_name_and_first_air_date() {
        let body = page_of(json!([
            { "id": 5, "name": "Une série", "first_air_date": "2024-09-01", "title": "ignoré" }
        ]));
        let page = decode_page(&body, Some(MediaType::Tv), 1).unwrap();
        let item = page.items.first().unwrap();
        assert_eq!(item.media_type, MediaType::Tv);
        assert_eq!(item.title, "Une série");
        assert_eq!(item.year, Some(2024));
    }

    #[test]
    fn a_title_falls_back_to_the_other_name_field() {
        let body = page_of(json!([
            { "id": 5, "name": "Seulement name", "release_date": "2020-01-01" }
        ]));
        let page = decode_page(&body, Some(MediaType::Movie), 1).unwrap();
        let item = page.items.first().unwrap();
        assert_eq!(item.title, "Seulement name");
        assert_eq!(item.release_date.as_deref(), Some("2020-01-01"));
    }

    #[test]
    fn unreadable_entries_are_skipped_not_fatal() {
        let body = page_of(json!([
            { "id": 1, "title": "Bon" },
            { "title": "Sans id" },
            { "id": "1", "title": "Id texte" },
            { "id": 2 },
            { "id": 3, "title": "   " },
            { "id": 4, "title": "Note texte", "vote_average": "bonne" },
            { "id": 6, "title": "Genres faux", "genre_ids": ["a"] },
            { "id": 7, "title": null },
            [1, 2],
            "texte",
            null,
            { "id": 8, "title": "Autre bon" }
        ]));
        let page = decode_page(&body, Some(MediaType::Movie), 1).unwrap();
        let ids: Vec<u64> = page.items.iter().map(|item| item.id).collect();
        assert_eq!(ids, vec![1, 8]);
    }

    #[test]
    fn mixed_lists_keep_only_movies_and_series() {
        let body = page_of(json!([
            { "id": 1, "title": "Film", "media_type": "movie" },
            { "id": 2, "name": "Série", "media_type": "tv" },
            { "id": 3, "name": "Personne", "media_type": "person" },
            { "id": 4, "title": "Sans type" },
            { "id": 5, "title": "Type inconnu", "media_type": "collection" }
        ]));
        let page = decode_page(&body, None, 1).unwrap();
        let kinds: Vec<(u64, MediaType)> = page
            .items
            .iter()
            .map(|item| (item.id, item.media_type))
            .collect();
        assert_eq!(kinds, vec![(1, MediaType::Movie), (2, MediaType::Tv)]);
    }

    #[test]
    fn page_numbers_default_to_the_requested_page() {
        let body = bytes(&json!({ "results": [] }));
        let page = decode_page(&body, Some(MediaType::Movie), 4).unwrap();
        assert_eq!((page.page, page.total_pages), (4, 4));
        assert!(page.items.is_empty());
    }

    #[test]
    fn an_unreadable_body_is_not_a_page() {
        for body in [
            &b""[..],
            b"<html>",
            b"[]",
            b"{}",
            b"{\"results\": null}",
            b"{\"results\": {}}",
            b"{\"page\": \"1\", \"results\": []}",
        ] {
            assert!(
                decode_page(body, None, 1).is_none(),
                "corps accepté : {}",
                String::from_utf8_lossy(body)
            );
        }
    }

    #[test]
    fn genres_skip_unreadable_entries() {
        let body = bytes(&json!({ "genres": [
            { "id": 28, "name": "Action" },
            { "id": 12 },
            { "id": 14, "name": " " },
            { "name": "Sans id" },
            { "id": "x", "name": "Id texte" },
            { "id": 35, "name": "Comédie" }
        ]}));
        let genres = decode_genres(&body).unwrap();
        let names: Vec<&str> = genres.iter().map(|genre| genre.name.as_str()).collect();
        assert_eq!(names, vec!["Action", "Comédie"]);
        assert!(decode_genres(b"{}").is_none());
        assert!(decode_genres(b"{\"genres\": 1}").is_none());
    }

    fn movie_root() -> Value {
        json!({
            "id": 77, "title": "Le Film", "tagline": "  Une accroche.  ",
            "overview": "Résumé.", "release_date": "2026-01-02",
            "poster_path": "/p.jpg", "backdrop_path": "/b.jpg",
            "vote_average": 8.2, "vote_count": 10, "runtime": 0, "status": "Released",
            "genres": [{ "id": 18, "name": "Drame" }, { "id": 53, "name": "Thriller" }]
        })
    }

    #[test]
    fn detail_uses_the_big_image_sizes_and_genres() {
        let detail = decode_detail(&bytes(&movie_root()), MediaType::Movie).unwrap();
        assert_eq!(
            detail.summary.poster_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w500/p.jpg")
        );
        assert_eq!(
            detail.summary.backdrop_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w1280/b.jpg")
        );
        assert_eq!(detail.summary.genre_ids, vec![18, 53]);
        assert_eq!(detail.genres.len(), 2);
        assert_eq!(detail.tagline.as_deref(), Some("Une accroche."));
        assert_eq!(detail.status.as_deref(), Some("Released"));
        // `0` minute : durée inconnue.
        assert_eq!(detail.runtime_minutes, None);
        assert_eq!((detail.seasons_count, detail.episodes_count), (None, None));
        assert!(detail.seasons.is_empty() && detail.cast.is_empty());
        assert!(detail.videos.is_empty() && detail.similar.is_empty());
        assert!(detail.providers.flatrate.is_empty());
        assert!(!detail.stale);
    }

    #[test]
    fn a_detail_without_title_or_id_is_unreadable() {
        let mut root = movie_root();
        root["title"] = json!("   ");
        assert!(decode_detail(&bytes(&root), MediaType::Movie).is_none());
        let mut root = movie_root();
        root.as_object_mut().unwrap().remove("id");
        assert!(decode_detail(&bytes(&root), MediaType::Movie).is_none());
        assert!(decode_detail(b"[]", MediaType::Movie).is_none());
        assert!(decode_detail(b"nope", MediaType::Movie).is_none());
    }

    #[test]
    fn unreadable_parts_of_a_detail_are_empty_not_fatal() {
        let mut root = movie_root();
        root["credits"] = json!("cassé");
        root["videos"] = json!({ "results": 3 });
        root["watch/providers"] = json!([]);
        root["similar"] = json!({ "results": "non" });
        root["runtime"] = json!("long");
        let detail = decode_detail(&bytes(&root), MediaType::Movie).unwrap();
        assert!(detail.cast.is_empty() && detail.directors.is_empty());
        assert!(detail.videos.is_empty() && detail.similar.is_empty());
        assert_eq!(detail.runtime_minutes, None);
    }

    #[test]
    fn cast_is_ordered_capped_and_tolerant() {
        let mut members: Vec<Value> = (0..30)
            .map(|i| json!({ "id": 100 + i, "name": format!("Acteur {i}"), "order": 29 - i }))
            .collect();
        members.push(json!({ "id": 1, "name": "", "order": 0 }));
        members.push(json!({ "name": "Sans id" }));
        let mut root = movie_root();
        root["credits"] = json!({ "cast": members });
        let detail = decode_detail(&bytes(&root), MediaType::Movie).unwrap();
        assert_eq!(detail.cast.len(), MAX_CAST);
        // Rang 0 d'abord (« Acteur 29 »), l'entrée sans nom est écartée.
        assert_eq!(detail.cast.first().unwrap().name, "Acteur 29");
        assert_eq!(detail.cast.get(19).unwrap().name, "Acteur 10");
    }

    #[test]
    fn cast_members_carry_character_and_profile() {
        let mut root = movie_root();
        root["credits"] = json!({ "cast": [
            { "id": 9, "name": "Une Actrice", "character": " Marie ", "profile_path": "/face.jpg" },
            { "id": 10, "name": "Un Acteur", "character": "", "profile_path": null }
        ]});
        let detail = decode_detail(&bytes(&root), MediaType::Movie).unwrap();
        let first = detail.cast.first().unwrap();
        assert_eq!(first.character.as_deref(), Some("Marie"));
        assert_eq!(
            first.profile_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w185/face.jpg")
        );
        let second = detail.cast.get(1).unwrap();
        assert_eq!(
            (second.character.clone(), second.profile_url.clone()),
            (None, None)
        );
    }

    #[test]
    fn movie_directors_come_from_the_crew_without_duplicates() {
        let mut root = movie_root();
        root["credits"] = json!({ "crew": [
            { "name": "Réal Un", "job": "Director" },
            { "name": "Compositeur", "job": "Original Music Composer" },
            { "name": "Réal Un", "job": "Director" },
            { "name": "Réal Deux", "job": "Director" },
            { "job": "Director" }
        ]});
        let detail = decode_detail(&bytes(&root), MediaType::Movie).unwrap();
        assert_eq!(detail.directors, vec!["Réal Un", "Réal Deux"]);
    }

    #[test]
    fn series_have_creators_seasons_counts_and_an_episode_runtime() {
        let root = json!({
            "id": 5, "name": "La Série", "first_air_date": "2024-09-01",
            "number_of_seasons": 2, "number_of_episodes": 0,
            "episode_run_time": [0, 52, 40],
            "created_by": [{ "id": 1, "name": "Créatrice" }, { "id": 2, "name": "Créateur" }],
            "credits": { "crew": [{ "name": "Réal", "job": "Director" }] },
            "seasons": [
                { "season_number": 0, "name": "Spéciaux", "episode_count": 2, "air_date": "" },
                { "season_number": 1, "name": "Saison 1", "episode_count": 8,
                  "air_date": "2024-09-01", "poster_path": "/s1.jpg" },
                { "name": "Sans numéro" }
            ]
        });
        let detail = decode_detail(&bytes(&root), MediaType::Tv).unwrap();
        assert_eq!(detail.summary.media_type, MediaType::Tv);
        assert_eq!(detail.summary.year, Some(2024));
        assert_eq!(detail.runtime_minutes, Some(52));
        assert_eq!(
            (detail.seasons_count, detail.episodes_count),
            (Some(2), Some(0))
        );
        assert_eq!(detail.directors, vec!["Créatrice", "Créateur"]);
        let numbers: Vec<u32> = detail.seasons.iter().map(|s| s.number).collect();
        assert_eq!(numbers, vec![0, 1]);
        let season = detail.seasons.get(1).unwrap();
        assert_eq!(season.episode_count, 8);
        assert_eq!(season.air_date.as_deref(), Some("2024-09-01"));
        assert_eq!(
            season.poster_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w342/s1.jpg")
        );
        assert_eq!(detail.seasons.first().unwrap().air_date, None);
    }

    #[test]
    fn a_series_runtime_falls_back_to_the_last_episode() {
        let root = json!({
            "id": 5, "name": "La Série", "episode_run_time": [],
            "last_episode_to_air": { "runtime": 47 }
        });
        let detail = decode_detail(&bytes(&root), MediaType::Tv).unwrap();
        assert_eq!(detail.runtime_minutes, Some(47));
    }

    #[test]
    fn only_youtube_videos_with_a_safe_key_are_kept_trailers_first() {
        let mut root = movie_root();
        root["videos"] = json!({ "results": [
            { "key": "abcDEF12345", "site": "YouTube", "type": "Teaser", "name": "Teaser" },
            { "key": "vimeo123", "site": "Vimeo", "type": "Trailer", "name": "Vimeo" },
            { "key": "x&y=1", "site": "YouTube", "type": "Trailer", "name": "Clé piégée" },
            { "key": "", "site": "YouTube", "type": "Trailer", "name": "Clé vide" },
            { "site": "YouTube", "type": "Trailer", "name": "Sans clé" },
            { "key": "trailer-1_a", "site": "youtube", "type": "Trailer", "name": "Bande-annonce" },
            { "key": "clip00000001", "site": "YouTube", "type": "Behind the Scenes" },
            { "key": "trailer-2", "site": "YouTube", "type": "Trailer", "name": "Seconde" }
        ]});
        let detail = decode_detail(&bytes(&root), MediaType::Movie).unwrap();
        let seen: Vec<(&str, VideoKind)> = detail
            .videos
            .iter()
            .map(|video| (video.youtube_key.as_str(), video.kind))
            .collect();
        assert_eq!(
            seen,
            vec![
                ("trailer-1_a", VideoKind::Trailer),
                ("trailer-2", VideoKind::Trailer),
                ("abcDEF12345", VideoKind::Teaser),
                ("clip00000001", VideoKind::Other),
            ]
        );
        assert_eq!(detail.videos.first().unwrap().name, "Bande-annonce");
    }

    #[test]
    fn providers_come_from_france_only() {
        let mut root = movie_root();
        root["watch/providers"] = json!({ "results": {
            "US": { "flatrate": [{ "provider_id": 1, "provider_name": "Américaine" }] },
            "FR": {
                "link": "https://example.com/fr",
                "flatrate": [
                    { "provider_id": 8, "provider_name": "Abonnement", "logo_path": "/l8.jpg" },
                    { "provider_id": 9 },
                    { "provider_name": "Sans id" }
                ],
                "rent": [{ "provider_id": 2, "provider_name": "Location" }],
                "buy": []
            }
        }});
        let detail = decode_detail(&bytes(&root), MediaType::Movie).unwrap();
        let flatrate = &detail.providers.flatrate;
        assert_eq!(flatrate.len(), 1);
        assert_eq!(flatrate.first().unwrap().name, "Abonnement");
        assert_eq!(
            flatrate.first().unwrap().logo_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w92/l8.jpg")
        );
        assert_eq!(detail.providers.rent.first().unwrap().logo_url, None);
        assert!(detail.providers.buy.is_empty());
    }

    #[test]
    fn similar_titles_take_the_type_of_the_detail() {
        let mut root = movie_root();
        root["similar"] = json!({ "page": 1, "results": [
            { "id": 21, "title": "Proche", "poster_path": "/q.jpg" },
            { "id": 22 }
        ]});
        let detail = decode_detail(&bytes(&root), MediaType::Movie).unwrap();
        assert_eq!(detail.similar.len(), 1);
        let similar = detail.similar.first().unwrap();
        assert_eq!(similar.media_type, MediaType::Movie);
        assert_eq!(
            similar.poster_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w342/q.jpg")
        );
    }

    #[test]
    fn seasons_decode_their_episodes() {
        let body = bytes(&json!({
            "season_number": 2, "name": "Saison 2", "overview": "Suite.",
            "episodes": [
                { "episode_number": 1, "name": "Retour", "overview": "Début.",
                  "air_date": "2025-01-10", "runtime": 48, "still_path": "/e1.jpg",
                  "vote_average": 7.9 },
                { "episode_number": 2, "runtime": 0, "air_date": "" },
                { "name": "Sans numéro" },
                { "episode_number": "3" }
            ]
        }));
        let season = decode_season(&body, 9).unwrap();
        assert_eq!((season.number, season.name.as_str()), (2, "Saison 2"));
        assert_eq!(season.overview, "Suite.");
        assert_eq!(season.episodes.len(), 2);
        let first = season.episodes.first().unwrap();
        assert_eq!(first.number, 1);
        assert_eq!(first.runtime_minutes, Some(48));
        assert_eq!(
            first.still_url.as_deref(),
            Some("https://image.tmdb.org/t/p/w300/e1.jpg")
        );
        assert_eq!(first.rating, 7.9);
        let second = season.episodes.get(1).unwrap();
        assert_eq!(second.name, "");
        assert_eq!(
            (second.runtime_minutes, second.air_date.clone()),
            (None, None)
        );
        assert_eq!(second.rating, 0.0);
    }

    #[test]
    fn a_season_without_number_takes_the_requested_one() {
        let season = decode_season(b"{\"episodes\": []}", 4).unwrap();
        assert_eq!(season.number, 4);
        assert!(season.episodes.is_empty());
        assert!(decode_season(b"{}", 4).is_none());
        assert!(decode_season(b"{\"episodes\": {}}", 4).is_none());
        assert!(decode_season(b"oops", 4).is_none());
    }
}
