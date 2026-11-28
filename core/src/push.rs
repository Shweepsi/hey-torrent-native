//! Notifications push « disponible sur Plex » : l'app donne au cœur le jeton
//! push du système (APNs ou FCM), le cœur l'enregistre auprès du Worker pour la
//! session en cours. Le jeton n'est ni journalisé ni gardé par le cœur.

use crate::account::{Account, AccountError};
use crate::api;

/// Service push du téléphone.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PushPlatform {
    /// APNs (iPhone).
    Ios,
    /// Firebase Cloud Messaging (Android).
    Android,
}

impl PushPlatform {
    fn wire(self) -> &'static str {
        match self {
            Self::Ios => "ios",
            Self::Android => "android",
        }
    }
}

const DEVICES_PATH: &str = "/v1/devices";

#[uniffi::export(async_runtime = "tokio")]
impl Account {
    /// Enregistre le jeton push de cet appareil (à rappeler à chaque nouveau
    /// jeton donné par le système et après chaque connexion).
    pub async fn register_push_device(
        &self,
        platform: PushPlatform,
        token: String,
    ) -> Result<(), AccountError> {
        let token = token.trim().to_owned();
        if token.is_empty() {
            return Err(AccountError::Unexpected);
        }
        let body = serde_json::json!({ "platform": platform.wire(), "token": token });
        let url = self.base.join(DEVICES_PATH);
        let response = self
            .authorized(|http, access| http.put(&url).bearer_auth(access).json(&body))
            .await?;
        api::read_empty(response).await
    }

    /// Cet appareil ne reçoit plus de notification (notifications coupées
    /// dans l'app). La déconnexion suffit aussi : le serveur ignore les
    /// appareils d'une session fermée.
    pub async fn unregister_push_device(&self) -> Result<(), AccountError> {
        let url = self.base.join(DEVICES_PATH);
        let response = self
            .authorized(|http, access| http.delete(&url).bearer_auth(access))
            .await?;
        api::read_empty(response).await
    }
}
