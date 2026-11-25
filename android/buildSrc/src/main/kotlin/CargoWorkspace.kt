import org.gradle.api.GradleException

/** Version de l'app : nom affiché et code entier croissant pour le Play Store. */
data class AppVersion(val name: String, val code: Int)

/** Lecture du `Cargo.toml` racine, source unique de la version de l'app. */
object CargoWorkspace {
    private const val SECTION = "workspace.package"
    private val versionLine = Regex("""^\s*version\s*=\s*"([^"]+)"\s*(#.*)?$""")
    private val semver = Regex("""^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

    /**
     * Extrait `version` de `[workspace.package]` et en déduit le code de
     * version `major * 10000 + minor * 100 + patch`.
     */
    fun parseVersion(cargoToml: String): AppVersion {
        var inSection = false
        var name: String? = null
        for (line in cargoToml.lineSequence()) {
            if (line.trimStart().startsWith("[")) {
                val header = line.substringBefore('#').trim().removePrefix("[").removeSuffix("]").trim()
                inSection = header == SECTION
            } else if (inSection) {
                val match = versionLine.matchEntire(line)
                if (match != null) {
                    name = match.groupValues[1]
                    break
                }
            }
        }
        val version = name ?: throw GradleException(
            "Version introuvable : ajoutez `version = \"x.y.z\"` sous [$SECTION] dans le Cargo.toml racine.",
        )
        val parts = semver.matchEntire(version)?.groupValues?.drop(1)?.map(String::toInt)
            ?: throw GradleException("Version « $version » illisible : format attendu x.y.z.")
        val (major, minor, patch) = parts
        if (minor > 99 || patch > 99) {
            throw GradleException("Version « $version » : minor et patch doivent rester inférieurs à 100.")
        }
        return AppVersion(name = version, code = major * 10_000 + minor * 100 + patch)
    }
}
