//! Connexion Google sur iPhone, sans SDK Google : flux OAuth « application
//! installée » avec PKCE. L'app ouvre `url` dans une session web du système
//! (`ASWebAuthenticationSession`) et rend au cœur l'adresse de retour ; le cœur
//! vérifie l'état, échange le code contre un jeton d'identité et le renvoie.
//!
//! Sur Android, le Credential Manager du système donne directement le jeton :
//! ce module ne sert pas.

use serde::Deserialize;

use crate::account::{AccountError, new_sign_in_nonce, random_token, sha256_hex};
use crate::api;

const AUTHORIZE_URL: &str = "https://accounts.google.com/o/oauth2/v2/auth";
const TOKEN_URL: &str = "https://oauth2.googleapis.com/token";

/// Une tentative de connexion Google en cours. Elle se garde le temps de la
/// fenêtre de connexion, puis se passe à [`complete_google_sign_in`].
#[derive(Debug, Clone, uniffi::Record)]
pub struct GoogleAuthorization {
    /// Page de connexion Google à ouvrir.
    pub url: String,
    /// Schéma d'URL de retour, attendu par la session web du système.
    pub callback_scheme: String,
    pub redirect_uri: String,
    pub client_id: String,
    pub state: String,
    pub code_verifier: String,
    /// Nonce brut, à envoyer au serveur avec le jeton.
    pub nonce: String,
}

/// `com.googleusercontent.apps.XXX` à partir de `XXX.apps.googleusercontent.com`.
fn reversed_client_id(client_id: &str) -> Result<String, AccountError> {
    let prefix = client_id
        .strip_suffix(".apps.googleusercontent.com")
        .filter(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '-'))
        .ok_or(AccountError::InvalidConfiguration)?;
    Ok(format!("com.googleusercontent.apps.{prefix}"))
}

/// Prépare la page de connexion Google pour le client iOS `client_id`.
#[uniffi::export]
pub fn begin_google_sign_in(client_id: String) -> Result<GoogleAuthorization, AccountError> {
    let callback_scheme = reversed_client_id(&client_id)?;
    let redirect_uri = format!("{callback_scheme}:/oauth2redirect");
    let state = random_token(24)?;
    let code_verifier = random_token(48)?;
    let challenge = {
        use base64::Engine;
        use sha2::Digest;
        base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(sha2::Sha256::digest(code_verifier.as_bytes()))
    };
    let nonce = new_sign_in_nonce()?.raw;
    let mut url = url::Url::parse(AUTHORIZE_URL).map_err(|_| AccountError::Internal)?;
    url.query_pairs_mut()
        .append_pair("client_id", &client_id)
        .append_pair("redirect_uri", &redirect_uri)
        .append_pair("response_type", "code")
        .append_pair("scope", "openid email profile")
        .append_pair("state", &state)
        .append_pair("nonce", &nonce)
        .append_pair("code_challenge", &challenge)
        .append_pair("code_challenge_method", "S256")
        .append_pair("prompt", "select_account");
    Ok(GoogleAuthorization {
        url: url.into(),
        callback_scheme,
        redirect_uri,
        client_id,
        state,
        code_verifier,
        nonce,
    })
}

#[derive(Deserialize)]
struct TokenResponse {
    id_token: String,
}

/// Code d'autorisation lu dans l'adresse de retour, après contrôle de l'état.
fn authorization_code(
    authorization: &GoogleAuthorization,
    callback_url: &str,
) -> Result<String, AccountError> {
    let url = url::Url::parse(callback_url).map_err(|_| AccountError::Unexpected)?;
    if url.scheme() != authorization.callback_scheme {
        return Err(AccountError::Unexpected);
    }
    let mut code = None;
    let mut state = None;
    let mut error = None;
    for (key, value) in url.query_pairs() {
        match key.as_ref() {
            "code" => code = Some(value.into_owned()),
            "state" => state = Some(value.into_owned()),
            "error" => error = Some(value.into_owned()),
            _ => {}
        }
    }
    if error.as_deref() == Some("access_denied") {
        return Err(AccountError::Cancelled);
    }
    // Un état différent : réponse qui ne vient pas de cette tentative.
    if state.as_deref().map(sha256_hex) != Some(sha256_hex(&authorization.state)) {
        return Err(AccountError::InvalidIdentity);
    }
    code.filter(|c| !c.is_empty())
        .ok_or(AccountError::InvalidIdentity)
}

pub(crate) async fn exchange_code(
    http: &reqwest::Client,
    token_url: &str,
    authorization: &GoogleAuthorization,
    callback_url: &str,
) -> Result<String, AccountError> {
    let code = authorization_code(authorization, callback_url)?;
    let form = [
        ("client_id", authorization.client_id.as_str()),
        ("code", code.as_str()),
        ("code_verifier", authorization.code_verifier.as_str()),
        ("redirect_uri", authorization.redirect_uri.as_str()),
        ("grant_type", "authorization_code"),
    ];
    let response = http
        .post(token_url)
        .form(&form)
        .send()
        .await
        .map_err(api::transport_error)?;
    if response.status().is_client_error() {
        // Code expiré ou déjà utilisé : recommencer la connexion.
        return Err(AccountError::InvalidIdentity);
    }
    if !response.status().is_success() {
        return Err(AccountError::ProviderUnavailable);
    }
    let tokens: TokenResponse = response
        .json()
        .await
        .map_err(|_| AccountError::Unexpected)?;
    Ok(tokens.id_token)
}

/// Termine la connexion Google : renvoie le jeton d'identité à passer à
/// `Account::sign_in` avec `authorization.nonce`.
#[uniffi::export(async_runtime = "tokio")]
pub async fn complete_google_sign_in(
    authorization: GoogleAuthorization,
    callback_url: String,
) -> Result<String, AccountError> {
    let http = api::http_client()?;
    exchange_code(&http, TOKEN_URL, &authorization, &callback_url).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{body_string_contains, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    const CLIENT: &str = "123-abc.apps.googleusercontent.com";

    fn query(url: &str) -> std::collections::HashMap<String, String> {
        url::Url::parse(url)
            .unwrap()
            .query_pairs()
            .into_owned()
            .collect()
    }

    #[test]
    fn builds_a_pkce_authorization_url() {
        let auth = begin_google_sign_in(CLIENT.into()).unwrap();
        assert_eq!(auth.callback_scheme, "com.googleusercontent.apps.123-abc");
        assert_eq!(
            auth.redirect_uri,
            "com.googleusercontent.apps.123-abc:/oauth2redirect"
        );
        assert!(auth.url.starts_with(AUTHORIZE_URL));
        let q = query(&auth.url);
        assert_eq!(q["client_id"], CLIENT);
        assert_eq!(q["response_type"], "code");
        assert_eq!(q["scope"], "openid email profile");
        assert_eq!(q["state"], auth.state);
        assert_eq!(q["nonce"], auth.nonce);
        assert_eq!(q["code_challenge_method"], "S256");
        assert_ne!(q["code_challenge"], auth.code_verifier);
        assert!(auth.code_verifier.len() >= 43);
    }

    #[test]
    fn each_attempt_is_unique() {
        let a = begin_google_sign_in(CLIENT.into()).unwrap();
        let b = begin_google_sign_in(CLIENT.into()).unwrap();
        assert_ne!(a.state, b.state);
        assert_ne!(a.nonce, b.nonce);
        assert_ne!(a.code_verifier, b.code_verifier);
    }

    #[test]
    fn rejects_a_client_id_that_is_not_an_ios_client() {
        assert!(matches!(
            begin_google_sign_in("autre".into()),
            Err(AccountError::InvalidConfiguration)
        ));
        assert!(matches!(
            begin_google_sign_in("a/b.apps.googleusercontent.com".into()),
            Err(AccountError::InvalidConfiguration)
        ));
    }

    #[test]
    fn reads_the_callback() {
        let auth = begin_google_sign_in(CLIENT.into()).unwrap();
        let ok = format!(
            "{}:/oauth2redirect?state={}&code=le-code",
            auth.callback_scheme, auth.state
        );
        assert_eq!(authorization_code(&auth, &ok).unwrap(), "le-code");

        let wrong_state = format!(
            "{}:/oauth2redirect?state=autre&code=le-code",
            auth.callback_scheme
        );
        assert!(matches!(
            authorization_code(&auth, &wrong_state),
            Err(AccountError::InvalidIdentity)
        ));

        let denied = format!(
            "{}:/oauth2redirect?error=access_denied&state={}",
            auth.callback_scheme, auth.state
        );
        assert!(matches!(
            authorization_code(&auth, &denied),
            Err(AccountError::Cancelled)
        ));

        let other_scheme = format!("https://example.com/?state={}&code=x", auth.state);
        assert!(matches!(
            authorization_code(&auth, &other_scheme),
            Err(AccountError::Unexpected)
        ));
    }

    #[tokio::test]
    async fn exchanges_the_code_with_the_verifier() {
        let server = MockServer::start().await;
        let auth = begin_google_sign_in(CLIENT.into()).unwrap();
        Mock::given(method("POST"))
            .and(path("/token"))
            .and(body_string_contains("code=le-code"))
            .and(body_string_contains(
                format!("code_verifier={}", auth.code_verifier).as_str(),
            ))
            .and(body_string_contains("grant_type=authorization_code"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "id_token": "jeton-google", "access_token": "x", "expires_in": 3600
            })))
            .expect(1)
            .mount(&server)
            .await;
        let callback = format!(
            "{}:/oauth2redirect?state={}&code=le-code",
            auth.callback_scheme, auth.state
        );
        let http = api::http_client().unwrap();
        let token = exchange_code(&http, &format!("{}/token", server.uri()), &auth, &callback)
            .await
            .unwrap();
        assert_eq!(token, "jeton-google");
    }

    #[tokio::test]
    async fn maps_token_endpoint_failures() {
        let server = MockServer::start().await;
        let auth = begin_google_sign_in(CLIENT.into()).unwrap();
        let callback = format!(
            "{}:/oauth2redirect?state={}&code=c",
            auth.callback_scheme, auth.state
        );
        let http = api::http_client().unwrap();
        let url = format!("{}/token", server.uri());

        Mock::given(method("POST"))
            .respond_with(
                ResponseTemplate::new(400)
                    .set_body_json(serde_json::json!({"error": "invalid_grant"})),
            )
            .up_to_n_times(1)
            .mount(&server)
            .await;
        assert!(matches!(
            exchange_code(&http, &url, &auth, &callback).await,
            Err(AccountError::InvalidIdentity)
        ));

        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(503))
            .up_to_n_times(1)
            .mount(&server)
            .await;
        assert!(matches!(
            exchange_code(&http, &url, &auth, &callback).await,
            Err(AccountError::ProviderUnavailable)
        ));

        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(200).set_body_string("pas du json"))
            .mount(&server)
            .await;
        assert!(matches!(
            exchange_code(&http, &url, &auth, &callback).await,
            Err(AccountError::Unexpected)
        ));
    }
}
