# JNA charge libheywatchlist_core.so ; il lit structures et rappels par réflexion
# (notamment l'annotation @Structure.FieldOrder du code UniFFI).
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# Liaisons générées par UniFFI : classes, structures de rappel, erreurs. Leurs
# noms servent à la résolution des symboles natifs, R8 ne doit pas les toucher.
-keep class uniffi.** { *; }

# Credential Manager : l'implémentation Play Services est chargée par réflexion.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
