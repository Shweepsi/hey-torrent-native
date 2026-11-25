package app.heywatchlist.core

/** Ce que l'écran d'accueil affiche du cœur. */
data class CoreInfo(val version: String, val pong: String)

/**
 * Frontière entre l'interface et le cœur Rust. Seule [UniffiCoreGateway]
 * importe le code généré : le reste de l'app et ses tests JVM n'en dépendent pas.
 */
interface CoreGateway {
    /** Interroge le cœur hors du fil principal ; lève [CoreFailureException] en cas d'échec. */
    suspend fun load(): CoreInfo
}
