-- Accès sur validation, à la place des codes d'invitation : le lien de l'app
-- (App Store non répertorié, test fermé Play) sert d'invitation ; tout
-- nouveau compte attend que l'administrateur l'accepte.
-- Les dates sont en secondes Unix.

-- Les comptes déjà créés (avec une invitation) restent actifs.
ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'active'
  CHECK (status IN ('active', 'pending', 'refused'));
-- Dernière décision de l'administrateur (acceptation, refus).
ALTER TABLE users ADD COLUMN status_changed_at INTEGER;
CREATE INDEX users_status ON users(status);

-- Adresses acceptées d'avance (proche prévenu, compte de démonstration pour
-- la vérification d'Apple) : un compte dont l'adresse vérifiée par Apple ou
-- Google figure ici est actif dès sa création. En minuscules.
CREATE TABLE allowed_emails (
  email TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);

DROP TABLE invitations;
