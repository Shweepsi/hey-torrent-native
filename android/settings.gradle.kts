// Projet Gradle d'HeyWatchlist pour Android : un seul module, `:app`, avec les
// éditions `public` et `admin` (voir app/build.gradle.kts).

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        // Miroir de Maven Central chez Google, essayé d'abord : Maven Central refuse
        // souvent (429) les machines partagées de la CI. Il reste en secours.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        // Même miroir que pour les greffons (voir plus haut).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "HeyWatchlist"

include(":app")
