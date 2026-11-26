import com.android.build.api.variant.BuildConfigField
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Le dépôt est un niveau au-dessus du projet Gradle (android/).
val coreRepoRoot = rootProject.layout.projectDirectory.dir("..")

// Source unique de la version : [workspace.package] du Cargo.toml racine.
val appVersion = CargoWorkspace.parseVersion(
    providers.fileContents(coreRepoRoot.file("Cargo.toml")).asText.getOrElse(""),
)

// Signature de release : variables d'environnement de la CI. Sans elles (poste
// de développement), la release est signée avec la clé de debug.
fun envOrNull(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val signingKeystorePath = envOrNull("ANDROID_KEYSTORE_PATH")
val signingKeystorePassword = envOrNull("ANDROID_KEYSTORE_PASSWORD")
val signingKeyAlias = envOrNull("ANDROID_KEY_ALIAS")
val signingKeyPassword = envOrNull("ANDROID_KEY_PASSWORD")

// Adresse de l'API (Worker Cloudflare) : variable GitHub HEYWATCHLIST_API_URL,
// jamais écrite dans le dépôt. Absente, l'app affiche « serveur non configuré ».
val apiBaseUrl = envOrNull("HEYWATCHLIST_API_URL")?.trim().orEmpty().also { url ->
    require(url.isEmpty() || Regex("^https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~/-]*)?$").matches(url)) {
        "HEYWATCHLIST_API_URL doit être une adresse https:// sans paramètres."
    }
}

// Réglages Firebase (push Android) : secret GitHub GOOGLE_SERVICES_JSON, ou
// app/google-services.json sur un poste (jamais versionné). Sans eux, l'app se
// compile et fonctionne, sans notifications.
val googleServicesJson: String? = envOrNull("GOOGLE_SERVICES_JSON")?.let(FirebaseConfig::decode)
    ?: layout.projectDirectory.file("google-services.json").asFile.takeIf { it.isFile }?.readText()

android {
    // Paquet du code Kotlin et de la classe R : distinct de l'identifiant publié.
    namespace = "app.heywatchlist"
    compileSdk = 36

    defaultConfig {
        // Identifiant décidé par le propriétaire (docs/DECISIONS.md), définitif
        // après publication ; l'édition admin ajoute le suffixe `.admin`. Il
        // doit rester celui des apps déclarées dans Firebase.
        applicationId = "io.github.shweepsi.heywatchlist"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersion.code
        versionName = appVersion.name

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")

        // Seules architectures compilées pour le cœur ; limite aussi celles de JNA.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("public") {
            dimension = "edition"
        }
        create("admin") {
            dimension = "edition"
            applicationIdSuffix = ".admin"
        }
    }

    signingConfigs {
        if (signingKeystorePath != null &&
            signingKeystorePassword != null &&
            signingKeyAlias != null &&
            signingKeyPassword != null
        ) {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.firebase.messaging)
    implementation(libs.coil.network.okhttp)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.haze)
    implementation(libs.kotlinx.coroutines.android)
    // Forme `aar` : elle embarque libjnidispatch.so, absent du jar.
    implementation(variantOf(libs.jna) { artifactType("aar") })

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Cœur Rust : scripts/build-android-core.sh est lancé par Gradle, une tâche
// pour les liaisons Kotlin et une pour les `.so`, par variante. Les liaisons
// sont séparées des `.so` pour que les tests JVM n'exigent pas le NDK. Les
// tâches d'un même projet s'exécutent en série : pas de collision dans le
// dossier `target/` de cargo entre l'édition publique et l'édition admin.
// Le dossier de sortie de chaque tâche est choisi par AGP (voir
// `addGeneratedSourceDirectory`), sous app/build/generated/.
val coreInputs = files(
    coreRepoRoot.asFile.resolve("Cargo.toml"),
    coreRepoRoot.asFile.resolve("Cargo.lock"),
    coreRepoRoot.dir("core").asFileTree.matching { exclude("**/target/**") },
    coreRepoRoot.dir("tools/uniffi-bindgen").asFileTree.matching { exclude("**/target/**") },
    coreRepoRoot.asFile.resolve("scripts/build-android-core.sh"),
    coreRepoRoot.asFile.resolve("scripts/generate-bindings.sh"),
)

androidComponents {
    onVariants { variant ->
        val edition = checkNotNull(variant.flavorName) { "Variante sans édition : ${variant.name}" }
        val buildType = checkNotNull(variant.buildType) { "Variante sans type de build : ${variant.name}" }
        val variantSuffix = variant.name.replaceFirstChar(Char::uppercase)

        val bindingsTask = tasks.register<RustCoreTask>("generateUniffiBindings$variantSuffix") {
            group = "rust"
            description = "Génère les liaisons Kotlin d'UniFFI (variante ${variant.name})."
            step.set(RustCoreStep.BINDINGS)
            this.admin.set(edition == "admin")
            this.release.set(false)
            repoRoot.set(coreRepoRoot)
            rustInputs.from(coreInputs)
        }

        val libsTask = tasks.register<RustCoreTask>("buildRustCore$variantSuffix") {
            group = "rust"
            description = "Compile le cœur Rust avec cargo-ndk (variante ${variant.name})."
            step.set(RustCoreStep.LIBS)
            this.admin.set(edition == "admin")
            this.release.set(buildType == "release")
            ndkHome.set(providers.environmentVariable("ANDROID_NDK_HOME"))
            repoRoot.set(coreRepoRoot)
            rustInputs.from(coreInputs)
        }

        // Réglages Firebase de cette app (publique ou admin), vides s'ils manquent.
        val packageName = "io.github.shweepsi.heywatchlist" + if (edition == "admin") ".admin" else ""
        val firebase = googleServicesJson?.let { FirebaseConfig.parse(it, packageName) }
        fun firebaseField(name: String, value: String?) = variant.buildConfigFields?.put(
            name,
            BuildConfigField("String", "\"${value.orEmpty()}\"", "Firebase (push), vide sans google-services.json"),
        )
        firebaseField("FIREBASE_APP_ID", firebase?.applicationId)
        firebaseField("FIREBASE_API_KEY", firebase?.apiKey)
        firebaseField("FIREBASE_PROJECT_ID", firebase?.projectId)
        firebaseField("FIREBASE_SENDER_ID", firebase?.senderId)

        // Le Kotlin généré se compile avec le reste ; les `.so` rejoignent l'APK.
        variant.sources.java?.addGeneratedSourceDirectory(bindingsTask, RustCoreTask::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(libsTask, RustCoreTask::outputDir)
    }
}
