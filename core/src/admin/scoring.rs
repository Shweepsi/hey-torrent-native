//! Tri des releases : pertinence (est-ce bien ce titre, cette année, cette
//! saison ?) puis qualité pour un public francophone (langue, définition,
//! source, codec, sources actives).
//!
//! La pertinence reprend les règles éprouvées de l'ancien HeyTorrent, avec
//! leurs cas de test ; seuls les seuils de qualité sont nouveaux.

use super::release::{AudioLanguage, ReleaseInfo, ReleaseSource, Resolution, VideoCodec};
use super::text::{as_year, contains_phrase, leading_number, normalize, season_tag, words};

// ── Pertinence ─────────────────────────────────────────────────────

const SCORE_FULL_TITLE: i32 = 50;
const SCORE_STRIPPED_TITLE: i32 = 45;
const SCORE_ALL_WORDS: i32 = 30;
const SCORE_PARTIAL_MATCH: i32 = 15;
const MIN_WORD_LENGTH: usize = 3;

const BONUS_YEAR_MATCH: i32 = 30;
const PENALTY_WRONG_YEAR: i32 = -50;
const PENALTY_OLD_YEAR: i32 = -50;
const PENALTY_NO_YEAR: i32 = -15;

const BONUS_SEASON_MATCH: i32 = 10;
const PENALTY_WRONG_SEASON: i32 = -100;
const PENALTY_NO_SEASON: i32 = -20;

/// En dessous, une release n'est pas montrée.
pub(crate) const MIN_RELEVANCE: i32 = 20;

const ARTICLES: [&str; 10] = ["the", "a", "an", "le", "la", "les", "l", "un", "une", "des"];

/// Ce qu'on cherche.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Target {
    pub title: String,
    pub original_title: Option<String>,
    /// Année de sortie (première diffusion pour une série), si TMDB la connaît.
    pub year: Option<i32>,
    pub is_series: bool,
    /// Saison voulue (série), sinon `None`.
    pub season: Option<u32>,
}

/// Des mots significatifs (ni article, ni trop courts) précèdent `pos`.
fn significant_words_before(torrent: &str, pos: usize) -> bool {
    torrent
        .get(..pos)
        .unwrap_or_default()
        .split(' ')
        .any(|w| w.len() >= MIN_WORD_LENGTH && !ARTICLES.contains(&w))
}

fn strip_leading_article(title: &str) -> &str {
    match title.split_once(' ') {
        Some((first, rest)) if ARTICLES.contains(&first) => rest.trim(),
        _ => title,
    }
}

/// Note du titre dans le nom (50, 45, 30, 15) ; `None` s'il n'y est pas.
///
/// Le titre complet ne compte que s'il ouvre le nom (articles mis à part) :
/// « The Bride » dans « Father of the Bride » retombe sur la comparaison mot à mot.
fn match_title(torrent: &str, title: &str) -> Option<i32> {
    // Un titre non latin se normalise en "" et serait partout.
    if title.is_empty() {
        return None;
    }
    if let Some(pos) = torrent.find(title)
        && !significant_words_before(torrent, pos)
    {
        return Some(SCORE_FULL_TITLE);
    }
    let stripped = strip_leading_article(title);
    if stripped != title
        && !stripped.is_empty()
        && let Some(pos) = torrent.find(stripped)
        && !significant_words_before(torrent, pos)
    {
        return Some(SCORE_STRIPPED_TITLE);
    }
    let title_words: Vec<&str> = title
        .split(' ')
        .filter(|w| w.len() >= MIN_WORD_LENGTH)
        .collect();
    let matched = title_words.iter().filter(|w| torrent.contains(**w)).count();
    // Au moins deux mots retrouvés : « This is Us » ne se réduit pas à « this ».
    if matched >= 2 && matched == title_words.len() {
        return Some(SCORE_ALL_WORDS);
    }
    // 60 % des mots, en entiers : matched / total >= 0,6.
    if matched >= 2 && matched * 5 >= title_words.len() * 3 {
        return Some(SCORE_PARTIAL_MATCH);
    }
    None
}

/// Années du nom qui peuvent être celle de la sortie : une occurrence de
/// chaque année du titre lui-même (« 1923 », « Blade Runner 2049 ») est
/// mise de côté.
fn release_years(torrent: &[&str], title: &str) -> Vec<i32> {
    let mut years: Vec<i32> = torrent.iter().filter_map(|w| as_year(w)).collect();
    for title_year in words(title).iter().filter_map(|w| as_year(w)) {
        if let Some(index) = years.iter().position(|y| *y == title_year) {
            years.remove(index);
        }
    }
    years
}

/// Année d'une date de publication (RFC 2822 ou ISO 8601).
pub(crate) fn year_of_date(date: &str) -> Option<i32> {
    words(&normalize(date)).iter().find_map(|w| as_year(w))
}

fn score_year(years: &[i32], expected: i32, published_year: Option<i32>) -> i32 {
    if years.contains(&expected) {
        return BONUS_YEAR_MATCH;
    }
    if !years.is_empty() {
        return PENALTY_WRONG_YEAR;
    }
    // Pas d'année dans le nom : la date de mise en ligne sert d'indice. Mise
    // en ligne plus d'un an avant la sortie, ce n'est pas ce film.
    match published_year {
        Some(year) if year < expected - 1 => PENALTY_WRONG_YEAR,
        Some(_) => 0,
        None => PENALTY_NO_YEAR,
    }
}

/// Année inconnue (titre à venir) : une release datée de plus d'un an ne
/// peut pas être la bonne.
fn score_old_year(years: &[i32], current_year: i32) -> i32 {
    if years.iter().any(|y| *y < current_year - 1) {
        PENALTY_OLD_YEAR
    } else {
        0
    }
}

/// Numéros de saison du nom, dans l'ordre : `s01`, `s1e02`, `saison 1`, `season 1`.
fn season_markers(torrent: &[&str]) -> Vec<u32> {
    let mut markers = Vec::new();
    let mut previous: Option<&str> = None;
    for word in torrent {
        if let Some((number, _)) = season_tag(word) {
            markers.push(number);
        } else if matches!(previous, Some("saison" | "season"))
            && let Some((number, _)) = leading_number(word)
        {
            markers.push(number);
        }
        previous = Some(word);
    }
    markers
}

fn score_season(torrent: &[&str], season: u32) -> i32 {
    let markers = season_markers(torrent);
    if markers.contains(&season) {
        BONUS_SEASON_MATCH
    } else if markers.is_empty() {
        PENALTY_NO_SEASON
    } else {
        PENALTY_WRONG_SEASON
    }
}

/// Pertinence d'un nom de release ; `None` si le titre n'y est pas.
pub(crate) fn relevance(
    name: &str,
    target: &Target,
    published_year: Option<i32>,
    current_year: i32,
) -> Option<i32> {
    let torrent = normalize(name);
    let title = normalize(&target.title);
    let mut matched_title = title.clone();
    let mut score = match_title(&torrent, &title);
    if score.is_none()
        && let Some(original) = target.original_title.as_deref()
    {
        let original = normalize(original);
        if original != title {
            score = match_title(&torrent, &original);
            matched_title = original;
        }
    }
    let mut score = score?;
    let torrent_words = words(&torrent);
    let years = release_years(&torrent_words, &matched_title);
    score += match target.year {
        Some(expected) => score_year(&years, expected, published_year),
        None => score_old_year(&years, current_year),
    };
    if target.is_series
        && let Some(season) = target.season.filter(|s| *s > 0)
    {
        score += score_season(&torrent_words, season);
    }
    Some(score)
}

// ── Filtres ────────────────────────────────────────────────────────

const AUDIO_CODECS: [&str; 8] = ["mp3", "flac", "aac", "ogg", "wav", "alac", "ape", "wma"];

/// Livre, bande dessinée, scan.
fn is_ebook(w: &[&str]) -> bool {
    let tome = w.iter().enumerate().any(|(i, word)| {
        *word == "tome"
            && w.get(i + 1)
                .is_some_and(|next| leading_number(next).is_some())
            || word
                .strip_prefix("tome")
                .is_some_and(|rest| leading_number(rest).is_some_and(|(_, r)| r.is_empty()))
    });
    w.iter().any(|word| {
        [
            "cbr", "cbz", "epub", "pdf", "mobi", "djvu", "ibooker", "comic", "scan",
        ]
        .contains(word)
    }) || tome
        || contains_phrase(w, &["bande", "dessinee"])
        || contains_phrase(w, &["bande", "dessine"])
}

/// Audio seul. Un nom de codec audio seul ne suffit pas : les releases vidéo
/// nomment leur piste (« 1080p AAC x264 »).
fn is_audio_only(w: &[&str]) -> bool {
    let after = |marker: &str| {
        w.iter()
            .position(|word| *word == marker)
            .is_some_and(|i| w.iter().skip(i + 1).any(|word| AUDIO_CODECS.contains(word)))
    };
    let bitrate = w.iter().enumerate().any(|(i, word)| {
        let rate = |value: &str| ["320", "192", "256"].contains(&value);
        (rate(word) && w.get(i + 1) == Some(&"kbps")) || word.strip_suffix("kbps").is_some_and(rate)
    });
    w.iter()
        .any(|word| ["discography", "discographie", "audiobook", "v0", "vbr"].contains(word))
        || contains_phrase(w, &["livre", "audio"])
        || bitrate
        || after("album")
        || after("ost")
}

/// Repère de série : `s01`, `s01e02`, `saison 1`, intégrale.
fn is_series_release(w: &[&str]) -> bool {
    !season_markers(w).is_empty()
        || w.iter().any(|word| word.starts_with("integrale"))
        || contains_phrase(w, &["complete", "series"])
        || w.iter().any(|word| {
            word.strip_prefix("saison")
                .or_else(|| word.strip_prefix("season"))
                .is_some_and(|rest| leading_number(rest).is_some())
        })
}

/// Un épisode seul (`s01e02`), pas un pack de saison.
fn is_single_episode(w: &[&str]) -> bool {
    w.iter().any(|word| {
        season_tag(word).is_some_and(|(_, rest)| {
            rest.strip_prefix('e')
                .and_then(leading_number)
                .is_some_and(|(_, after)| after.is_empty())
                && rest.len() <= 4
        })
    })
}

/// Expression exclue par l'administrateur, comme mot entier : « TS » écarte
/// « Film.TS.720p » mais pas « Fantastic Beasts ».
fn is_excluded(w: &[&str], excluded: &[String]) -> bool {
    excluded
        .iter()
        .any(|expression| contains_phrase(w, &words(&normalize(expression))))
}

/// La release est à montrer pour cette cible (avant la note).
pub(crate) fn is_candidate(
    name: &str,
    info: &ReleaseInfo,
    target: &Target,
    excluded: &[String],
) -> bool {
    let normalized = normalize(name);
    let w = words(&normalized);
    if is_ebook(&w)
        || is_audio_only(&w)
        || is_excluded(&w, excluded)
        || info.source == ReleaseSource::Cam
    {
        return false;
    }
    if !target.is_series {
        return !is_series_release(&w);
    }
    // Saison précise : les packs de saison seulement, pas les épisodes.
    target.season.is_none_or(|s| s == 0) || !is_single_episode(&w)
}

// ── Qualité ────────────────────────────────────────────────────────

fn language_points(language: AudioLanguage) -> i32 {
    match language {
        AudioLanguage::MultiVff | AudioLanguage::Vff => 40,
        AudioLanguage::Vf => 35,
        AudioLanguage::Multi => 32,
        AudioLanguage::Vfq => 15,
        AudioLanguage::Vostfr => -25,
        AudioLanguage::Unknown => -40,
    }
}

fn resolution_points(resolution: Resolution) -> i32 {
    match resolution {
        Resolution::Uhd => 30,
        Resolution::FullHd => 28,
        Resolution::Hd => 15,
        Resolution::Sd => 0,
        Resolution::Unknown => 5,
    }
}

fn source_points(source: ReleaseSource) -> i32 {
    match source {
        ReleaseSource::Remux => 12,
        ReleaseSource::BluRay | ReleaseSource::WebDl => 10,
        ReleaseSource::Web => 8,
        ReleaseSource::WebRip => 7,
        ReleaseSource::Hdtv => 3,
        ReleaseSource::Unknown => 2,
        ReleaseSource::Dvd => 0,
        ReleaseSource::Cam => -100,
    }
}

fn codec_points(codec: VideoCodec) -> i32 {
    match codec {
        VideoCodec::Hevc | VideoCodec::Av1 => 8,
        VideoCodec::H264 => 5,
        VideoCodec::Unknown => 2,
        VideoCodec::Xvid => 0,
    }
}

/// Personne ne partage : la release ne se téléchargera pas, elle passe en
/// dernier quelle que soit sa qualité. Sinon une prime
/// qui croît avec le logarithme du nombre de sources, plafonnée à 20.
fn seeder_points(seeders: Option<u32>) -> i32 {
    match seeders {
        Some(0) => -80,
        Some(n) => {
            let log2 = i32::try_from(u32::BITS - (n + 1).leading_zeros() - 1).unwrap_or(0);
            (4 * log2).min(20)
        }
        None => 0,
    }
}

/// Note de qualité, indépendante du titre.
pub(crate) fn quality(info: &ReleaseInfo, seeders: Option<u32>) -> i32 {
    language_points(info.language)
        + resolution_points(info.resolution)
        + source_points(info.source)
        + codec_points(info.codec)
        + if info.hdr { 3 } else { 0 }
        + seeder_points(seeders)
}

#[cfg(test)]
mod tests {
    use super::super::release::parse;
    use super::*;

    const NOW: i32 = 2026;

    fn movie(title: &str, year: Option<i32>) -> Target {
        Target {
            title: title.into(),
            original_title: None,
            year,
            is_series: false,
            season: None,
        }
    }

    fn series(title: &str, year: Option<i32>, season: u32) -> Target {
        Target {
            title: title.into(),
            original_title: None,
            year,
            is_series: true,
            season: Some(season),
        }
    }

    fn score(name: &str, target: &Target) -> i32 {
        relevance(name, target, None, NOW).unwrap_or(-1)
    }

    #[test]
    fn year_of_date_reads_rfc2822_and_iso() {
        assert_eq!(year_of_date("Mon, 01 Jan 2012 00:00:00 +0000"), Some(2012));
        assert_eq!(year_of_date("2023-03-01T00:00:00Z"), Some(2023));
        assert_eq!(year_of_date("2023-11-01"), Some(2023));
        assert_eq!(year_of_date("hier"), None);
    }

    #[test]
    fn seeders_curve() {
        assert_eq!(seeder_points(Some(0)), -80);
        assert_eq!(seeder_points(Some(1)), 4);
        assert_eq!(seeder_points(Some(3)), 8);
        assert_eq!(seeder_points(Some(1000)), 20);
        assert_eq!(seeder_points(None), 0);
    }

    #[test]
    fn french_releases_rank_above_original_versions() {
        let vff = quality(&parse("Film.2020.MULTi.VFF.1080p.BluRay.x265"), Some(20));
        let vo = quality(&parse("Film.2020.1080p.BluRay.x265"), Some(200));
        let vostfr = quality(&parse("Film.2020.VOSTFR.2160p.WEB-DL"), Some(200));
        assert!(vff > vo);
        assert!(vff > vostfr);
    }

    #[test]
    fn dead_releases_rank_last() {
        let dead = quality(&parse("Film.2020.MULTi.1080p.BluRay"), Some(0));
        let alive = quality(&parse("Film.2020.MULTi.720p.WEB"), Some(3));
        assert!(alive > dead);
    }

    #[test]
    fn relevance_basics() {
        assert_eq!(
            score(
                "Inception 2010 1080p BluRay",
                &movie("Inception", Some(2010))
            ),
            80
        );
        assert_eq!(
            score(
                "Completely Different Movie 2020",
                &movie("Inception", Some(2010))
            ),
            -1
        );
        assert_eq!(
            score("Inception 2015 BluRay", &movie("Inception", Some(2010))),
            0
        );
        assert!(
            score(
                "The Dark Knight Rises 2012 HDRip",
                &movie("Dark Knight Rises The", Some(2012))
            ) >= 50
        );
    }

    #[test]
    fn relevance_without_expected_year() {
        assert_eq!(score("Inception 2015 BluRay", &movie("Inception", None)), 0);
        assert_eq!(
            score(
                &format!("Marsupilami {} BluRay", NOW - 2),
                &movie("Marsupilami", None)
            ),
            0
        );
        assert_eq!(
            score(
                &format!("Marsupilami {} BluRay", NOW - 1),
                &movie("Marsupilami", None)
            ),
            50
        );
        assert_eq!(
            score("Marsupilami BluRay 1080p", &movie("Marsupilami", None)),
            50
        );
        assert!(
            score(
                "Sur La Piste Du Marsupilami 2012 FRENCH BluRay",
                &movie("Marsupilami", None)
            ) < 0
        );
    }

    #[test]
    fn relevance_seasons() {
        let bb = series("Breaking Bad", None, 1);
        assert_eq!(score("Breaking Bad S03 FRENCH", &bb), -50);
        assert_eq!(score("Breaking Bad S01 FRENCH", &bb), 60);
        assert_eq!(score("Breaking Bad S01", &bb), 60);
        assert_eq!(score("Breaking Bad S01E01 FRENCH", &bb), 60);
        // « DTS5.1 » n'est pas la saison 5.
        assert_eq!(score("Breaking Bad Complete DTS5.1 MULTi 1080p", &bb), 30);
        assert_eq!(score("Breaking Bad S05 FRENCH", &bb), -50);
        assert_eq!(score("Breaking Bad Saison 1 FRENCH", &bb), 60);
    }

    #[test]
    fn relevance_articles() {
        assert_eq!(
            score(
                "Dark Knight 2008 BluRay",
                &movie("The Dark Knight", Some(2008))
            ),
            75
        );
        assert!(
            score(
                "Dark Knight The 2008 BluRay",
                &movie("The Dark Knight", Some(2008))
            ) >= 50
        );
        assert_eq!(
            score(
                "Vie en Rose 2007 FRENCH",
                &movie("La Vie en Rose", Some(2007))
            ),
            75
        );
    }

    #[test]
    fn relevance_original_titles() {
        let sticks = Target {
            original_title: Some("Bienvenue chez les Ch'tis".into()),
            ..movie("Welcome to the Sticks", Some(2008))
        };
        assert!(score("Bienvenue chez les Chtis 2008 FRENCH", &sticks) >= 20);
        let inception = Target {
            original_title: Some("Inception".into()),
            ..movie("Inception", Some(2010))
        };
        assert_eq!(score("Inception 2010 BluRay", &inception), 80);
        let heron = Target {
            original_title: Some("君たちはどう生きるか".into()),
            ..movie("Le Garçon et le Héron", Some(2023))
        };
        assert_eq!(
            score("Oppenheimer.2023.MULTi.1080p.BluRay.x264", &heron),
            -1
        );
        assert_eq!(
            score("Le.Garcon.et.le.Heron.2023.FRENCH.1080p.WEB", &heron),
            80
        );
        assert_eq!(
            score(
                "Oppenheimer.2023.MULTi.1080p.BluRay.x264",
                &movie("進撃の巨人", Some(2023))
            ),
            -1
        );
        let titan = Target {
            original_title: Some("Shingeki no Kyojin".into()),
            ..series("進撃の巨人", None, 1)
        };
        assert_eq!(score("Shingeki.no.Kyojin.S01.1080p.BluRay", &titan), 60);
    }

    #[test]
    fn relevance_publication_date() {
        let marsupilami = movie("Marsupilami", Some(2026));
        assert_eq!(
            relevance("Marsupilami BluRay 1080p", &marsupilami, Some(2012), NOW),
            Some(0)
        );
        assert_eq!(
            relevance("Marsupilami BluRay", &marsupilami, Some(2025), NOW),
            Some(50)
        );
        assert_eq!(
            relevance("Marsupilami BluRay", &marsupilami, None, NOW),
            Some(35)
        );
    }

    #[test]
    fn relevance_embedded_titles() {
        assert!(
            score(
                "Father of the Bride 2022 FRENCH",
                &movie("The Bride", Some(2026))
            ) < 0
        );
        assert_eq!(
            score("The Bride 2026 FRENCH", &movie("The Bride", Some(2026))),
            80
        );
    }

    #[test]
    fn relevance_years_inside_titles() {
        assert_eq!(
            relevance(
                "1923.S01.MULTi.1080p.WEB.H264",
                &series("1923", Some(2022), 1),
                Some(2023),
                NOW
            ),
            Some(60)
        );
        assert!(
            relevance(
                "Paris.Police.1900.S01.FRENCH.1080p.WEB",
                &series("Paris Police 1900", Some(2021), 1),
                Some(2021),
                NOW
            )
            .is_some_and(|s| s >= MIN_RELEVANCE)
        );
        let blade = movie("Blade Runner 2049", Some(2017));
        assert_eq!(
            relevance(
                "Blade.Runner.2049.MULTi.1080p.BluRay",
                &blade,
                Some(2018),
                NOW
            ),
            Some(50)
        );
        assert_eq!(score("Blade.Runner.2049.2017.MULTi.1080p", &blade), 80);
        assert_eq!(
            score("1917.1970.FRENCH.DVDRip", &movie("1917", Some(2019))),
            0
        );
        assert_eq!(score("1923.S01.MULTi.1080p", &series("1923", None, 1)), 60);
    }

    fn keep(name: &str, target: &Target, excluded: &[&str]) -> bool {
        let excluded: Vec<String> = excluded.iter().map(|e| (*e).to_owned()).collect();
        is_candidate(name, &parse(name), target, &excluded)
    }

    #[test]
    fn filters_books_and_audio() {
        let t = movie("Inception", Some(2010));
        assert!(!keep("Inception 2010 PDF", &t, &[]));
        assert!(keep("Inception 2010 1080p", &t, &[]));
        for name in [
            "Inception 2010 FRENCH 1080p AAC x264",
            "Inception 2010 FRENCH AAC 5.1 720p WEB-DL x264",
            "Inception 2010 1080p FLAC x264-GRP",
            "Inception 2010 480p MP3 XviD",
        ] {
            assert!(keep(name, &t, &[]), "{name}");
        }
        for name in [
            "Inception Soundtrack Discography FLAC",
            "Inception Original Album MP3",
            "Inception OST FLAC lossless",
            "Inception Audiobook narrated",
            "Inception Soundtrack 320 kbps",
            "Inception BD Tome 2",
        ] {
            assert!(!keep(name, &t, &[]), "{name}");
        }
    }

    #[test]
    fn filters_series_from_movie_searches_and_episodes_from_season_searches() {
        let t = movie("Inception", Some(2010));
        assert!(!keep("Inception S01E01", &t, &[]));
        assert!(keep("Inception 2010 BluRay", &t, &[]));
        let bb = series("Breaking Bad", None, 1);
        assert!(!keep("Breaking Bad S01E02 FRENCH", &bb, &[]));
        assert!(keep("Breaking Bad S01 FRENCH", &bb, &[]));
        let whole = Target { season: None, ..bb };
        assert!(keep("Breaking Bad S01E02 FRENCH", &whole, &[]));
    }

    #[test]
    fn excluded_expressions_are_whole_words() {
        let t = movie("Inception", Some(2010));
        assert!(!keep("Inception 2010 VOSTFR 1080p", &t, &["vostfr"]));
        assert!(!keep("Inception 2010 Vostfr BluRay", &t, &["VOSTFR"]));
        assert!(keep("Inception 2010 VOSTFR", &t, &[]));
        assert!(keep("Inception 2010 1080p BluRay", &t, &["", "  "]));
        assert!(keep(
            "Fantastic Beasts 2016 FRENCH BluRay 1080p",
            &movie("Fantastic Beasts", Some(2016)),
            &["TS"]
        ));
        assert!(keep(
            "Cameron Story 2020 BluRay 1080p",
            &movie("Cameron Story", Some(2020)),
            &["CAM"]
        ));
        assert!(!keep(
            "Movie 2020 HC 720p",
            &movie("Movie", Some(2020)),
            &["HC"]
        ));
        assert!(!keep(
            "Movie.2020.Hard.Sub.720p",
            &movie("Movie", Some(2020)),
            &["hard sub"]
        ));
    }

    #[test]
    fn cam_copies_are_never_candidates() {
        let t = movie("Movie", Some(2020));
        assert!(!keep("Movie 2020 TS 720p", &t, &[]));
        assert!(!keep("Movie.2020.CAM.480p", &t, &[]));
        assert!(keep("Movie-2020-BluRay-1080p", &t, &[]));
    }
}
