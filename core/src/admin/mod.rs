//! Code d'administration, compilé seulement avec la fonctionnalité `admin`
//! (édition admin Android). L'app publique n'en contient aucun symbole.
//!
//! - [`text`], [`release`], [`scoring`] : lecture et tri des noms de releases ;
//! - [`torznab`], [`search`] : recherche sur les sources de l'administrateur ;
//! - [`qbittorrent`] : envoi et suivi des téléchargements ;
//! - [`settings`] : réglages des serveurs ;
//! - [`worker`] : routes d'administration du Worker (demandes, réglages
//!   chiffrés, accès des comptes) ;
//! - [`space`] : l'objet exposé à l'app, qui relie le tout.

mod qbittorrent;
mod release;
mod scoring;
mod search;
mod settings;
mod space;
mod text;
mod torznab;
mod worker;

pub use qbittorrent::{Download, DownloadState};
pub use release::{AudioLanguage, ReleaseInfo, ReleaseSource, Resolution, VideoCodec};
pub use search::{
    Release, SearchObserver, SearchOutcome, SearchQuery, SourceFailure, SourceReport,
};
pub use settings::{QbitAuth, QbitSettings, ServerSettings, TorznabSource};
pub use space::AdminSpace;
pub use worker::{AdminRequest, AllowedEmail, Member, RequestFilter, RequestFollower};

/// Erreur d'une opération d'administration. L'app traduit chaque cas en
/// français ; le texte ci-dessous ne sert qu'aux journaux et ne contient
/// jamais d'adresse ni d'identifiant.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AdminError {
    /// Worker injoignable (pas de réseau, délai dépassé).
    #[error("network unavailable")]
    Network,
    /// Session expirée : se reconnecter.
    #[error("signed out")]
    SignedOut,
    /// Le compte n'est pas administrateur.
    #[error("forbidden")]
    Forbidden,
    /// Demande ou compte introuvable (supprimé entre-temps).
    #[error("not found")]
    NotFound,
    /// L'état de la demande a changé entre-temps : relire la file. Pour un
    /// compte : l'administrateur ne change pas d'état.
    #[error("conflict")]
    Conflict,
    /// Erreur du Worker (5xx).
    #[error("server error")]
    Server,
    /// Réglages incomplets ou invalides (adresse non HTTPS, champ vide…), ou
    /// adresse e-mail mal formée.
    #[error("invalid settings")]
    InvalidSettings,
    /// Aucune source active ou qBittorrent non réglé.
    #[error("not configured")]
    NotConfigured,
    /// qBittorrent ne répond pas (adresse, réseau, certificat, délai).
    #[error("qbittorrent unreachable")]
    QbitUnreachable,
    /// qBittorrent refuse l'identifiant, le mot de passe ou le jeton.
    #[error("qbittorrent authentication failed")]
    QbitAuthFailed,
    /// qBittorrent a banni l'adresse après trop d'échecs de connexion.
    #[error("qbittorrent banned this address")]
    QbitBanned,
    /// Version de qBittorrent trop ancienne (avant 4.1).
    #[error("qbittorrent version unsupported")]
    QbitUnsupportedVersion,
    /// qBittorrent a refusé le torrent (lien invalide, déjà présent).
    #[error("qbittorrent rejected the torrent")]
    QbitRejected,
    /// Torrent introuvable dans qBittorrent.
    #[error("torrent not found")]
    TorrentNotFound,
    /// Résultat d'une ancienne recherche : relancer la recherche.
    #[error("release expired")]
    ReleaseExpired,
    /// Le torrent est parti vers qBittorrent mais le Worker n'a pas
    /// enregistré l'état « en cours » : réessayer l'enregistrement.
    #[error("status not saved")]
    StatusNotSaved,
    /// Réponse imprévue.
    #[error("unexpected response")]
    Unexpected,
    /// Défaillance interne.
    #[error("internal error")]
    Internal,
}

/// Réponse de test de l'édition admin, vérifiée par l'app Android admin.
#[uniffi::export]
pub fn admin_ping() -> String {
    "admin-pong".to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn admin_ping_answers_admin_pong() {
        assert_eq!(admin_ping(), "admin-pong");
    }
}
