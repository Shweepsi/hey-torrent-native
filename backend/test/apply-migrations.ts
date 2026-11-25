import { applyD1Migrations } from "cloudflare:test";
import { env } from "cloudflare:workers";

// Chaque fichier de test part d'une base vide, au schéma des migrations.
await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
