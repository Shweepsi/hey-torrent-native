/**
 * Envoi des push : APNs (iPhone, jeton d'authentification ES256) et FCM HTTP v1
 * (Android, compte de service Google). Le texte affiché n'est pas écrit ici :
 * le Worker envoie des clés de traduction (`loc-key`, `body_loc_key`) que
 * chaque app résout dans ses fichiers de ressources. Seul le titre du média,
 * tel que Plex le donne, voyage en clair.
 *
 * Aucun jeton d'appareil, clé ni réponse brute n'est journalisé.
 */
import { SignJWT, importPKCS8 } from "jose";
import type { FetchLike } from "./apple";
import type { Env } from "./env";

const APNS_HOST = "https://api.push.apple.com";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";
const TIMEOUT_MS = 10_000;
/** APNs refuse un jeton de plus d'une heure et en limite le renouvellement. */
const APNS_JWT_TTL = 50 * 60;

export type Platform = "ios" | "android";

/** Ce que dit la notification, sans texte affiché. */
export interface PushMessage {
  /** Titre du film ou de la série. */
  title: string;
  /** Clé de traduction du corps (identique sur iOS et Android). */
  bodyKey: string;
  /** Arguments du corps (numéros de saison et d'épisode). */
  bodyArgs: string[];
  /** Regroupe et remplace les notifications d'un même titre. */
  collapseKey: string;
  /** Pour ouvrir la fiche à l'appui sur la notification. */
  mediaType: "movie" | "tv";
  tmdbId: number;
}

/** `sent` : accepté ; `gone` : jeton périmé, à oublier ; `failed` : à réessayer plus tard. */
export type PushOutcome = "sent" | "gone" | "failed";

export interface PushSender {
  send(platform: Platform, token: string, message: PushMessage): Promise<PushOutcome>;
}

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

function errorName(error: unknown): string {
  return error instanceof Error ? error.name : typeof error;
}

function parseServiceAccount(raw: string | undefined): ServiceAccount | null {
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as Partial<ServiceAccount>;
    if (
      typeof value.project_id === "string" &&
      typeof value.client_email === "string" &&
      typeof value.private_key === "string"
    ) {
      return {
        project_id: value.project_id,
        client_email: value.client_email,
        private_key: value.private_key,
        ...(typeof value.token_uri === "string" ? { token_uri: value.token_uri } : {}),
      };
    }
  } catch {
    // Secret illisible : traité comme absent.
  }
  return null;
}

/** Jeton réutilisable jusqu'à `expiresAt` (secondes Unix). */
interface CachedToken {
  value: string;
  expiresAt: number;
}

/**
 * Expéditeur réel. Les jetons d'APNs et de Google sont gardés en mémoire le
 * temps de leur validité : une rafale de push ne signe qu'une fois.
 */
export class LivePushSender implements PushSender {
  private apnsToken: CachedToken | null = null;
  private googleToken: CachedToken | null = null;

  constructor(
    private readonly env: Env,
    private readonly fetcher: FetchLike,
    private readonly now: () => number,
  ) {}

  async send(platform: Platform, token: string, message: PushMessage): Promise<PushOutcome> {
    try {
      return platform === "ios" ? await this.sendApns(token, message) : await this.sendFcm(token, message);
    } catch (error) {
      console.warn("push failed", platform, errorName(error));
      return "failed";
    }
  }

  private async apnsJwt(): Promise<string | null> {
    const now = this.now();
    if (this.apnsToken && this.apnsToken.expiresAt > now) return this.apnsToken.value;
    const { APNS_KEY_ID: keyId, APPLE_TEAM_ID: teamId, APNS_PRIVATE_KEY: pem } = this.env;
    if (!keyId || !teamId || !pem) return null;
    const key = await importPKCS8(pem, "ES256");
    const value = await new SignJWT({})
      .setProtectedHeader({ alg: "ES256", kid: keyId })
      .setIssuer(teamId)
      .setIssuedAt(now)
      .sign(key);
    this.apnsToken = { value, expiresAt: now + APNS_JWT_TTL };
    return value;
  }

  private async sendApns(token: string, message: PushMessage): Promise<PushOutcome> {
    if (!/^[0-9a-fA-F]{32,200}$/.test(token)) return "gone";
    const jwt = await this.apnsJwt();
    if (!jwt) {
      console.warn("push skipped: apns not configured");
      return "failed";
    }
    const body = {
      aps: {
        alert: { title: message.title, "loc-key": message.bodyKey, "loc-args": message.bodyArgs },
        sound: "default",
        "thread-id": message.collapseKey,
      },
      media_type: message.mediaType,
      tmdb_id: message.tmdbId,
    };
    const response = await this.fetcher(`${APNS_HOST}/3/device/${token}`, {
      method: "POST",
      headers: {
        authorization: `bearer ${jwt}`,
        "apns-topic": this.env.APPLE_CLIENT_ID,
        "apns-push-type": "alert",
        "apns-priority": "10",
        "apns-collapse-id": message.collapseKey.slice(0, 64),
        "content-type": "application/json",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (response.ok) return "sent";
    const reason = await apnsReason(response);
    if (response.status === 410 || reason === "BadDeviceToken" || reason === "DeviceTokenNotForTopic") return "gone";
    if (reason === "ExpiredProviderToken" || reason === "InvalidProviderToken") this.apnsToken = null;
    console.warn("apns refused", response.status, reason ?? "unknown");
    return "failed";
  }

  private async googleAccessToken(account: ServiceAccount): Promise<string> {
    const now = this.now();
    if (this.googleToken && this.googleToken.expiresAt > now + 60) return this.googleToken.value;
    const tokenUri = account.token_uri ?? GOOGLE_TOKEN_URI;
    const key = await importPKCS8(account.private_key, "RS256");
    const assertion = await new SignJWT({ scope: FCM_SCOPE })
      .setProtectedHeader({ alg: "RS256", typ: "JWT" })
      .setIssuer(account.client_email)
      .setAudience(tokenUri)
      .setIssuedAt(now)
      .setExpirationTime(now + 3600)
      .sign(key);
    const response = await this.fetcher(tokenUri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }).toString(),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!response.ok) throw new Error(`google token ${response.status}`);
    const body = (await response.json()) as { access_token?: unknown; expires_in?: unknown };
    if (typeof body.access_token !== "string") throw new Error("google token unreadable");
    const lifetime = typeof body.expires_in === "number" ? body.expires_in : 3600;
    this.googleToken = { value: body.access_token, expiresAt: now + lifetime };
    return body.access_token;
  }

  private async sendFcm(token: string, message: PushMessage): Promise<PushOutcome> {
    const account = parseServiceAccount(this.env.FCM_SERVICE_ACCOUNT);
    if (!account) {
      console.warn("push skipped: fcm not configured");
      return "failed";
    }
    const accessToken = await this.googleAccessToken(account);
    const body = {
      message: {
        token,
        data: { media_type: message.mediaType, tmdb_id: String(message.tmdbId) },
        android: {
          priority: "HIGH",
          collapse_key: message.collapseKey,
          notification: {
            title: message.title,
            body_loc_key: message.bodyKey,
            body_loc_args: message.bodyArgs,
            channel_id: ANDROID_CHANNEL,
            tag: message.collapseKey,
          },
        },
      },
    };
    const response = await this.fetcher(
      `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.project_id)}/messages:send`,
      {
        method: "POST",
        headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      },
    );
    if (response.ok) return "sent";
    const status = await fcmStatus(response);
    if (response.status === 404 || status === "UNREGISTERED") return "gone";
    if (response.status === 401) this.googleToken = null;
    console.warn("fcm refused", response.status, status ?? "unknown");
    return "failed";
  }
}

/** Canal Android des notifications « disponible » (créé par l'app). */
export const ANDROID_CHANNEL = "available";

async function apnsReason(response: Response): Promise<string | undefined> {
  try {
    const body = (await response.json()) as { reason?: unknown };
    return typeof body.reason === "string" ? body.reason : undefined;
  } catch {
    return undefined;
  }
}

/** Statut d'erreur FCM (`UNREGISTERED`…), y compris dans les détails. */
async function fcmStatus(response: Response): Promise<string | undefined> {
  try {
    const body = (await response.json()) as {
      error?: { status?: unknown; details?: { errorCode?: unknown }[] };
    };
    const detail = body.error?.details?.find((d) => typeof d.errorCode === "string")?.errorCode;
    if (typeof detail === "string") return detail;
    return typeof body.error?.status === "string" ? body.error.status : undefined;
  } catch {
    return undefined;
  }
}
