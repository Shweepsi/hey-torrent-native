//! Erreurs du cœur, exposées à Swift et Kotlin comme énumération.

/// Erreur renvoyée par les fonctions faillibles du cœur.
///
/// Le cœur ne produit aucun texte affiché : les messages ci-dessous ne servent
/// qu'aux journaux. Chaque interface traduit le *type* d'erreur en français.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// Défaillance interne inattendue (invariant rompu, runtime absent…).
    #[error("internal error")]
    Internal,
}
