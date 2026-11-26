-- Jalon 6 : notifications « Disponible sur Plex ».
-- Appareils qui reçoivent les push, médias arrivés sur Plex, arrivées déjà
-- traitées (dédoublonnage entre Plex et Tautulli) et jeton secret des webhooks.
-- Les dates sont en secondes Unix, posées par le Worker.

-- Un appareil par jeton push. Il est rattaché à la session qui l'a enregistré :
-- une session coupée ou expirée ne reçoit plus rien, la suppression du compte
-- efface ses appareils.
CREATE TABLE devices (
  token TEXT PRIMARY KEY,
  platform TEXT NOT NULL CHECK (platform IN ('ios', 'android')),
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX devices_user ON devices(user_id);
CREATE INDEX devices_session ON devices(session_id);

-- Titres TMDB vus arriver sur Plex par les webhooks (une série dès son premier
-- épisode).
CREATE TABLE library (
  media_type TEXT NOT NULL CHECK (media_type IN ('movie', 'tv')),
  tmdb_id INTEGER NOT NULL,
  first_seen_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  PRIMARY KEY (media_type, tmdb_id)
);

-- Arrivées déjà traitées : `event_key` vaut `movie:<id>`, `tv:<id>`,
-- `tv:<id>:s<saison>` ou `tv:<id>:s<saison>e<épisode>`. Une même arrivée reçue
-- de Plex puis de Tautulli n'est traitée qu'une fois. `pushed` vaut 1 si une
-- notification est partie (sert à regrouper les épisodes d'une même saison).
CREATE TABLE notified (
  event_key TEXT PRIMARY KEY,
  media_type TEXT NOT NULL CHECK (media_type IN ('movie', 'tv')),
  tmdb_id INTEGER NOT NULL,
  season INTEGER,
  pushed INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX notified_media ON notified(media_type, tmdb_id, created_at);

-- Jeton secret des URL de webhook (Plex et Tautulli). Seul son condensé
-- SHA-256 est gardé ; un seul jeton actif, régénérable par l'administrateur.
CREATE TABLE webhook_tokens (
  token_hash TEXT PRIMARY KEY,
  created_by TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  last_used_at INTEGER,
  revoked_at INTEGER
);
