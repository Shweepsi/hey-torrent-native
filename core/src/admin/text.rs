//! Mise en forme des noms de releases et des titres pour les comparer :
//! minuscules ASCII, accents retirés, tout le reste devient une espace.
//!
//! Sans expression régulière : après normalisation, un nom n'est qu'une suite
//! de mots, et chaque règle se lit mot par mot.

/// Lettre accentuée latine → lettres ASCII. Les autres écritures (japonais,
/// cyrillique…) disparaissent : un titre qui n'en contient que devient vide.
fn fold(c: char) -> Option<&'static str> {
    Some(match c {
        'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' | 'ā' => "a",
        'ç' | 'ć' | 'č' => "c",
        'è' | 'é' | 'ê' | 'ë' | 'ē' | 'ę' | 'ě' => "e",
        'ì' | 'í' | 'î' | 'ï' | 'ī' => "i",
        'ñ' | 'ń' | 'ň' => "n",
        'ò' | 'ó' | 'ô' | 'õ' | 'ö' | 'ø' | 'ō' => "o",
        'ù' | 'ú' | 'û' | 'ü' | 'ū' | 'ů' => "u",
        'ý' | 'ÿ' => "y",
        'š' | 'ś' => "s",
        'ž' | 'ź' | 'ż' => "z",
        'ł' => "l",
        'ř' => "r",
        'ß' => "ss",
        'œ' => "oe",
        'æ' => "ae",
        _ => return None,
    })
}

/// Forme comparable : `« L'Été 2023 ! »` → `l ete 2023`.
pub(crate) fn normalize(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut pending_space = false;
    for c in value.chars().flat_map(char::to_lowercase) {
        let piece: Option<&str> = if c.is_ascii_alphanumeric() {
            None
        } else {
            fold(c)
        };
        if c.is_ascii_alphanumeric() || piece.is_some() {
            if pending_space && !out.is_empty() {
                out.push(' ');
            }
            pending_space = false;
            match piece {
                Some(text) => out.push_str(text),
                None => out.push(c),
            }
        } else {
            pending_space = true;
        }
    }
    out
}

/// Mots d'une forme normalisée.
pub(crate) fn words(normalized: &str) -> Vec<&str> {
    normalized.split(' ').filter(|w| !w.is_empty()).collect()
}

/// `needle` (déjà découpé) apparaît comme suite de mots consécutifs de `haystack`.
pub(crate) fn contains_phrase(haystack: &[&str], needle: &[&str]) -> bool {
    !needle.is_empty()
        && haystack
            .windows(needle.len())
            .any(|window| window == needle)
}

/// Le mot est une année plausible de sortie (`19xx` ou `20xx`).
pub(crate) fn as_year(word: &str) -> Option<i32> {
    let year_like = word.len() == 4
        && word.bytes().all(|b| b.is_ascii_digit())
        && (word.starts_with("19") || word.starts_with("20"));
    if year_like { word.parse().ok() } else { None }
}

/// Chiffres en tête d'un mot : `"1x"` → `Some((1, "x"))`.
pub(crate) fn leading_number(word: &str) -> Option<(u32, &str)> {
    let digits = word.bytes().take_while(u8::is_ascii_digit).count();
    if digits == 0 {
        return None;
    }
    let (number, rest) = word.split_at(digits);
    number.parse().ok().map(|n| (n, rest))
}

/// Marqueur de saison au format `s01`, `s1`, `s01e02` : numéro de saison et
/// suite éventuelle (`e02`). Une ou deux chiffres seulement, comme les noms de
/// releases (`s2024` n'en est pas un).
pub(crate) fn season_tag(word: &str) -> Option<(u32, &str)> {
    let rest = word.strip_prefix('s')?;
    let (number, after) = leading_number(rest)?;
    let digits = rest.len() - after.len();
    (digits <= 2).then_some((number, after))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_strips_accents_and_punctuation() {
        assert_eq!(normalize("Le Garçon et le Héron"), "le garcon et le heron");
        assert_eq!(
            normalize("  Bienvenue chez les Ch'tis!! "),
            "bienvenue chez les ch tis"
        );
        assert_eq!(
            normalize("Dune.Part.Two.2024.MULTi"),
            "dune part two 2024 multi"
        );
        assert_eq!(normalize("Œuvre"), "oeuvre");
    }

    #[test]
    fn normalize_drops_non_latin_scripts() {
        assert_eq!(normalize("君たちはどう生きるか"), "");
        assert_eq!(normalize("進撃の巨人 2"), "2");
    }

    #[test]
    fn phrases_match_whole_words_only() {
        let hay = words("fantastic beasts 2016 ts 720p");
        assert!(contains_phrase(&hay, &["ts"]));
        assert!(contains_phrase(&hay, &["2016", "ts"]));
        assert!(!contains_phrase(&hay, &["beast"]));
        assert!(!contains_phrase(&hay, &[]));
    }

    #[test]
    fn years_and_season_tags() {
        assert_eq!(as_year("2010"), Some(2010));
        assert_eq!(as_year("1080"), None);
        assert_eq!(as_year("2160p"), None);
        assert_eq!(season_tag("s01"), Some((1, "")));
        assert_eq!(season_tag("s1e05"), Some((1, "e05")));
        assert_eq!(season_tag("s2024"), None);
        assert_eq!(season_tag("series"), None);
        assert_eq!(season_tag("dts5"), None);
    }
}
