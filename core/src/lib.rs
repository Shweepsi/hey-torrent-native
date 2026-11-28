//! Cœur partagé de HeyWatchlist.
//!
//! Toute la logique vit ici ; les interfaces SwiftUI et Compose ne font
//! qu'afficher. Les liaisons Swift et Kotlin sont générées par UniFFI à partir
//! des macros `#[uniffi::export]` (jamais de fichier UDL) et ne sont pas
//! versionnées.

mod account;
#[cfg(feature = "admin")]
mod admin;
mod api;
mod cache;
mod catalog;
mod error;
mod google;
mod push;
mod tmdb;
mod watchlist;
#[cfg(feature = "admin")]
mod webhook;

pub use account::{
    AccessStatus, Account, AccountError, GoogleClientIds, IdentityProvider, SessionStore,
    SignInNonce, SignInRequest, UserProfile, UserRole, new_sign_in_nonce,
};
#[cfg(feature = "admin")]
pub use admin::{
    AdminError, AdminRequest, AdminSpace, AllowedEmail, AssistantStatus, AudioLanguage,
    AutoSourceReport, AutomationStatus, ClearedRequests, Download, DownloadState, Member,
    PlexSettings, PlexStatus, QbitAuth, QbitSettings, Recommendation, Release, ReleaseInfo,
    ReleaseSource, RequestFilter, RequestFollower, Resolution, SearchObserver, SearchOutcome,
    SearchQuery, ServerSettings, SourceFailure, SourceReport, TorznabSource, VideoCodec,
};
pub use catalog::{
    CastMember, Catalog, CatalogError, Episode, Genre, MediaType, Provider, Providers,
    SeasonDetail, SeasonSummary, Shelf, TitleDetail, TitlePage, TitleSummary, Video, VideoKind,
};
pub use error::CoreError;
pub use google::{GoogleAuthorization, begin_google_sign_in, complete_google_sign_in};
pub use push::{PushPlatform, TestPushResult};
pub use watchlist::{RequestStatus, Watchlist, WatchlistError, WatchlistItem, WatchlistSnapshot};
#[cfg(feature = "admin")]
pub use webhook::{WebhookStatus, WebhookUrls};

uniffi::setup_scaffolding!();

/// Version du cœur, identique à celle de l'app (`[workspace.package]`).
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

/// Test de bout en bout du pont : les deux apps affichent cette réponse.
#[uniffi::export]
pub fn ping() -> String {
    "pong".to_owned()
}

/// Variante asynchrone de [`ping`], exécutée sur le runtime tokio d'UniFFI.
///
/// Échoue avec [`CoreError::Internal`] si aucun runtime tokio n'est actif :
/// l'interface affiche alors une erreur au lieu d'un plantage.
#[uniffi::export(async_runtime = "tokio")]
pub async fn ping_async() -> Result<String, CoreError> {
    tokio::runtime::Handle::try_current().map_err(|_| CoreError::Internal)?;
    Ok(ping())
}

/// Adresse de la politique de confidentialité, servie par le Worker sous
/// `/confidentialite`. `None` si l'adresse du serveur est absente ou invalide :
/// l'interface masque alors le lien.
#[uniffi::export]
pub fn privacy_policy_url(base_url: String) -> Option<String> {
    api::BaseUrl::parse(&base_url)
        .ok()
        .map(|base| base.join("/confidentialite"))
}

/// Indique si cette bibliothèque contient le code d'administration.
#[uniffi::export]
pub fn has_admin() -> bool {
    cfg!(feature = "admin")
}

#[cfg(test)]
mod tests {
    #[test]
    fn privacy_policy_url_follows_the_server_address() {
        assert_eq!(
            super::privacy_policy_url("https://api.example.com/".to_owned()).as_deref(),
            Some("https://api.example.com/confidentialite")
        );
        assert_eq!(super::privacy_policy_url(String::new()), None);
        assert_eq!(
            super::privacy_policy_url("http://api.example.com".to_owned()),
            None
        );
    }

    use std::future::Future;
    use std::pin::pin;
    use std::sync::Arc;
    use std::task::{Context, Poll, Wake, Waker};
    use std::thread::{self, Thread};

    use super::*;

    /// Réveille le thread qui attend le futur.
    struct ThreadWaker(Thread);

    impl Wake for ThreadWaker {
        fn wake(self: Arc<Self>) {
            self.0.unpark();
        }
    }

    /// Exécuteur minimal, sans runtime tokio ambiant : reproduit ce que fait
    /// le code généré par UniFFI côté Swift et Kotlin.
    fn block_on<F: Future>(future: F) -> F::Output {
        let mut future = pin!(future);
        let waker = Waker::from(Arc::new(ThreadWaker(thread::current())));
        let mut context = Context::from_waker(&waker);
        loop {
            match future.as_mut().poll(&mut context) {
                Poll::Ready(output) => return output,
                Poll::Pending => thread::park(),
            }
        }
    }

    #[test]
    fn ping_answers_pong() {
        assert_eq!(ping(), "pong");
    }

    #[test]
    fn core_version_is_a_semver_triplet() {
        let version = core_version();
        let parts: Vec<&str> = version.split('.').collect();
        assert_eq!(parts.len(), 3, "version inattendue : {version}");
        assert!(parts.iter().all(|p| p.parse::<u32>().is_ok()));
    }

    #[test]
    fn core_version_comes_from_the_workspace_manifest() {
        let manifest = include_str!("../../Cargo.toml");
        let section = manifest.split("[workspace.package]").nth(1).unwrap();
        let line = section
            .lines()
            .find(|line| line.starts_with("version = "))
            .unwrap();
        assert_eq!(line, format!("version = \"{}\"", core_version()));
    }

    #[cfg(not(feature = "admin"))]
    #[test]
    fn has_admin_is_false_without_the_admin_feature() {
        assert!(!has_admin());
    }

    #[cfg(feature = "admin")]
    #[test]
    fn has_admin_is_true_with_the_admin_feature() {
        assert!(has_admin());
    }

    #[tokio::test]
    async fn ping_async_answers_pong() {
        assert_eq!(ping_async().await.unwrap(), "pong");
    }

    #[test]
    fn ping_async_fails_without_a_tokio_runtime() {
        assert!(matches!(block_on(ping_async()), Err(CoreError::Internal)));
    }

    #[test]
    fn ping_async_works_under_the_uniffi_tokio_wrapper() {
        let wrapped = uniffi::deps::async_compat::Compat::new(ping_async());
        assert_eq!(block_on(wrapped).unwrap(), "pong");
    }
}
