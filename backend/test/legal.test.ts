import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { accountDeletion, privacyPolicy } from "../src/legal";
import { call } from "./fakes";

describe("pages publiques des stores", () => {
  it("sert la politique de confidentialité en HTML", async () => {
    const response = await call("GET", "/confidentialite");
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("text/html; charset=utf-8");
    expect(response.headers.get("content-security-policy")).toContain("default-src 'none'");
    const html = await response.text();
    expect(html).toContain("Politique de confidentialité");
    expect(html).toContain("Crashlytics");
    expect(html).toContain('href="/suppression-du-compte"');
  });

  it("sert la page de suppression du compte", async () => {
    const response = await call("GET", "/suppression-du-compte");
    expect(response.status).toBe(200);
    expect(await response.text()).toContain("Supprimer mon compte");
  });

  it("refuse une autre méthode", async () => {
    expect((await call("POST", "/confidentialite", {})).status).toBe(404);
  });

  it("renvoie vers l'administrateur sans contact réglé", async () => {
    const { PRIVACY_CONTACT: _ignored, ...withoutContact } = env;
    const html = await privacyPolicy(withoutContact).text();
    expect(html).toContain("l’administrateur du serveur");
  });

  it("met une adresse en lien et échappe le contact", async () => {
    expect(await privacyPolicy({ ...env, PRIVACY_CONTACT: "moi@example.com" }).text()).toContain(
      '<a href="mailto:moi@example.com">moi@example.com</a>',
    );
    const html = await accountDeletion({ ...env, PRIVACY_CONTACT: "<script>x</script> Loïc" }).text();
    expect(html).not.toContain("<script>x");
    expect(html).toContain("&lt;script&gt;x&lt;/script&gt; Loïc");
  });
});
