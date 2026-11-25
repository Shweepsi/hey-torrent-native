import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/** Ce que la tâche demande à scripts/build-android-core.sh de produire. */
enum class RustCoreStep(val option: String, val directoryOption: String) {
    /** Bibliothèques natives `.so` (cargo-ndk), une par architecture. */
    LIBS("libs", "--libs-dir"),

    /** Liaisons Kotlin d'UniFFI, générées depuis une bibliothèque de l'hôte. */
    BINDINGS("bindings", "--bindings-dir"),
}

/**
 * Lance scripts/build-android-core.sh pour une étape. Les liaisons dépendent
 * de l'édition seulement, les `.so` aussi du type de build : deux tâches
 * distinctes, pour que les tests JVM n'exigent pas le NDK.
 */
@DisableCachingByDefault(because = "La compilation est faite par cargo, qui a son propre cache")
abstract class RustCoreTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val step: Property<RustCoreStep>

    /** Compile le cœur avec la fonctionnalité Cargo `admin`. */
    @get:Input
    abstract val admin: Property<Boolean>

    /** Compile en mode release (sans effet sur les liaisons). */
    @get:Input
    abstract val release: Property<Boolean>

    /** Le NDK change la sortie : on le garde comme entrée de la tâche. */
    @get:Input
    @get:Optional
    abstract val ndkHome: Property<String>

    /** Dossier de sortie : `.so` par architecture, ou arbre des liaisons Kotlin. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Racine du dépôt, dossier de travail du script. */
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    /** Code Rust, manifestes et scripts dont dépend le résultat. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustInputs: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val root = repoRoot.get().asFile
        val currentStep = step.get()
        val command = buildList {
            add("bash")
            add(root.resolve("scripts/build-android-core.sh").absolutePath)
            if (admin.get()) add("--admin")
            if (release.get()) add("--release")
            add("--only")
            add(currentStep.option)
            add(currentStep.directoryOption)
            add(outputDir.get().asFile.absolutePath)
        }
        execOperations.exec {
            workingDir(root)
            commandLine(command)
        }
    }
}
