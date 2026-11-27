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
        // Miroir de Maven Central chez Google, pour Robolectric seulement : son gros
        // runtime natif est souvent refusé (429) aux machines partagées de la CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeGroupByRegex("org\\.robolectric(\\..*)?") }
        }
        mavenCentral()
    }
}

rootProject.name = "HeyWatchlist"

include(":app")
