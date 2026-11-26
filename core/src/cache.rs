//! Cache hors ligne du catalogue : les réponses brutes du Worker, rangées dans
//! un fichier SQLite dont l'app donne le chemin.
//!
//! Le cache n'est jamais fatal : ouverture impossible, lecture ou écriture en
//! échec (disque plein, fichier corrompu, verrou) se comportent comme un cache
//! vide. Aucun identifiant n'y est rangé : la clé est le chemin et les
//! paramètres de la requête, la valeur le corps de la réponse TMDB.

use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{Connection, OpenFlags, OptionalExtension, params};

/// Une entrée plus récente que cela est servie sans appel réseau (secondes).
const FRESH_FOR: i64 = 5 * 60;
/// Au-delà, une entrée est élaguée à l'ouverture (secondes).
const MAX_AGE: i64 = 30 * 24 * 60 * 60;
/// Nombre d'entrées gardées à l'ouverture, les plus récentes d'abord.
const MAX_ENTRIES: i64 = 400;

/// Secondes écoulées depuis le 1er janvier 1970 (0 si l'horloge est avant).
pub(crate) fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| {
            i64::try_from(elapsed.as_secs()).unwrap_or(i64::MAX)
        })
}

/// Réponse gardée et date de son enregistrement.
pub(crate) struct Entry {
    pub(crate) body: Vec<u8>,
    fetched_at: i64,
}

impl Entry {
    /// Assez récente pour se passer du réseau. Une date dans le futur (horloge
    /// réglée en arrière) n'est jamais fraîche.
    pub(crate) fn is_fresh(&self, now: i64) -> bool {
        (0..FRESH_FOR).contains(&(now - self.fetched_at))
    }
}

pub(crate) struct Cache {
    connection: Mutex<Connection>,
}

impl Cache {
    /// Ouvre (ou crée) le cache à `path` et élague les vieilles entrées.
    /// `None` si le fichier est inutilisable : l'appelant se passe de cache.
    pub(crate) fn open(path: &str, now: i64) -> Option<Self> {
        // Un chemin vide ferait créer à SQLite une base temporaire, perdue à
        // la fermeture : mieux vaut pas de cache.
        if path.trim().is_empty() {
            return None;
        }
        // Pas de `SQLITE_OPEN_URI` : le chemin de l'app n'est jamais interprété.
        let flags = OpenFlags::SQLITE_OPEN_READ_WRITE
            | OpenFlags::SQLITE_OPEN_CREATE
            | OpenFlags::SQLITE_OPEN_NO_MUTEX;
        let connection = Connection::open_with_flags(path, flags).ok()?;
        connection
            .execute_batch(
                "CREATE TABLE IF NOT EXISTS responses (
                    cache_key TEXT PRIMARY KEY NOT NULL,
                    body BLOB NOT NULL,
                    fetched_at INTEGER NOT NULL
                ) WITHOUT ROWID;",
            )
            .ok()?;
        let cache = Self {
            connection: Mutex::new(connection),
        };
        cache.prune(now);
        Some(cache)
    }

    /// Entrée enregistrée sous `key`, quel que soit son âge.
    pub(crate) fn get(&self, key: &str) -> Option<Entry> {
        let connection = self.connection.lock().ok()?;
        connection
            .query_row(
                "SELECT body, fetched_at FROM responses WHERE cache_key = ?1",
                [key],
                |row| {
                    Ok(Entry {
                        body: row.get(0)?,
                        fetched_at: row.get(1)?,
                    })
                },
            )
            .optional()
            .ok()
            .flatten()
    }

    /// Enregistre (ou remplace) la réponse de `key`.
    pub(crate) fn put(&self, key: &str, body: &[u8], now: i64) {
        let Ok(connection) = self.connection.lock() else {
            return;
        };
        // Un échec d'écriture laisse le cache tel quel : on l'ignore.
        let _ = connection.execute(
            "INSERT OR REPLACE INTO responses (cache_key, body, fetched_at) VALUES (?1, ?2, ?3)",
            params![key, body, now],
        );
    }

    /// Retire les entrées de plus de 30 jours, puis les plus anciennes au-delà
    /// de 400.
    fn prune(&self, now: i64) {
        let Ok(connection) = self.connection.lock() else {
            return;
        };
        let _ = connection.execute(
            "DELETE FROM responses WHERE fetched_at < ?1",
            [now - MAX_AGE],
        );
        let _ = connection.execute(
            "DELETE FROM responses WHERE cache_key NOT IN (
                SELECT cache_key FROM responses ORDER BY fetched_at DESC LIMIT ?1
            )",
            [MAX_ENTRIES],
        );
    }

    #[cfg(test)]
    fn count(&self) -> i64 {
        let connection = self.connection.lock().unwrap();
        connection
            .query_row("SELECT COUNT(*) FROM responses", [], |row| row.get(0))
            .unwrap()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Base en mémoire : même SQL que sur disque, sans fichier.
    fn memory_cache(now: i64) -> Cache {
        Cache::open(":memory:", now).unwrap()
    }

    #[test]
    fn stores_and_replaces_a_response() {
        let cache = memory_cache(1_000);
        assert!(cache.get("movie/1").is_none());
        cache.put("movie/1", b"un", 1_000);
        cache.put("movie/1", b"deux", 1_100);
        let entry = cache.get("movie/1").unwrap();
        assert_eq!(entry.body, b"deux");
        assert_eq!(cache.count(), 1);
    }

    #[test]
    fn an_entry_is_fresh_for_five_minutes_only() {
        let cache = memory_cache(10_000);
        cache.put("k", b"x", 10_000);
        let entry = cache.get("k").unwrap();
        assert!(entry.is_fresh(10_000));
        assert!(entry.is_fresh(10_299));
        assert!(!entry.is_fresh(10_300));
        // Horloge réglée en arrière : jamais fraîche.
        assert!(!entry.is_fresh(9_000));
    }

    #[test]
    fn prune_drops_entries_older_than_thirty_days() {
        let now = 100 * 24 * 60 * 60;
        let cache = memory_cache(now);
        cache.put("vieille", b"x", now - MAX_AGE - 1);
        cache.put("limite", b"x", now - MAX_AGE);
        cache.put("recente", b"x", now - 60);
        cache.prune(now);
        assert!(cache.get("vieille").is_none());
        assert!(cache.get("limite").is_some());
        assert!(cache.get("recente").is_some());
    }

    #[test]
    fn prune_keeps_the_most_recent_entries_up_to_the_cap() {
        let now = 1_000_000;
        let cache = memory_cache(now);
        for i in 0..450 {
            cache.put(&format!("k{i}"), b"x", now - 1_000 + i);
        }
        cache.prune(now);
        assert_eq!(cache.count(), MAX_ENTRIES);
        // Les 50 plus anciennes sont parties, la plus récente reste.
        assert!(cache.get("k0").is_none());
        assert!(cache.get("k49").is_none());
        assert!(cache.get("k50").is_some());
        assert!(cache.get("k449").is_some());
    }

    #[test]
    fn an_empty_path_gives_no_cache() {
        assert!(Cache::open("", 0).is_none());
        assert!(Cache::open("   ", 0).is_none());
    }

    #[test]
    fn an_unusable_path_gives_no_cache() {
        // Un dossier n'est pas une base SQLite.
        let directory = std::env::temp_dir();
        assert!(Cache::open(directory.to_str().unwrap(), 0).is_none());
        assert!(Cache::open("/inexistant/dossier/cache.sqlite", 0).is_none());
    }
}
