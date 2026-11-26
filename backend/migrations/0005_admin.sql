-- Jalon 5 : espace d'administration.
-- Les dates sont en secondes Unix, posées par le Worker.

-- Traitement d'une demande par l'administrateur : la release envoyée à
-- qBittorrent (son nom seulement, jamais son lien) et la date du dernier
-- geste (envoi, refus, réouverture).
ALTER TABLE requests ADD COLUMN release_title TEXT;
-- Empreinte du torrent quand elle est connue (lien magnet), pour le suivi.
ALTER TABLE requests ADD COLUMN torrent_hash TEXT;
ALTER TABLE requests ADD COLUMN handled_at INTEGER;

-- Réglages des serveurs de l'administrateur (sources Torznab, qBittorrent…),
-- chiffrés en AES-256-GCM par le Worker avec le secret CREDENTIALS_KEY, qui
-- n'est jamais dans D1. Le texte clair n'est connu que du Worker, le temps
-- d'une requête, et n'est renvoyé qu'au compte administrateur.
CREATE TABLE credentials (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  -- Vecteur d'initialisation (12 octets) et texte chiffré avec son
  -- étiquette d'authentification, en base64.
  iv TEXT NOT NULL,
  ciphertext TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
