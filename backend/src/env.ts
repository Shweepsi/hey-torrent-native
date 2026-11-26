/** Liaisons et secrets du Worker (voir backend/README.md). */
export interface Env {
  DB: D1Database;
  /** Identifiant de l'app iPhone, audience des jetons Apple (`wrangler.toml`). */
  APPLE_CLIENT_ID: string;
  /** Clé HMAC des jetons d'accès, en base64 (32 octets au moins). Secret. */
  SESSION_SIGNING_KEY: string;
  /** Code qui crée le compte administrateur tant qu'il n'existe pas. Secret. */
  BOOTSTRAP_CODE?: string;
  /** Client OAuth « Application Web » de Google : audience des jetons Android. */
  GOOGLE_WEB_CLIENT_ID?: string;
  /** Client OAuth « iOS » de Google : audience des jetons obtenus sur iPhone. */
  GOOGLE_IOS_CLIENT_ID?: string;
  /**
   * Clé d'API v3 (32 caractères hexadécimaux) ou jeton v4 (`eyJ…`) de TMDB,
   * utilisée par le relais `/v1/tmdb/`. Secret : jamais journalisée ni renvoyée.
   */
  TMDB_API_KEY?: string;
  /** Équipe Apple et clé « Sign in with Apple » : révocation à la suppression. */
  APPLE_TEAM_ID?: string;
  APPLE_SIGNIN_KEY_ID?: string;
  APPLE_SIGNIN_PRIVATE_KEY?: string;
}
