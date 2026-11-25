//! Compte et session face à une API simulée (wiremock) : jamais le vrai Worker.

// Fichier de test : les assistants hors `#[test]` ont droit à `unwrap`.
#![allow(clippy::unwrap_used)]

use std::sync::{Arc, Mutex};

use heywatchlist_core::{
    Account, AccountError, IdentityProvider, SessionStore, SignInRequest, UserRole,
};
use serde_json::json;
use wiremock::matchers::{body_partial_json, header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

/// Coffre en mémoire, à la place du Keychain ou du Keystore.
#[derive(Default)]
struct MemoryStore(Mutex<Option<String>>);

impl SessionStore for MemoryStore {
    fn load(&self) -> Option<String> {
        self.0.lock().unwrap().clone()
    }
    fn save(&self, value: String) {
        *self.0.lock().unwrap() = Some(value);
    }
    fn clear(&self) {
        *self.0.lock().unwrap() = None;
    }
}

impl MemoryStore {
    fn value(&self) -> Option<String> {
        self.0.lock().unwrap().clone()
    }
}

/// Adresse locale où rien n'écoute (wiremock garde ses serveurs arrêtés en
/// réserve : ils répondraient encore).
fn unreachable_uri() -> String {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    format!("http://127.0.0.1:{port}")
}

/// Même compte, mais dont le serveur ne répond plus.
async fn offline_copy(store: Arc<MemoryStore>) -> Arc<Account> {
    Account::new(unreachable_uri(), store).unwrap()
}

fn user(name: Option<&str>) -> serde_json::Value {
    json!({ "id": "u-1", "role": "member", "display_name": name, "providers": ["apple"] })
}

fn session_body(access: &str, refresh: &str, expires_in: u64) -> serde_json::Value {
    json!({
        "access_token": access,
        "access_expires_in": expires_in,
        "refresh_token": refresh,
        "user": user(Some("Camille")),
    })
}

fn request(code: Option<&str>) -> SignInRequest {
    SignInRequest {
        provider: IdentityProvider::Apple,
        id_token: "jeton-apple".into(),
        nonce: "nonce-brut".into(),
        invitation_code: code.map(Into::into),
        given_name: Some("Camille".into()),
    }
}

async fn signed_in(server: &MockServer, store: Arc<MemoryStore>, expires_in: u64) -> Arc<Account> {
    Mock::given(method("POST"))
        .and(path("/v1/auth/sign-in"))
        .respond_with(ResponseTemplate::new(200).set_body_json(session_body(
            "acces-1",
            "refresh-1",
            expires_in,
        )))
        .up_to_n_times(1)
        .mount(server)
        .await;
    let account = Account::new(server.uri(), store).unwrap();
    account.sign_in(request(None)).await.unwrap();
    account
}

#[tokio::test]
async fn sign_in_sends_the_token_and_keeps_the_session() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/sign-in"))
        .and(body_partial_json(json!({
            "provider": "apple",
            "id_token": "jeton-apple",
            "nonce": "nonce-brut",
            "given_name": "Camille",
        })))
        .respond_with(ResponseTemplate::new(200).set_body_json(session_body(
            "acces-1",
            "refresh-1",
            900,
        )))
        .expect(1)
        .mount(&server)
        .await;
    let store = Arc::new(MemoryStore::default());
    let account = Account::new(server.uri(), store.clone()).unwrap();
    assert_eq!(account.current_user().await, None);

    let profile = account.sign_in(request(None)).await.unwrap();
    assert_eq!(profile.display_name.as_deref(), Some("Camille"));
    assert_eq!(profile.role, UserRole::Member);
    assert!(profile.has_apple);
    assert_eq!(account.current_user().await, Some(profile.clone()));

    // Le coffre garde le jeton de rafraîchissement, jamais le jeton d'accès.
    let stored = store.value().unwrap();
    assert!(stored.contains("refresh-1"));
    assert!(!stored.contains("acces-1"));

    // Au lancement suivant, la session est reprise sans réseau.
    let relaunched = Account::new(server.uri(), store).unwrap();
    assert_eq!(relaunched.current_user().await, Some(profile));
}

#[tokio::test]
async fn sign_in_reports_a_missing_or_invalid_invitation() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/sign-in"))
        .and(body_partial_json(
            json!({ "invitation_code": "AAAA-BBBB-CCCC" }),
        ))
        .respond_with(
            ResponseTemplate::new(403).set_body_json(json!({ "error": "invitation_invalid" })),
        )
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/sign-in"))
        .respond_with(
            ResponseTemplate::new(403).set_body_json(json!({ "error": "invitation_required" })),
        )
        .mount(&server)
        .await;
    let store = Arc::new(MemoryStore::default());
    let account = Account::new(server.uri(), store.clone()).unwrap();

    assert!(matches!(
        account.sign_in(request(None)).await,
        Err(AccountError::InvitationRequired)
    ));
    // Un code vide n'est pas envoyé.
    assert!(matches!(
        account.sign_in(request(Some("  "))).await,
        Err(AccountError::InvitationRequired)
    ));
    assert!(matches!(
        account.sign_in(request(Some("AAAA-BBBB-CCCC"))).await,
        Err(AccountError::InvitationInvalid)
    ));
    assert_eq!(store.value(), None);
}

#[tokio::test]
async fn sign_in_maps_server_and_transport_failures() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(401).set_body_json(json!({ "error": "invalid_token" })))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .respond_with(
            ResponseTemplate::new(503).set_body_json(json!({ "error": "provider_unavailable" })),
        )
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(500).set_body_string("<html>"))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200).set_body_string("{\"partiel\": true}"))
        .mount(&server)
        .await;
    let account = Account::new(server.uri(), Arc::new(MemoryStore::default())).unwrap();
    assert!(matches!(
        account.sign_in(request(None)).await,
        Err(AccountError::InvalidIdentity)
    ));
    assert!(matches!(
        account.sign_in(request(None)).await,
        Err(AccountError::ProviderUnavailable)
    ));
    assert!(matches!(
        account.sign_in(request(None)).await,
        Err(AccountError::Server)
    ));
    assert!(matches!(
        account.sign_in(request(None)).await,
        Err(AccountError::Unexpected)
    ));

    // Serveur injoignable : erreur réseau, pas de plantage.
    let offline = Account::new(unreachable_uri(), Arc::new(MemoryStore::default())).unwrap();
    assert!(matches!(
        offline.sign_in(request(None)).await,
        Err(AccountError::Network)
    ));
}

#[test]
fn rejects_an_insecure_server_address() {
    let store = Arc::new(MemoryStore::default());
    assert!(matches!(
        Account::new("http://api.example.com".into(), store.clone()),
        Err(AccountError::InvalidConfiguration)
    ));
    assert!(matches!(
        Account::new(String::new(), store),
        Err(AccountError::InvalidConfiguration)
    ));
}

#[test]
fn forgets_an_unreadable_stored_session() {
    let store = Arc::new(MemoryStore::default());
    store.save("{ ancienne version".into());
    let account = Account::new("https://api.example.com".into(), store.clone()).unwrap();
    assert_eq!(tokio_block_on(account.current_user()), None);
    assert_eq!(store.value(), None);
}

fn tokio_block_on<F: std::future::Future>(future: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(future)
}

#[tokio::test]
async fn refresh_profile_uses_the_access_token() {
    let server = MockServer::start().await;
    let store = Arc::new(MemoryStore::default());
    let account = signed_in(&server, store.clone(), 900).await;
    Mock::given(method("GET"))
        .and(path("/v1/me"))
        .and(header("authorization", "Bearer acces-1"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "user": user(Some("Cam")) })),
        )
        .expect(1)
        .mount(&server)
        .await;
    let profile = account.refresh_profile().await.unwrap();
    assert_eq!(profile.display_name.as_deref(), Some("Cam"));
    assert!(store.value().unwrap().contains("Cam"));
}

#[tokio::test]
async fn renews_an_expiring_access_token_before_the_call() {
    let server = MockServer::start().await;
    let store = Arc::new(MemoryStore::default());
    // Durée de vie sous la marge de renouvellement : le jeton est périmé d'emblée.
    let account = signed_in(&server, store.clone(), 30).await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/refresh"))
        .and(body_partial_json(json!({ "refresh_token": "refresh-1" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "access_token": "acces-2", "access_expires_in": 900, "refresh_token": "refresh-2"
        })))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/me"))
        .and(header("authorization", "Bearer acces-2"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "user": user(Some("Camille")) })),
        )
        .expect(1)
        .mount(&server)
        .await;
    account.refresh_profile().await.unwrap();
    let stored = store.value().unwrap();
    assert!(stored.contains("refresh-2") && !stored.contains("refresh-1"));
}

#[tokio::test]
async fn retries_once_after_a_rejected_access_token() {
    let server = MockServer::start().await;
    let account = signed_in(&server, Arc::new(MemoryStore::default()), 900).await;
    Mock::given(method("GET"))
        .and(path("/v1/me"))
        .and(header("authorization", "Bearer acces-1"))
        .respond_with(ResponseTemplate::new(401).set_body_json(json!({ "error": "unauthorized" })))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/refresh"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "access_token": "acces-2", "access_expires_in": 900, "refresh_token": "refresh-2"
        })))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/me"))
        .and(header("authorization", "Bearer acces-2"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "user": user(Some("Camille")) })),
        )
        .expect(1)
        .mount(&server)
        .await;
    assert!(account.refresh_profile().await.is_ok());
}

#[tokio::test]
async fn an_expired_session_signs_out() {
    let server = MockServer::start().await;
    let store = Arc::new(MemoryStore::default());
    let account = signed_in(&server, store.clone(), 30).await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/refresh"))
        .respond_with(
            ResponseTemplate::new(401).set_body_json(json!({ "error": "session_expired" })),
        )
        .expect(1)
        .mount(&server)
        .await;
    assert!(matches!(
        account.refresh_profile().await,
        Err(AccountError::SignedOut)
    ));
    assert_eq!(account.current_user().await, None);
    assert_eq!(store.value(), None);
    // Plus de session : aucune requête de plus.
    assert!(matches!(
        account.refresh_profile().await,
        Err(AccountError::SignedOut)
    ));
}

#[tokio::test]
async fn a_refresh_without_network_keeps_the_session() {
    let server = MockServer::start().await;
    let store = Arc::new(MemoryStore::default());
    signed_in(&server, store.clone(), 30).await;
    // Au relancement, le jeton d'accès est absent : il faut rafraîchir.
    let account = offline_copy(store.clone()).await;
    assert!(matches!(
        account.refresh_profile().await,
        Err(AccountError::Network)
    ));
    assert!(account.current_user().await.is_some());
    assert!(store.value().is_some());
}

#[tokio::test]
async fn sign_out_clears_the_session_and_tells_the_server() {
    let server = MockServer::start().await;
    let store = Arc::new(MemoryStore::default());
    let account = signed_in(&server, store.clone(), 900).await;
    Mock::given(method("POST"))
        .and(path("/v1/auth/sign-out"))
        .and(body_partial_json(json!({ "refresh_token": "refresh-1" })))
        .respond_with(ResponseTemplate::new(204))
        .expect(1)
        .mount(&server)
        .await;
    account.sign_out().await;
    assert_eq!(account.current_user().await, None);
    assert_eq!(store.value(), None);
}

#[tokio::test]
async fn sign_out_works_offline() {
    let server = MockServer::start().await;
    let store = Arc::new(MemoryStore::default());
    signed_in(&server, store.clone(), 900).await;
    let account = offline_copy(store.clone()).await;
    assert!(account.current_user().await.is_some());
    account.sign_out().await;
    assert_eq!(store.value(), None);
}

#[tokio::test]
async fn delete_account_sends_the_apple_code_then_forgets_everything() {
    let server = MockServer::start().await;
    let store = Arc::new(MemoryStore::default());
    let account = signed_in(&server, store.clone(), 900).await;
    Mock::given(method("DELETE"))
        .and(path("/v1/me"))
        .and(header("authorization", "Bearer acces-1"))
        .and(body_partial_json(
            json!({ "apple_authorization_code": "code-apple" }),
        ))
        .respond_with(ResponseTemplate::new(204))
        .expect(1)
        .mount(&server)
        .await;
    account
        .delete_account(Some("code-apple".into()))
        .await
        .unwrap();
    assert_eq!(account.current_user().await, None);
    assert_eq!(store.value(), None);
}

#[tokio::test]
async fn a_failed_deletion_keeps_the_session() {
    let server = MockServer::start().await;
    let store = Arc::new(MemoryStore::default());
    let account = signed_in(&server, store.clone(), 900).await;
    Mock::given(method("DELETE"))
        .and(path("/v1/me"))
        .respond_with(ResponseTemplate::new(500).set_body_json(json!({ "error": "internal" })))
        .mount(&server)
        .await;
    assert!(matches!(
        account.delete_account(None).await,
        Err(AccountError::Server)
    ));
    assert!(account.current_user().await.is_some());
}

#[tokio::test]
async fn google_client_ids_come_from_the_server() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/config"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "google_web_client_id": "web.apps.googleusercontent.com",
            "google_ios_client_id": "",
        })))
        .mount(&server)
        .await;
    let account = Account::new(server.uri(), Arc::new(MemoryStore::default())).unwrap();
    let ids = account.google_client_ids().await.unwrap();
    assert_eq!(ids.web.as_deref(), Some("web.apps.googleusercontent.com"));
    assert_eq!(ids.ios, None);
}

#[test]
fn nonces_are_random_and_hashed_for_apple() {
    let a = heywatchlist_core::new_sign_in_nonce().unwrap();
    let b = heywatchlist_core::new_sign_in_nonce().unwrap();
    assert_ne!(a.raw, b.raw);
    assert!(a.raw.len() >= 43);
    assert_eq!(a.sha256.len(), 64);
    assert!(a.sha256.chars().all(|c| c.is_ascii_hexdigit()));
}

#[cfg(feature = "admin")]
#[tokio::test]
async fn admin_creates_an_invitation() {
    let server = MockServer::start().await;
    let account = signed_in(&server, Arc::new(MemoryStore::default()), 900).await;
    Mock::given(method("POST"))
        .and(path("/v1/invitations"))
        .and(header("authorization", "Bearer acces-1"))
        .respond_with(ResponseTemplate::new(201).set_body_json(json!({
            "code": "ABCD-EFGH-JKMN", "expires_at": 1_800_000_000
        })))
        .mount(&server)
        .await;
    let invitation = account.create_invitation().await.unwrap();
    assert_eq!(invitation.code, "ABCD-EFGH-JKMN");
    assert_eq!(invitation.expires_at, 1_800_000_000);
}

#[cfg(feature = "admin")]
#[tokio::test]
async fn a_member_cannot_create_an_invitation() {
    let server = MockServer::start().await;
    let account = signed_in(&server, Arc::new(MemoryStore::default()), 900).await;
    Mock::given(method("POST"))
        .and(path("/v1/invitations"))
        .respond_with(ResponseTemplate::new(403).set_body_json(json!({ "error": "forbidden" })))
        .mount(&server)
        .await;
    assert!(matches!(
        account.create_invitation().await,
        Err(AccountError::Forbidden)
    ));
}
