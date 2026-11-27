# Test de démarrage seulement (`-Pheywatchlist.smoke`) : l'APK de test partage
# avec l'app la bibliothèque Kotlin et androidx.tracing, que R8 réduirait sinon
# (NoSuchMethodError au lancement de l'instrumentation). Le reste de l'app passe
# par R8 comme la vraie release.
-keep class kotlin.** { *; }
-keep class androidx.tracing.** { *; }
