import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lancer avec `./gradlew :buildSrc:test` : vérifie la lecture de la version Cargo. */
class CargoWorkspaceTest {
    private fun manifest(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun readsTheWorkspacePackageVersion() {
        val version = CargoWorkspace.parseVersion(
            manifest(
                "[workspace]",
                "members = [\"core\"]",
                "",
                "[workspace.package]",
                "# commentaire",
                "version = \"1.23.45\" # version de l'app",
                "edition = \"2024\"",
            ),
        )

        assertEquals(AppVersion(name = "1.23.45", code = 12345), version)
    }

    @Test
    fun ignoresVersionsOfOtherSections() {
        val version = CargoWorkspace.parseVersion(
            manifest(
                "[dependencies]",
                "version = \"9.9.9\"",
                "[workspace.package]",
                "version = \"0.1.0\"",
                "[[bin]]",
                "version = \"8.8.8\"",
            ),
        )

        assertEquals(AppVersion(name = "0.1.0", code = 100), version)
    }

    @Test
    fun keepsPreReleaseSuffixInTheNameButNotInTheCode() {
        val version = CargoWorkspace.parseVersion("[workspace.package]\nversion = \"2.0.1-beta.3\"")

        assertEquals(AppVersion(name = "2.0.1-beta.3", code = 20001), version)
    }

    @Test(expected = GradleException::class)
    fun failsWithoutWorkspacePackageVersion() {
        CargoWorkspace.parseVersion("[package]\nversion = \"1.0.0\"")
    }

    @Test(expected = GradleException::class)
    fun failsOnUnreadableVersion() {
        CargoWorkspace.parseVersion("[workspace.package]\nversion = \"1.0\"")
    }

    @Test(expected = GradleException::class)
    fun failsWhenMinorOrPatchOverflowTheCode() {
        CargoWorkspace.parseVersion("[workspace.package]\nversion = \"1.100.0\"")
    }
}
