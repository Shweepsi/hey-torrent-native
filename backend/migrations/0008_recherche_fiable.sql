-- Recherche automatique et lecture de Plex plus fiables (2026-11-28).

-- Recherches sans release convenable depuis le début de l'attente : les
-- essais s'espacent (6 h, puis 24 h, puis 3 jours) pour ménager les sources.
ALTER TABLE requests ADD COLUMN auto_attempts INTEGER NOT NULL DEFAULT 0;
-- La dernière recherche n'a pas pu se faire (Claude en échec, ou toutes les
-- sources en échec depuis Cloudflare) : la demande reste « à traiter », à
-- chercher depuis l'app.
ALTER TABLE requests ADD COLUMN auto_failed INTEGER NOT NULL DEFAULT 0;
-- Remise en attente à la main d'une demande « disponible » : la lecture de la
-- bibliothèque Plex ne la repasse pas « disponible » toute seule (le titre y
-- est encore) ; seule une nouvelle arrivée (webhook) ou l'administrateur le fait.
ALTER TABLE requests ADD COLUMN reopened_at INTEGER;
