import Foundation

/// Point commun du compte et du catalogue : l'unique `Account` du processus.
///
/// Le cœur garde la session (jeton d'accès, jeton de rafraîchissement et son
/// renouvellement) dans l'objet `Account`. Deux instances auraient chacune leur
/// jeton et se disputeraient le renouvellement : `LiveAccountClient` et
/// `LiveCatalogClient` reçoivent donc la même source. Un acteur : le compte
/// est créé une seule fois, hors du fil principal.
actor CoreAccountSource {
    private let baseUrl: String
    private let store: any SessionStore
    private var created: Account?

    init(
        baseUrl: String = Bundle.main.object(forInfoDictionaryKey: "HeyWatchlistAPIURL") as? String ?? "",
        store: any SessionStore = KeychainSessionStore()
    ) {
        self.baseUrl = baseUrl
        self.store = store
    }

    /// Le compte du cœur, créé à la première demande.
    func account() throws(AccountFailure) -> Account {
        if let created { return created }
        guard !baseUrl.isEmpty else { throw .notConfigured }
        do {
            let account = try Account(baseUrl: baseUrl, store: store)
            created = account
            return account
        } catch {
            throw AccountFailure(error)
        }
    }
}
