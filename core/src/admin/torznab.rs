//! Client Torznab générique : une source ajoutée par l'administrateur (un
//! indexeur de Prowlarr, Jackett ou tout service compatible). Aucune source
//! n'est connue de l'app.
//!
//! Lecture tolérante : un élément illisible du flux est ignoré, jamais fatal.
//! L'adresse et la clé ne sont jamais journalisées.

use std::time::Duration;

use super::search::SourceFailure;
use super::settings::TorznabSource;
use crate::api::BaseUrl;

/// Délai maximal d'une source (contexte, §3).
pub(crate) const SOURCE_TIMEOUT: Duration = Duration::from_secs(6);
const TORZNAB_NS: &str = "http://torznab.com/schemas/2015/feed";
/// Taille maximale lue d'une réponse (un flux normal fait quelques centaines de Kio).
const MAX_BODY: usize = 8 * 1024 * 1024;

/// Type de recherche Torznab.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Mode {
    /// `t=movie`, catégorie 2000.
    Movie,
    /// `t=tvsearch`, catégorie 5000, saison éventuelle.
    Series { season: Option<u32> },
    /// `t=search` sans catégorie : repli quand la source ne connaît pas les deux autres.
    Generic,
}

/// Un élément du flux, avant la note.
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct Item {
    pub title: String,
    /// Identifiant de l'élément dans sa source (`guid`, sinon le lien).
    pub guid: String,
    /// Lien magnet ou adresse du `.torrent` (contient souvent la clé de la
    /// source) : remis à qBittorrent, jamais à l'app ni aux journaux.
    pub link: String,
    pub infohash: Option<String>,
    pub size: Option<u64>,
    pub seeders: Option<u32>,
    pub peers: Option<u32>,
    /// Date de mise en ligne, telle que reçue (RFC 2822).
    pub published: Option<String>,
    /// Indexeur d'origine quand la source en agrège plusieurs (Prowlarr, Jackett).
    pub indexer: Option<String>,
}

impl std::fmt::Debug for Item {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Item")
            .field("title", &self.title)
            .finish_non_exhaustive()
    }
}

/// Échec d'une lecture du flux.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum FeedError {
    Failure(SourceFailure),
    /// La source ne connaît pas ce type de recherche (codes 201 à 203) : relancer en [`Mode::Generic`].
    Unsupported,
}

/// Adresse de l'API d'une source : le chemin se termine par `/api`, ajouté
/// s'il manque (Jackett donne `…/torznab/`, Prowlarr `…/1/api`).
pub(crate) fn api_url(source: &TorznabSource) -> Result<url::Url, SourceFailure> {
    BaseUrl::parse(&source.url).map_err(|_| SourceFailure::InvalidAddress)?;
    let mut url = url::Url::parse(source.url.trim()).map_err(|_| SourceFailure::InvalidAddress)?;
    let ends_with_api = url
        .path_segments()
        .and_then(|mut s| s.rfind(|seg| !seg.is_empty()))
        == Some("api");
    if !ends_with_api {
        url.path_segments_mut()
            .map_err(|_| SourceFailure::InvalidAddress)?
            .pop_if_empty()
            .push("api");
    }
    Ok(url)
}

/// Adresse d'une recherche.
pub(crate) fn search_url(
    source: &TorznabSource,
    mode: Mode,
    query: &str,
) -> Result<url::Url, SourceFailure> {
    let mut url = api_url(source)?;
    {
        let mut pairs = url.query_pairs_mut();
        match mode {
            Mode::Movie => {
                pairs.append_pair("t", "movie").append_pair("cat", "2000");
            }
            Mode::Series { season } => {
                pairs
                    .append_pair("t", "tvsearch")
                    .append_pair("cat", "5000");
                if let Some(season) = season {
                    pairs.append_pair("season", &season.to_string());
                }
            }
            Mode::Generic => {
                pairs.append_pair("t", "search");
            }
        }
        pairs
            .append_pair("q", query)
            .append_pair("apikey", source.api_key.trim())
            .append_pair("extended", "1");
    }
    Ok(url)
}

/// Adresse du test de connexion (`t=caps`, qui exige la clé chez Prowlarr et Jackett).
pub(crate) fn caps_url(source: &TorznabSource) -> Result<url::Url, SourceFailure> {
    let mut url = api_url(source)?;
    url.query_pairs_mut()
        .append_pair("t", "caps")
        .append_pair("apikey", source.api_key.trim());
    Ok(url)
}

/// Code d'erreur Torznab → échec.
fn error_code(code: u32) -> FeedError {
    match code {
        100..=102 => FeedError::Failure(SourceFailure::AuthFailed),
        201..=203 => FeedError::Unsupported,
        429 => FeedError::Failure(SourceFailure::RateLimited),
        900..=999 => FeedError::Failure(SourceFailure::Server),
        _ => FeedError::Failure(SourceFailure::InvalidResponse),
    }
}

fn child_text<'a>(node: roxmltree::Node<'a, 'a>, name: &str) -> Option<&'a str> {
    node.children()
        .find(|c| {
            c.is_element()
                && c.tag_name().name() == name
                && c.tag_name().namespace().is_none_or(|ns| ns != TORZNAB_NS)
        })
        .and_then(|c| c.text())
        .map(str::trim)
        .filter(|t| !t.is_empty())
}

fn parse_item(node: roxmltree::Node<'_, '_>) -> Option<Item> {
    let title = child_text(node, "title")?.to_owned();
    let mut attrs = std::collections::HashMap::new();
    for attr in node
        .children()
        .filter(|c| c.is_element() && c.tag_name().name() == "attr")
    {
        if let (Some(name), Some(value)) = (attr.attribute("name"), attr.attribute("value")) {
            attrs
                .entry(name.to_ascii_lowercase())
                .or_insert_with(|| value.trim().to_owned());
        }
    }
    let enclosure = node
        .children()
        .find(|c| c.is_element() && c.tag_name().name() == "enclosure");
    let magnet = attrs
        .get("magneturl")
        .filter(|v| v.starts_with("magnet:"))
        .cloned();
    let link = magnet
        .or_else(|| {
            enclosure
                .and_then(|e| e.attribute("url"))
                .map(str::trim)
                .map(str::to_owned)
        })
        .or_else(|| child_text(node, "link").map(str::to_owned))
        .filter(|l| {
            l.starts_with("magnet:") || l.starts_with("https://") || l.starts_with("http://")
        })?;
    let number = |key: &str| attrs.get(key).and_then(|v| v.parse::<u64>().ok());
    let size = child_text(node, "size")
        .and_then(|s| s.parse().ok())
        .or_else(|| number("size"))
        .or_else(|| {
            enclosure
                .and_then(|e| e.attribute("length"))
                .and_then(|l| l.parse().ok())
        })
        .filter(|s| *s > 0);
    let seeders = number("seeders").and_then(|s| u32::try_from(s).ok());
    // `peers` compte les sources et les clients en attente.
    let peers = number("peers").and_then(|s| u32::try_from(s).ok());
    let infohash = attrs
        .get("infohash")
        .filter(|h| h.len() == 40 && h.bytes().all(|b| b.is_ascii_hexdigit()))
        .map(|h| h.to_ascii_lowercase());
    let indexer = ["prowlarrindexer", "jackettindexer", "indexer"]
        .iter()
        .find_map(|name| child_text(node, name))
        .map(str::to_owned);
    Some(Item {
        guid: child_text(node, "guid").map_or_else(|| link.clone(), str::to_owned),
        title,
        link,
        infohash,
        size,
        seeders,
        peers,
        published: child_text(node, "pubDate").map(str::to_owned),
        indexer,
    })
}

/// Lit un flux Torznab (RSS) ; une erreur Torznab (`<error code=…>`) devient [`FeedError`].
pub(crate) fn parse_feed(body: &str) -> Result<Vec<Item>, FeedError> {
    let document = roxmltree::Document::parse(body)
        .map_err(|_| FeedError::Failure(SourceFailure::InvalidResponse))?;
    let root = document.root_element();
    if root.tag_name().name() == "error" {
        let code = root
            .attribute("code")
            .and_then(|c| c.parse().ok())
            .unwrap_or(0);
        return Err(error_code(code));
    }
    if root.tag_name().name() != "rss" {
        return Err(FeedError::Failure(SourceFailure::InvalidResponse));
    }
    Ok(root
        .descendants()
        .filter(|n| n.is_element() && n.tag_name().name() == "item")
        .filter_map(parse_item)
        .collect())
}

/// Lit une réponse de `t=caps` : tout document `<caps>` vaut succès.
pub(crate) fn parse_caps(body: &str) -> Result<(), FeedError> {
    let document = roxmltree::Document::parse(body)
        .map_err(|_| FeedError::Failure(SourceFailure::InvalidResponse))?;
    let root = document.root_element();
    match root.tag_name().name() {
        "caps" => Ok(()),
        "error" => Err(error_code(
            root.attribute("code")
                .and_then(|c| c.parse().ok())
                .unwrap_or(0),
        )),
        _ => Err(FeedError::Failure(SourceFailure::InvalidResponse)),
    }
}

/// Échec de transport → échec de la source. Le détail n'est pas gardé : il
/// contient l'adresse (et donc la clé).
fn transport(error: &reqwest::Error) -> SourceFailure {
    if error.is_timeout() {
        SourceFailure::Timeout
    } else {
        SourceFailure::Unreachable
    }
}

/// GET d'une adresse de la source, avec le délai de 6 s. Corps texte, ou échec.
pub(crate) async fn fetch(http: &reqwest::Client, url: url::Url) -> Result<String, FeedError> {
    let response = http
        .get(url)
        .timeout(SOURCE_TIMEOUT)
        .send()
        .await
        .map_err(|e| FeedError::Failure(transport(&e)))?;
    let status = response.status().as_u16();
    let body = response
        .bytes()
        .await
        .map_err(|e| FeedError::Failure(transport(&e)))?;
    if body.len() > MAX_BODY {
        return Err(FeedError::Failure(SourceFailure::InvalidResponse));
    }
    let text = String::from_utf8_lossy(&body).into_owned();
    if (200..300).contains(&status) {
        return Ok(text);
    }
    // Certains services renvoient l'erreur Torznab avec un statut d'échec : elle est plus précise.
    if let Err(error) = parse_feed(&text)
        && error != FeedError::Failure(SourceFailure::InvalidResponse)
    {
        return Err(error);
    }
    Err(FeedError::Failure(match status {
        401 | 403 => SourceFailure::AuthFailed,
        429 => SourceFailure::RateLimited,
        500..=599 => SourceFailure::Server,
        _ => SourceFailure::InvalidResponse,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn source(url: &str) -> TorznabSource {
        TorznabSource {
            id: "s".into(),
            name: "Maison".into(),
            url: url.into(),
            api_key: "cle".into(),
            enabled: true,
        }
    }

    #[test]
    fn api_path_is_completed() {
        assert_eq!(
            api_url(&source("https://p.example.com/1/api"))
                .unwrap()
                .as_str(),
            "https://p.example.com/1/api"
        );
        assert_eq!(
            api_url(&source(
                "https://j.example.com/api/v2.0/indexers/all/results/torznab/"
            ))
            .unwrap()
            .as_str(),
            "https://j.example.com/api/v2.0/indexers/all/results/torznab/api"
        );
        assert_eq!(
            api_url(&source("https://p.example.com/1"))
                .unwrap()
                .as_str(),
            "https://p.example.com/1/api"
        );
        assert_eq!(
            api_url(&source("http://p.example.com/1/api")),
            Err(SourceFailure::InvalidAddress)
        );
    }

    #[test]
    fn search_urls_carry_mode_and_key() {
        let url = search_url(
            &source("https://p.example.com/1/api"),
            Mode::Series { season: Some(2) },
            "Dark & Co",
        )
        .unwrap();
        let query: Vec<(String, String)> = url.query_pairs().into_owned().collect();
        assert!(query.contains(&("t".into(), "tvsearch".into())));
        assert!(query.contains(&("season".into(), "2".into())));
        assert!(query.contains(&("q".into(), "Dark & Co".into())));
        assert!(query.contains(&("apikey".into(), "cle".into())));
    }

    #[test]
    fn torznab_errors_are_typed() {
        assert_eq!(
            parse_feed(
                r#"<?xml version="1.0"?><error code="100" description="Incorrect user credentials"/>"#
            ),
            Err(FeedError::Failure(SourceFailure::AuthFailed))
        );
        assert_eq!(
            parse_feed(r#"<error code="202" description="No such function"/>"#),
            Err(FeedError::Unsupported)
        );
        assert_eq!(
            parse_feed("<html>panne</html>"),
            Err(FeedError::Failure(SourceFailure::InvalidResponse))
        );
        assert_eq!(
            parse_feed("pas du xml"),
            Err(FeedError::Failure(SourceFailure::InvalidResponse))
        );
        assert_eq!(parse_caps("<caps><server/></caps>"), Ok(()));
    }
}
