//! Code d'administration, compilé seulement avec la fonctionnalité `admin`.

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
