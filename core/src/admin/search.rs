//! Recherche d'une demande sur toutes les sources actives, **en parallèle**,
//! avec 6 s au plus par source : une source lente ou en panne est signalée
//! et ignorée, les résultats des autres arrivent dès qu'ils sont prêts.

use std::collections::HashMap;

use futures_util::stream::{FuturesUnordered, StreamExt};
use sha2::{Digest, Sha256};

use super::release::{self, ReleaseInfo};
use super::scoring::{self, MIN_RELEVANCE, Target};
use super::settings::{ServerSettings, TorznabSource};
use super::text::normalize;
use super::torznab::{self, FeedError, Item, Mode};
use crate::catalog::MediaType;

/// Pourquoi une source n'a rien donné.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SourceFailure {
    /// Pas de réponse en 6 secondes.
    Timeout,
    /// Injoignable (adresse, réseau, certificat).
    Unreachable,
    /// Clé d'API refusée.
    AuthFailed,
    /// Trop de requêtes : la source demande d'attendre.
    RateLimited,
    /// Erreur de la source elle-même.
    Server,
    /// Réponse qui n'est pas un flux Torznab.
    InvalidResponse,
    /// Adresse de la source invalide (pas en HTTPS).
    InvalidAddress,
}

/// Ce qu'une source a donné.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SourceReport {
    pub source_id: String,
    /// Nom donné par l'administrateur.
    pub source_name: String,
    /// `None` : la source a répondu.
    pub failure: Option<SourceFailure>,
    /// Releases retenues pour cette source.
    pub count: u32,
}

/// Une release retenue, notée. Son lien de téléchargement reste dans le
/// cœur : l'app ne le voit jamais.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Release {
    /// Identifiant à rendre à [`super::AdminSpace::send_release`].
    pub id: String,
    pub title: String,
    pub source_name: String,
    /// Indexeur d'origine, quand la source en agrège plusieurs.
    pub indexer: Option<String>,
    pub size_bytes: Option<u64>,
    pub seeders: Option<u32>,
    pub peers: Option<u32>,
    /// Mise en ligne, en secondes Unix.
    pub published_at: Option<i64>,
    pub info: ReleaseInfo,
    /// Note globale (pertinence et qualité) : plus haut, mieux.
    pub score: i32,
}

/// Ce qu'on cherche.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchQuery {
    pub media_type: MediaType,
    /// Titre cherché (modifiable par l'administrateur).
    pub title: String,
    /// Titre original, cherché aussi s'il diffère.
    pub original_title: Option<String>,
    pub year: Option<u32>,
    /// Série : saison voulue (`None` : série entière).
    pub season: Option<u32>,
}

/// Résultat final d'une recherche.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SearchOutcome {
    /// Toutes sources confondues, meilleure note d'abord, sans doublons.
    pub releases: Vec<Release>,
    pub reports: Vec<SourceReport>,
}

/// Reçoit l'avancement d'une recherche, à chaque source terminée, depuis un
/// fil du cœur : l'app repasse sur son fil principal pour afficher.
#[uniffi::export(with_foreign)]
pub trait SearchObserver: Send + Sync {
    /// `releases` : tout ce qui est arrivé jusqu'ici, déjà trié ;
    /// `pending` : sources qui n'ont pas encore répondu.
    fn on_progress(&self, releases: Vec<Release>, reports: Vec<SourceReport>, pending: u32);
}

/// Lien gardé dans le cœur pour une release.
#[derive(Clone)]
pub(crate) struct ReleaseLink {
    pub link: String,
    pub infohash: Option<String>,
    pub title: String,
}

/// Résultat interne : releases notées et liens correspondants.
pub(crate) struct Found {
    pub outcome: SearchOutcome,
    pub links: HashMap<String, ReleaseLink>,
}

fn release_id(source_id: &str, guid: &str) -> String {
    let digest = Sha256::digest(format!("{source_id}\n{guid}").as_bytes());
    digest.iter().take(12).map(|b| format!("{b:02x}")).collect()
}

/// Jours depuis le 1er janvier 1970 d'une date civile (algorithme de H. Hinnant).
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// Année civile (UTC) d'un instant Unix.
pub(crate) fn year_of_unix(seconds: i64) -> i32 {
    let days = seconds.div_euclid(86_400);
    // Inverse approché puis ajusté : simple et sans table.
    let mut year = 1970 + days.div_euclid(365);
    while days_from_civil(year, 1, 1) > days {
        year -= 1;
    }
    while days_from_civil(year + 1, 1, 1) <= days {
        year += 1;
    }
    i32::try_from(year).unwrap_or(1970)
}

/// Date de publication (RFC 2822 `Mon, 01 Jan 2012 10:00:00 +0100` ou ISO
/// `2012-01-01T10:00:00Z`) en secondes Unix ; `None` si illisible.
pub(crate) fn parse_date(raw: &str) -> Option<i64> {
    const MONTHS: [&str; 12] = [
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    ];
    let raw = raw.trim();
    let (year, month, day, time, zone): (i64, i64, i64, &str, &str);
    // ISO : `AAAA-MM-JJ` en tête (« Thu, … » contient aussi un T).
    let iso = if raw.get(4..5) == Some("-") {
        raw.split_once(['T', ' '])
    } else {
        None
    };
    if let Some((date, rest)) = iso {
        let mut parts = date.split('-');
        year = parts.next()?.parse().ok()?;
        month = parts.next()?.parse().ok()?;
        day = parts.next()?.parse().ok()?;
        let split = rest.find(['Z', '+', '-']).unwrap_or(rest.len());
        time = rest.get(..split)?;
        zone = rest.get(split..).unwrap_or("");
    } else {
        let rest = raw.split_once(',').map_or(raw, |(_, r)| r);
        let mut parts = rest.split_whitespace();
        day = parts.next()?.parse().ok()?;
        let name = parts.next()?.to_ascii_lowercase();
        month = i64::try_from(MONTHS.iter().position(|m| name.starts_with(m))? + 1).ok()?;
        year = parts.next()?.parse().ok()?;
        time = parts.next().unwrap_or("00:00:00");
        zone = parts.next().unwrap_or("");
    }
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) || !(1970..=2200).contains(&year) {
        return None;
    }
    let mut clock = time
        .split(':')
        .map(|p| p.split('.').next().unwrap_or("0").parse::<i64>().ok());
    let hours = clock.next().flatten().unwrap_or(0);
    let minutes = clock.next().flatten().unwrap_or(0);
    let seconds = clock.next().flatten().unwrap_or(0);
    let offset = match zone.as_bytes().first() {
        Some(sign @ (b'+' | b'-')) => {
            let digits: String = zone.chars().filter(char::is_ascii_digit).collect();
            let value: i64 = digits.parse().ok()?;
            let (h, m) = if digits.len() > 2 {
                (value / 100, value % 100)
            } else {
                (value, 0)
            };
            let total = h * 3600 + m * 60;
            if *sign == b'+' { total } else { -total }
        }
        _ => 0,
    };
    Some(
        days_from_civil(year, month, day) * 86_400 + hours * 3600 + minutes * 60 + seconds - offset,
    )
}

/// Cible de la note, depuis la requête.
fn target(query: &SearchQuery) -> Target {
    Target {
        title: query.title.trim().to_owned(),
        original_title: query
            .original_title
            .clone()
            .filter(|t| !t.trim().is_empty()),
        year: query.year.and_then(|y| i32::try_from(y).ok()),
        is_series: query.media_type == MediaType::Tv,
        season: if query.media_type == MediaType::Tv {
            query.season
        } else {
            None
        },
    }
}

/// Textes envoyés à chaque source : le titre, et le titre original s'il diffère.
fn search_texts(query: &SearchQuery) -> Vec<String> {
    let mut texts = vec![query.title.trim().to_owned()];
    if let Some(original) = query.original_title.as_deref().map(str::trim)
        && !original.is_empty()
        && normalize(original) != normalize(&query.title)
        && !normalize(original).is_empty()
    {
        texts.push(original.to_owned());
    }
    texts
}

/// Recherche d'un texte sur une source, avec repli sur `t=search`.
async fn search_text(
    http: &reqwest::Client,
    source: &TorznabSource,
    mode: Mode,
    text: &str,
) -> Result<Vec<Item>, SourceFailure> {
    let url = torznab::search_url(source, mode, text)?;
    let first = torznab::fetch(http, url)
        .await
        .and_then(|body| torznab::parse_feed(&body));
    match first {
        Ok(items) => Ok(items),
        Err(FeedError::Unsupported) => {
            let url = torznab::search_url(source, Mode::Generic, text)?;
            match torznab::fetch(http, url)
                .await
                .and_then(|body| torznab::parse_feed(&body))
            {
                Ok(items) => Ok(items),
                Err(FeedError::Failure(failure)) => Err(failure),
                Err(FeedError::Unsupported) => Err(SourceFailure::InvalidResponse),
            }
        }
        Err(FeedError::Failure(failure)) => Err(failure),
    }
}

/// Une source : tous ses textes en parallèle. Échoue seulement si tous échouent.
async fn search_source(
    http: &reqwest::Client,
    source: &TorznabSource,
    query: &SearchQuery,
) -> Result<Vec<Item>, SourceFailure> {
    let mode = match query.media_type {
        MediaType::Movie => Mode::Movie,
        MediaType::Tv => Mode::Series {
            season: query.season,
        },
    };
    let texts = search_texts(query);
    let results = futures_util::future::join_all(
        texts
            .iter()
            .map(|text| search_text(http, source, mode, text)),
    )
    .await;
    let mut items = Vec::new();
    let mut failure = None;
    for result in results {
        match result {
            Ok(found) => items.extend(found),
            Err(error) => failure = failure.or(Some(error)),
        }
    }
    match failure {
        Some(error) if items.is_empty() => Err(error),
        _ => Ok(items),
    }
}

/// Note les éléments d'une source : seuls restent les candidats assez pertinents.
fn score_items(
    source: &TorznabSource,
    items: Vec<Item>,
    target: &Target,
    excluded: &[String],
    current_year: i32,
) -> Vec<(Release, ReleaseLink)> {
    let mut seen = std::collections::HashSet::new();
    items
        .into_iter()
        .filter(|item| seen.insert(item.guid.clone()))
        .filter_map(|item| {
            let info = release::parse(&item.title);
            if !scoring::is_candidate(&item.title, &info, target, excluded) {
                return None;
            }
            let published_at = item.published.as_deref().and_then(parse_date);
            let published_year = published_at
                .map(year_of_unix)
                .or_else(|| item.published.as_deref().and_then(scoring::year_of_date));
            let relevance = scoring::relevance(&item.title, target, published_year, current_year)?;
            if relevance < MIN_RELEVANCE {
                return None;
            }
            let release = Release {
                id: release_id(&source.id, &item.guid),
                title: item.title.clone(),
                source_name: source.name.clone(),
                indexer: item.indexer,
                size_bytes: item.size,
                seeders: item.seeders,
                peers: item.peers,
                published_at,
                score: relevance + scoring::quality(&info, item.seeders),
                info,
            };
            let link = ReleaseLink {
                link: item.link,
                infohash: item.infohash,
                title: item.title,
            };
            Some((release, link))
        })
        .collect()
}

/// Ordre d'affichage : meilleure note, puis plus de sources, puis le nom.
fn sort(releases: &mut [Release]) {
    releases.sort_by(|a, b| {
        b.score
            .cmp(&a.score)
            .then_with(|| b.seeders.unwrap_or(0).cmp(&a.seeders.unwrap_or(0)))
            .then_with(|| a.title.cmp(&b.title))
    });
}

/// Fusion d'un lot dans l'ensemble : une même release vue par deux sources
/// (même empreinte, ou même nom et même taille) n'apparaît qu'une fois, avec
/// le plus de sources actives.
fn merge(
    all: &mut Vec<Release>,
    links: &mut HashMap<String, ReleaseLink>,
    batch: Vec<(Release, ReleaseLink)>,
) {
    for (release, link) in batch {
        let duplicate = all.iter().position(|other| {
            let same_hash = link.infohash.is_some()
                && links.get(&other.id).and_then(|l| l.infohash.as_ref()) == link.infohash.as_ref();
            let same_name = normalize(&other.title) == normalize(&release.title)
                && other.size_bytes == release.size_bytes;
            same_hash || same_name
        });
        match duplicate {
            Some(index) => {
                let better = all
                    .get(index)
                    .is_some_and(|other| release.seeders.unwrap_or(0) > other.seeders.unwrap_or(0));
                if better && let Some(slot) = all.get_mut(index) {
                    links.remove(&slot.id);
                    links.insert(release.id.clone(), link);
                    *slot = release;
                }
            }
            None => {
                links.insert(release.id.clone(), link);
                all.push(release);
            }
        }
    }
    sort(all);
}

/// Cherche sur toutes les sources actives en parallèle. `observer` est
/// prévenu à chaque source terminée.
pub(crate) async fn run(
    http: &reqwest::Client,
    settings: &ServerSettings,
    query: &SearchQuery,
    observer: Option<&dyn SearchObserver>,
    current_year: i32,
) -> Found {
    let target = target(query);
    let sources: Vec<&TorznabSource> = settings.sources.iter().filter(|s| s.enabled).collect();
    let mut pending: FuturesUnordered<_> = sources
        .iter()
        .map(|source| async move { (*source, search_source(http, source, query).await) })
        .collect();
    let mut releases = Vec::new();
    let mut links = HashMap::new();
    let mut reports = Vec::new();
    while let Some((source, result)) = pending.next().await {
        let report = match result {
            Ok(items) => {
                let batch = score_items(
                    source,
                    items,
                    &target,
                    &settings.excluded_terms,
                    current_year,
                );
                let count = u32::try_from(batch.len()).unwrap_or(u32::MAX);
                merge(&mut releases, &mut links, batch);
                SourceReport {
                    source_id: source.id.clone(),
                    source_name: source.name.clone(),
                    failure: None,
                    count,
                }
            }
            Err(failure) => SourceReport {
                source_id: source.id.clone(),
                source_name: source.name.clone(),
                failure: Some(failure),
                count: 0,
            },
        };
        reports.push(report);
        if let Some(observer) = observer {
            observer.on_progress(
                releases.clone(),
                reports.clone(),
                u32::try_from(pending.len()).unwrap_or(0),
            );
        }
    }
    Found {
        outcome: SearchOutcome { releases, reports },
        links,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Jeu de cas de `tests/fixtures/releases.json` : chaque nom est retenu
    /// ou écarté, et lu, comme prévu ; le classement suit l'ordre attendu.
    const RELEASES: &str = include_str!("../../tests/fixtures/releases.json");

    fn item(name: &str, seeders: Option<u32>) -> Item {
        Item {
            title: name.into(),
            guid: name.into(),
            link: format!("https://indexer.invalid/{name}"),
            infohash: None,
            size: None,
            seeders,
            peers: None,
            published: None,
            indexer: None,
        }
    }

    fn source() -> TorznabSource {
        TorznabSource {
            id: "s".into(),
            name: "Maison".into(),
            url: "https://indexer.invalid/api".into(),
            api_key: "k".into(),
            enabled: true,
        }
    }

    #[test]
    fn release_fixture_cases() {
        let fixture: serde_json::Value = serde_json::from_str(RELEASES).unwrap();
        for case in fixture["cases"].as_array().unwrap() {
            let name = case["name"].as_str().unwrap();
            let query = SearchQuery {
                media_type: if case["media"] == "tv" {
                    MediaType::Tv
                } else {
                    MediaType::Movie
                },
                title: case["title"].as_str().unwrap().into(),
                original_title: case["original"].as_str().map(str::to_owned),
                year: case["year"].as_u64().map(|y| u32::try_from(y).unwrap()),
                season: case["season"].as_u64().map(|s| u32::try_from(s).unwrap()),
            };
            let kept = score_items(
                &source(),
                vec![item(name, Some(10))],
                &target(&query),
                &[],
                2026,
            );
            assert_eq!(!kept.is_empty(), case["keep"].as_bool().unwrap(), "{name}");
            let info = release::parse(name);
            if let Some(language) = case["language"].as_str() {
                assert_eq!(format!("{:?}", info.language), language, "{name}");
            }
            if let Some(resolution) = case["resolution"].as_str() {
                assert_eq!(format!("{:?}", info.resolution), resolution, "{name}");
            }
            if let Some(source) = case["source"].as_str() {
                assert_eq!(format!("{:?}", info.source), source, "{name}");
            }
        }
    }

    #[test]
    fn release_fixture_ranking() {
        let fixture: serde_json::Value = serde_json::from_str(RELEASES).unwrap();
        let ranking = &fixture["ranking"];
        let query = SearchQuery {
            media_type: MediaType::Movie,
            title: ranking["title"].as_str().unwrap().into(),
            original_title: None,
            year: ranking["year"].as_u64().map(|y| u32::try_from(y).unwrap()),
            season: None,
        };
        let expected: Vec<&str> = ranking["order"]
            .as_array()
            .unwrap()
            .iter()
            .map(|r| r["name"].as_str().unwrap())
            .collect();
        // Mélangés à l'envers : l'ordre vient de la note, pas de l'arrivée.
        let items = ranking["order"]
            .as_array()
            .unwrap()
            .iter()
            .rev()
            .map(|r| {
                item(
                    r["name"].as_str().unwrap(),
                    r["seeders"].as_u64().map(|s| u32::try_from(s).unwrap()),
                )
            })
            .collect();
        let mut all = Vec::new();
        let mut links = HashMap::new();
        merge(
            &mut all,
            &mut links,
            score_items(&source(), items, &target(&query), &[], 2026),
        );
        let order: Vec<&str> = all.iter().map(|r| r.title.as_str()).collect();
        assert_eq!(order, expected);
    }

    #[test]
    fn dates_are_read_in_both_formats() {
        assert_eq!(parse_date("Thu, 01 Jan 1970 00:00:00 +0000"), Some(0));
        assert_eq!(
            parse_date("Mon, 01 Jan 2012 01:00:00 +0100"),
            Some(1_325_376_000)
        );
        assert_eq!(parse_date("2012-01-01T00:00:00Z"), Some(1_325_376_000));
        assert_eq!(parse_date("2012-01-01T02:00:00+02:00"), Some(1_325_376_000));
        assert_eq!(parse_date("n'importe quoi"), None);
    }

    #[test]
    fn years_of_unix_times() {
        assert_eq!(year_of_unix(0), 1970);
        assert_eq!(year_of_unix(1_325_376_000), 2012);
        assert_eq!(year_of_unix(1_325_375_999), 2011);
        assert_eq!(year_of_unix(1_798_761_600), 2027);
    }

    #[test]
    fn original_title_is_searched_only_when_different() {
        let mut query = SearchQuery {
            media_type: MediaType::Movie,
            title: "Le Garçon et le Héron".into(),
            original_title: Some("君たちはどう生きるか".into()),
            year: Some(2023),
            season: None,
        };
        assert_eq!(search_texts(&query).len(), 1);
        query.original_title = Some("Le garcon et le heron".into());
        assert_eq!(search_texts(&query).len(), 1);
        query.original_title = Some("The Boy and the Heron".into());
        assert_eq!(search_texts(&query).len(), 2);
    }
}
