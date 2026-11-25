// Code de build partagé par `:app` : lecture de la version Cargo et tâche qui
// pilote scripts/build-android-core.sh.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
