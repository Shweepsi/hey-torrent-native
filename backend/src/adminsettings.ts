/**
 * Réglages des serveurs de l'administrateur, tels que le Worker les lit pour
 * ses propres tâches (recommandation, recherche automatique, Plex). Le texte
 * est produit par le cœur (`ServerSettings`, JSON) et gardé chiffré ; il
 * n'est déchiffré que le temps d'un appel et rien n'en est journalisé.
 */
import { decryptText } from "./crypto";
import type { Env } from "./env";
import type { TorznabSource } from "./torznab";

/** Données associées au chiffrement : un texte chiffré ne se recolle pas sur un autre compte. */
export const settingsAad = (userId: string) => `heywatchlist-credentials:${userId}`;

export interface PlexConnection {
  url: string;
  token: string;
}

/** Ce que le Worker utilise des réglages. Tout champ illisible est ignoré. */
export interface WorkerSettings {
  sources: TorznabSource[];
  excludedTerms: string[];
  anthropicKey: string | null;
  rules: string;
  plex: PlexConnection | null;
}

const str = (value: unknown) => (typeof value === "string" ? value.trim() : "");

function source(value: unknown): TorznabSource | null {
  if (typeof value !== "object" || value === null) return null;
  const v = value as Record<string, unknown>;
  const result = { id: str(v.id), name: str(v.name), url: str(v.url), api_key: str(v.api_key), enabled: v.enabled === true };
  return result.id && result.url && result.api_key ? result : null;
}

export function readSettings(raw: unknown): WorkerSettings {
  const v = typeof raw === "object" && raw !== null && !Array.isArray(raw) ? (raw as Record<string, unknown>) : {};
  const plex = typeof v.plex === "object" && v.plex !== null ? (v.plex as Record<string, unknown>) : null;
  const plexUrl = str(plex?.url);
  const plexToken = str(plex?.token);
  return {
    sources: Array.isArray(v.sources) ? v.sources.flatMap((s) => source(s) ?? []) : [],
    excludedTerms: Array.isArray(v.excluded_terms) ? v.excluded_terms.map(str).filter((t) => t.length > 0) : [],
    anthropicKey: str(v.anthropic_api_key) || null,
    rules: str(v.release_rules),
    plex: plexUrl && plexToken ? { url: plexUrl, token: plexToken } : null,
  };
}

/** Réglages déchiffrés d'un compte ; `null` s'il n'y en a pas ou s'ils sont illisibles. */
export async function loadSettings(env: Env, userId: string): Promise<WorkerSettings | null> {
  const row = await env.DB.prepare("SELECT iv, ciphertext FROM credentials WHERE user_id = ?")
    .bind(userId)
    .first<{ iv: string; ciphertext: string }>();
  if (!row) return null;
  try {
    return readSettings(JSON.parse(await decryptText(env, row.iv, row.ciphertext, settingsAad(userId))));
  } catch {
    return null;
  }
}

/** La recherche automatique tourne : une clé Anthropic et au moins une source active. */
export function automationEnabled(settings: WorkerSettings | null): boolean {
  return settings !== null && settings.anthropicKey !== null && settings.sources.some((s) => s.enabled);
}
