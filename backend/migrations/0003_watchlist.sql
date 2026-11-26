-- Jalon 4 : demandes et watchlists.
-- Une demande par titre TMDB (film ou série), partagée par toutes les
-- personnes qui l'ont ajoutée à leur watchlist : les « suiveurs » d'une demande
-- sont les entrées vivantes de `watchlist_items` qui la désignent.
-- Les dates sont en secondes Unix, posées par le Worker.

CREATE TABLE requests (
  id TEXT PRIMARY KEY,
  media_type TEXT NOT NULL CHECK (media_type IN ('movie', 'tv')),
  tmdb_id INTEGER NOT NULL,
  -- Titre, affiche (chemin TMDB `/xxx.jpg`) et année lus par le Worker chez
  -- TMDB, jamais fournis par l'app.
  title TEXT NOT NULL,
  poster_path TEXT,
  year INTEGER,
  status TEXT NOT NULL DEFAULT 'requested'
    CHECK (status IN ('requested', 'in_progress', 'available', 'refused')),
  -- Motif court d'un refus, écrit par l'administrateur (jalon 5).
  refusal_reason TEXT,
  created_at INTEGER NOT NULL,
  -- Dernier changement d'état.
  updated_at INTEGER NOT NULL,
  UNIQUE (media_type, tmdb_id)
);
CREATE INDEX requests_status ON requests(status, created_at);

-- Watchlist de chaque compte. Suppression logique (`deleted_at`) : la ligne
-- reste, la dernière modification reçue par le Worker l'emporte.
CREATE TABLE watchlist_items (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  media_type TEXT NOT NULL CHECK (media_type IN ('movie', 'tv')),
  tmdb_id INTEGER NOT NULL,
  request_id TEXT REFERENCES requests(id) ON DELETE SET NULL,
  -- Série : numéros de saisons voulues, en JSON (`[1,2]`) ; NULL pour un film
  -- ou une série entière.
  seasons TEXT,
  added_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  PRIMARY KEY (user_id, media_type, tmdb_id)
);
CREATE INDEX watchlist_items_request ON watchlist_items(request_id);
