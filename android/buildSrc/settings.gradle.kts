// Greffons de buildSrc : miroir de Maven Central chez Google d'abord (voir
// ../settings.gradle.kts), puis le portail des greffons.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
