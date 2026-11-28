-- Recherche automatique par le Worker et connexion à Plex (2026-11-28).
-- Les dates sont en secondes Unix, posées par le Worker.

-- Release qu'une recherche automatique a trouvée et que Claude juge
-- convenable (son nom seulement, jamais son lien), avec sa raison. Tant
-- qu'il n'y en a pas, une demande « demandée » reste hors de « À traiter »
-- (si la recherche automatique est réglée).
ALTER TABLE requests ADD COLUMN candidate_title TEXT;
ALTER TABLE requests ADD COLUMN candidate_reason TEXT;
ALTER TABLE requests ADD COLUMN candidate_found_at INTEGER;
-- Dernière recherche automatique, et empreinte des noms trouvés alors :
-- si rien n'a changé depuis un « aucune ne convient », Claude n'est pas
-- rappelé.
ALTER TABLE requests ADD COLUMN auto_checked_at INTEGER;
ALTER TABLE requests ADD COLUMN auto_fingerprint TEXT;
-- Début de l'attente actuelle (création ou réouverture) et alerte déjà
-- envoyée à l'administrateur pour une attente trop longue.
ALTER TABLE requests ADD COLUMN waiting_since INTEGER;
ALTER TABLE requests ADD COLUMN stale_notified_at INTEGER;
UPDATE requests SET waiting_since = COALESCE(handled_at, created_at) WHERE status = 'requested';

-- État des tâches planifiées (une seule ligne) : dernier passage, échecs des
-- sources vus depuis Cloudflare, dernière lecture de la bibliothèque Plex.
CREATE TABLE automation (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  searched_at INTEGER,
  -- JSON `[{"id": "…", "failure": "timeout" | null}]` : jamais d'adresse ni de clé.
  source_reports TEXT,
  -- Dernier appel à Claude : `ok`, `auth_failed` ou `unavailable`.
  assistant_status TEXT,
  plex_synced_at INTEGER,
  -- `ok`, `unreachable`, `auth_failed` ou `invalid_response`.
  plex_status TEXT,
  plex_items INTEGER
);
INSERT INTO automation (id) VALUES (1);
