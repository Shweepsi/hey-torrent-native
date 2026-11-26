import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// Les tests tournent dans workerd, avec une D1 locale vide à chaque fichier.
// Aucun appel aux vrais services : Apple, Google et TMDB sont simulés (test/fakes.ts).
export default defineConfig({
  plugins: [
    cloudflareTest(async () => ({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        bindings: {
          TEST_MIGRATIONS: await readD1Migrations(path.join(import.meta.dirname, "migrations")),
          SESSION_SIGNING_KEY: "dGVzdC1zaWduaW5nLWtleS1vZi0zMi1ieXRlcy0hISE=",
          BOOTSTRAP_CODE: "BOOT-STRA-P234",
          GOOGLE_WEB_CLIENT_ID: "web-client.apps.googleusercontent.com",
          GOOGLE_IOS_CLIENT_ID: "ios-client.apps.googleusercontent.com",
          // Fausse clé v3 (32 caractères hexadécimaux) : TMDB est simulé.
          TMDB_API_KEY: "fa4efa4efa4efa4efa4efa4efa4efa4e",
        },
      },
    })),
  ],
  test: {
    setupFiles: ["./test/apply-migrations.ts"],
  },
});
