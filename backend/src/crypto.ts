/** Petites briques cryptographiques, sur Web Crypto. */
import { ApiError } from "./http";

const encoder = new TextEncoder();

export function toHex(bytes: ArrayBuffer | Uint8Array): string {
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, "0")).join("");
}

export function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

export function fromBase64(value: string): Uint8Array {
  const normalized = value.trim().replaceAll("-", "+").replaceAll("_", "/");
  const binary = atob(normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), "="));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

export async function sha256Hex(value: string): Promise<string> {
  return toHex(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}

export function randomToken(byteLength = 32): string {
  return toBase64Url(crypto.getRandomValues(new Uint8Array(byteLength)));
}

/** Comparaison en temps constant de deux chaînes (par leur condensé). */
export async function safeEqual(a: string, b: string): Promise<boolean> {
  const [ha, hb] = await Promise.all([sha256Hex(a), sha256Hex(b)]);
  let diff = 0;
  for (let i = 0; i < ha.length; i++) diff |= ha.charCodeAt(i) ^ hb.charCodeAt(i);
  return diff === 0;
}

/** Forme canonique d'un code saisi (code d'amorçage) : majuscules, sans espaces ni tirets. */
export function normalizeCode(code: string): string {
  return code.toUpperCase().replace(/[\s-]/g, "");
}

/** Clé AES-256-GCM des réglages des serveurs (secret `CREDENTIALS_KEY`, 32 octets en base64). */
async function credentialsKey(env: { CREDENTIALS_KEY?: string }): Promise<CryptoKey> {
  // Absente ou trop courte : le Worker n'est pas configuré, rien n'est gardé.
  const raw = env.CREDENTIALS_KEY ? fromBase64(env.CREDENTIALS_KEY) : new Uint8Array();
  if (raw.length !== 32) throw new ApiError("provider_unavailable");
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

/** Chiffre un texte ; `aad` le lie à son contexte (le compte). */
export async function encryptText(
  env: { CREDENTIALS_KEY?: string },
  plaintext: string,
  aad: string,
): Promise<{ iv: string; ciphertext: string }> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const sealed = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: encoder.encode(aad) },
    await credentialsKey(env),
    encoder.encode(plaintext),
  );
  return { iv: toBase64Url(iv), ciphertext: toBase64Url(new Uint8Array(sealed)) };
}

/** Déchiffre un texte de [`encryptText`] ; `internal` s'il a été altéré ou si la clé a changé. */
export async function decryptText(
  env: { CREDENTIALS_KEY?: string },
  iv: string,
  ciphertext: string,
  aad: string,
): Promise<string> {
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: fromBase64(iv), additionalData: encoder.encode(aad) },
      await credentialsKey(env),
      fromBase64(ciphertext),
    );
    return new TextDecoder().decode(plain);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError("internal");
  }
}
