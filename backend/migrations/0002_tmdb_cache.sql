-- Jalon 3 : cache des réponses TMDB relayées par `/v1/tmdb/…`.
-- La Cache API ne fonctionne pas sur `workers.dev` : D1 la remplace.
-- Données publiques et partagées par tous les comptes : aucun lien avec `users`.
-- Les dates sont en secondes Unix.

CREATE TABLE tmdb_cache (
  -- Chemin TMDB et paramètres triés. Jamais la clé d'API.
  cache_key TEXT PRIMARY KEY,
  -- Corps JSON de TMDB, tel que reçu.
  body TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
-- La purge des entrées expirées les retrouve par date.
CREATE INDEX tmdb_cache_expires ON tmdb_cache(expires_at);
