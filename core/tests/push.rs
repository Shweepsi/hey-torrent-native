//! Enregistrement push et webhook face à une API simulée (wiremock) : jamais le vrai Worker.

// Fichier de test : les assistants hors `#[test]` ont droit à `unwrap`.
#![allow(clippy::unwrap_used)]

use std::sync::{Arc, Mutex};

use heywatchlist_core::{
    Account, AccountError, IdentityProvider, PushPlatform, SessionStore, SignInRequest,
};
use serde_json::json;
use wiremock::matchers::{body_json, header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

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

async fn signed_in(server: &MockServer) -> Arc<Account> {
    Mock::given(method("POST"))
        .and(path("/v1/auth/sign-in"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "access_token": "acces-1",
            "access_expires_in": 900,
            "refresh_token": "refresh-1",
            "user": { "id": "u-1", "role": "admin", "display_name": "Loïc", "providers": ["apple"] },
        })))
        .up_to_n_times(1)
        .mount(server)
        .await;
    let account = Account::new(server.uri(), Arc::new(MemoryStore::default())).unwrap();
    account
        .sign_in(SignInRequest {
            provider: IdentityProvider::Apple,
            id_token: "jeton-apple".into(),
            nonce: "nonce-brut".into(),
            given_name: None,
        })
        .await
        .unwrap();
    account
}

#[tokio::test]
async fn registers_the_push_token_of_this_device() {
    let server = MockServer::start().await;
    let account = signed_in(&server).await;
    Mock::given(method("PUT"))
        .and(path("/v1/devices"))
        .and(header("authorization", "Bearer acces-1"))
        .and(body_json(
            json!({ "platform": "ios", "token": "abcdef0123" }),
        ))
        .respond_with(ResponseTemplate::new(204))
        .expect(1)
        .mount(&server)
        .await;
    account
        .register_push_device(PushPlatform::Ios, "  abcdef0123 ".into())
        .await
        .unwrap();
}

#[tokio::test]
async fn refuses_an_empty_token_without_calling_the_server() {
    let server = MockServer::start().await;
    let account = signed_in(&server).await;
    assert!(matches!(
        account
            .register_push_device(PushPlatform::Android, "  ".into())
            .await,
        Err(AccountError::Unexpected)
    ));
}

#[tokio::test]
async fn unregisters_the_device() {
    let server = MockServer::start().await;
    let account = signed_in(&server).await;
    Mock::given(method("DELETE"))
        .and(path("/v1/devices"))
        .respond_with(ResponseTemplate::new(204))
        .expect(1)
        .mount(&server)
        .await;
    account.unregister_push_device().await.unwrap();
}

#[cfg(feature = "admin")]
#[tokio::test]
async fn admin_reads_and_regenerates_the_webhook() {
    let server = MockServer::start().await;
    let account = signed_in(&server).await;
    Mock::given(method("GET"))
        .and(path("/v1/notifications/webhook"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "configured": true, "created_at": 1_800_000_000, "last_used_at": null
        })))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/v1/notifications/webhook"))
        .respond_with(ResponseTemplate::new(201).set_body_json(json!({
            "plex_url": "https://api.test/v1/hooks/plex/secret",
            "tautulli_url": "https://api.test/v1/hooks/tautulli/secret",
            "tautulli_body": "{}"
        })))
        .mount(&server)
        .await;
    let status = account.webhook_status().await.unwrap();
    assert!(status.configured);
    assert_eq!(status.last_used_at, None);
    let urls = account.regenerate_webhook().await.unwrap();
    assert!(urls.plex_url.ends_with("/plex/secret"));
}

#[cfg(feature = "admin")]
#[tokio::test]
async fn a_member_cannot_see_the_webhook() {
    let server = MockServer::start().await;
    let account = signed_in(&server).await;
    Mock::given(method("GET"))
        .and(path("/v1/notifications/webhook"))
        .respond_with(ResponseTemplate::new(403).set_body_json(json!({ "error": "forbidden" })))
        .mount(&server)
        .await;
    assert!(matches!(
        account.webhook_status().await,
        Err(AccountError::Forbidden)
    ));
}
