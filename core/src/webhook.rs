//! Webhook des nouveaux ajouts sur Plex (administrateur seulement, compilé
//! avec la fonctionnalité `admin`) : URL secrètes à coller dans Plex et
//! Tautulli. Le serveur ne les montre qu'une fois, à leur création.

use serde::Deserialize;

use crate::account::{Account, AccountError};
use crate::api;

const WEBHOOK_PATH: &str = "/v1/notifications/webhook";

/// URL créées par le serveur ; elles remplacent les précédentes.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record, Deserialize)]
pub struct WebhookUrls {
    /// À coller dans Plex : Réglages, Webhooks.
    pub plex_url: String,
    /// À coller dans Tautulli : agent de notification « Webhook ».
    pub tautulli_url: String,
    /// Corps JSON à coller dans Tautulli, événement « Recently Added ».
    pub tautulli_body: String,
}

/// État du webhook, sans l'URL.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record, Deserialize)]
pub struct WebhookStatus {
    /// Une URL active existe.
    pub configured: bool,
    /// Création de l'URL active, en secondes Unix.
    pub created_at: Option<i64>,
    /// Dernier appel reçu de Plex ou de Tautulli, en secondes Unix.
    pub last_used_at: Option<i64>,
}

#[uniffi::export(async_runtime = "tokio")]
impl Account {
    /// État du webhook ([`AccountError::Forbidden`] hors administrateur).
    pub async fn webhook_status(&self) -> Result<WebhookStatus, AccountError> {
        let url = self.base.join(WEBHOOK_PATH);
        let response = self
            .authorized(|http, access| http.get(&url).bearer_auth(access))
            .await?;
        api::read(response).await
    }

    /// Crée de nouvelles URL et révoque les anciennes.
    pub async fn regenerate_webhook(&self) -> Result<WebhookUrls, AccountError> {
        let url = self.base.join(WEBHOOK_PATH);
        let response = self
            .authorized(|http, access| {
                http.post(&url)
                    .bearer_auth(access)
                    .json(&serde_json::json!({}))
            })
            .await?;
        api::read(response).await
    }
}
