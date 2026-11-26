-- Jalon 2 : comptes, identités Apple et Google, invitations, sessions.
-- Les tables des jalons suivants (watchlist, demandes, appareils, identifiants
-- chiffrés, bibliothèque Plex) arrivent avec leur jalon, dans une nouvelle
-- migration. Chacune référencera `users(id)` avec ON DELETE CASCADE, pour que
-- la suppression d'un compte efface tout ce qui lui appartient.
-- Les dates sont en secondes Unix.

CREATE TABLE users (
  id TEXT PRIMARY KEY,
  role TEXT NOT NULL CHECK (role IN ('admin', 'member')),
  -- Prénom affiché dans l'app ; fourni par Apple (une seule fois) ou Google.
  display_name TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

-- Un compte peut avoir une identité Apple et une identité Google.
CREATE TABLE identities (
  provider TEXT NOT NULL CHECK (provider IN ('apple', 'google')),
  -- Identifiant stable de la personne chez le fournisseur (`sub`).
  subject TEXT NOT NULL,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- Adresse vérifiée par le fournisseur, en minuscules : sert à rattacher une
  -- deuxième identité au même compte. NULL si non vérifiée.
  email TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (provider, subject)
);
CREATE INDEX identities_user ON identities(user_id);
CREATE INDEX identities_email ON identities(email);

-- Codes d'invitation : seul leur condensé SHA-256 est gardé.
CREATE TABLE invitations (
  id TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL UNIQUE,
  created_by TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used_by TEXT REFERENCES users(id) ON DELETE SET NULL,
  used_at INTEGER,
  revoked_at INTEGER
);

-- Une session par appareil connecté. Le jeton de rafraîchissement n'est gardé
-- que haché ; il change à chaque rafraîchissement. `previous_hash` permet de
-- repérer la réutilisation d'un ancien jeton (vol probable) et de couper la
-- session.
CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash TEXT NOT NULL UNIQUE,
  previous_hash TEXT,
  created_at INTEGER NOT NULL,
  last_used_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER
);
CREATE INDEX sessions_user ON sessions(user_id);
CREATE INDEX sessions_previous ON sessions(previous_hash);

-- Nonces déjà acceptés : un jeton d'identité Apple ou Google ne sert qu'une fois.
CREATE TABLE used_nonces (
  nonce_hash TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);
