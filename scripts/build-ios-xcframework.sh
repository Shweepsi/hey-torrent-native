#!/usr/bin/env bash
# Compile le cœur Rust pour iOS et prépare tout ce dont le projet Xcode a besoin :
#   - ios/Frameworks/HeyWatchlistCore.xcframework  (bibliothèques statiques appareil + simulateur)
#   - ios/Generated/*.swift                        (liaisons Swift générées par UniFFI)
#   - ios/Config/Version.xcconfig                  (version de l'app, lue dans le Cargo.toml racine)
# Rien de tout cela n'est versionné (voir ios/.gitignore).
#
# Usage : scripts/build-ios-xcframework.sh
#
# Variables facultatives :
#   BUILD_NUMBER ou GITHUB_RUN_NUMBER : numéro de build (CURRENT_PROJECT_VERSION),
#                                       entier croissant exigé par TestFlight (défaut : 1).
#   CARGO_TARGET_DIR                  : dossier de compilation de Cargo (défaut : target/).
#
# Compatible avec le bash 3.2 livré par macOS : ni tableaux associatifs, ni mapfile.
set -euo pipefail

# Garder la même valeur que `deploymentTarget` dans ios/project.yml.
IOS_DEPLOYMENT_TARGET="26.0"
DEVICE_TARGET="aarch64-apple-ios"
SIMULATOR_TARGET="aarch64-apple-ios-sim"
CORE_PACKAGE="heywatchlist-core"
STATIC_LIB="libheywatchlist_core.a"
HOST_LIB="libheywatchlist_core.dylib"
XCFRAMEWORK="HeyWatchlistCore.xcframework"

fail() {
  echo "Erreur : $*" >&2
  exit 1
}

if [[ $# -ne 0 ]]; then
  echo "Usage : $0" >&2
  echo "Voir l'en-tête du script pour les variables d'environnement." >&2
  exit 2
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  fail "ce script ne fonctionne que sous macOS (Xcode est nécessaire pour créer le XCFramework). Sans Mac, c'est la CI GitHub Actions (runner macOS) qui le lance."
fi

for tool in cargo rustup xcodebuild; do
  command -v "$tool" >/dev/null 2>&1 || fail "outil introuvable : $tool"
done

# Tout se fait depuis la racine de l'espace de travail, quel que soit le dossier d'appel.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
ios_dir="$repo_root/ios"
target_dir="${CARGO_TARGET_DIR:-$repo_root/target}"

# Version de l'app : `version` de la section [workspace.package] du Cargo.toml racine.
version="$(awk '
  /^\[/ { in_package = ($0 == "[workspace.package]") }
  in_package && /^version[[:space:]]*=/ {
    gsub(/^[^"]*"|".*$/, "")
    print
    exit
  }
' Cargo.toml)"
# MARKETING_VERSION n'accepte que trois entiers séparés par des points.
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || fail "version illisible ou non conforme dans Cargo.toml : « $version » (attendu : X.Y.Z)"

build_number="${BUILD_NUMBER:-${GITHUB_RUN_NUMBER:-1}}"
[[ "$build_number" =~ ^[1-9][0-9]*$ ]] \
  || fail "numéro de build invalide : « $build_number » (attendu : entier strictement positif)"

# Cibles Rust manquantes.
installed_targets="$(rustup target list --installed)"
for target in "$DEVICE_TARGET" "$SIMULATOR_TARGET"; do
  if ! printf '%s\n' "$installed_targets" | grep -qx "$target"; then
    echo "==> Ajout de la cible Rust $target"
    rustup target add "$target"
  fi
done

# Bibliothèques statiques de l'app. `cargo rustc --crate-type` évite de compiler
# aussi le cdylib et le rlib, inutiles sur iOS. IPHONEOS_DEPLOYMENT_TARGET aligne
# le code Rust sur la cible iOS du projet Xcode.
for target in "$DEVICE_TARGET" "$SIMULATOR_TARGET"; do
  echo "==> Compilation du cœur pour $target"
  IPHONEOS_DEPLOYMENT_TARGET="$IOS_DEPLOYMENT_TARGET" \
    cargo rustc --release --locked --lib -p "$CORE_PACKAGE" \
    --target "$target" --crate-type staticlib
done
device_lib="$target_dir/$DEVICE_TARGET/release/$STATIC_LIB"
simulator_lib="$target_dir/$SIMULATOR_TARGET/release/$STATIC_LIB"

# Bibliothèque de l'hôte : sert seulement à lire les métadonnées UniFFI pour
# générer les liaisons (cas le plus courant d'uniffi-bindgen, un .dylib complet).
# Les métadonnées sont les mêmes que celles des bibliothèques iOS.
echo "==> Compilation du cœur pour l'hôte (métadonnées UniFFI)"
cargo rustc --release --locked --lib -p "$CORE_PACKAGE" --crate-type cdylib
host_lib="$target_dir/release/$HOST_LIB"

for lib in "$device_lib" "$simulator_lib" "$host_lib"; do
  [[ -f "$lib" ]] || fail "bibliothèque attendue mais absente : $lib"
done

staging="$(mktemp -d "${TMPDIR:-/tmp}/heywatchlist-ios.XXXXXX")"
trap 'rm -rf "$staging"' EXIT

# Renvoie l'unique fichier du dossier correspondant au motif, ou échoue.
single_file() {
  local dir="$1" pattern="$2" found
  found="$(find "$dir" -maxdepth 1 -type f -name "$pattern")"
  if [[ -z "$found" || "$found" == *$'\n'* ]]; then
    fail "un seul fichier $pattern attendu dans $dir (trouvé : ${found:-aucun})"
  fi
  printf '%s\n' "$found"
}

echo "==> Génération des liaisons Swift"
bindings="$staging/bindings"
"$repo_root/scripts/generate-bindings.sh" swift "$host_lib" "$bindings"
swift_file="$(single_file "$bindings" '*.swift')"
header_file="$(single_file "$bindings" '*.h')"
modulemap_file="$(single_file "$bindings" '*.modulemap')"

# Le Swift généré fait `import <module>` : ce nom doit être celui que déclare le
# modulemap, sinon l'app ne compile pas. On lit le nom, on ne le suppose pas.
module_name="$(sed -n 's/^module \([A-Za-z0-9_]*\).*/\1/p' "$modulemap_file" | head -n 1)"
[[ -n "$module_name" ]] || fail "aucun module déclaré dans $modulemap_file"
grep -Fq "canImport($module_name)" "$swift_file" \
  || fail "le Swift généré n'importe pas le module « $module_name » déclaré par le modulemap"
grep -Fq "header \"$(basename "$header_file")\"" "$modulemap_file" \
  || fail "le modulemap ne référence pas l'en-tête $(basename "$header_file")"

# Pas d'édition admin sur iOS : le cœur est compilé sans la fonctionnalité `admin`,
# donc aucune liaison ne doit mentionner `admin_ping`. Garde-fou si un jour elle
# devenait active par défaut.
if grep -Eq 'adminPing|admin_ping' "$swift_file" "$header_file"; then
  fail "les liaisons contiennent du code d'administration : l'app iPhone n'a pas d'édition admin"
fi

# En-têtes du XCFramework : l'en-tête C et un modulemap nommé module.modulemap,
# le nom que Xcode cherche dans le dossier des en-têtes d'une bibliothèque.
headers="$staging/headers"
mkdir -p "$headers"
cp "$header_file" "$headers/"
cp "$modulemap_file" "$headers/module.modulemap"

echo "==> Création de $XCFRAMEWORK"
# `xcodebuild -create-xcframework` refuse d'écraser une sortie existante.
rm -rf "$ios_dir/Frameworks/$XCFRAMEWORK"
mkdir -p "$ios_dir/Frameworks"
xcodebuild -create-xcframework \
  -library "$device_lib" -headers "$headers" \
  -library "$simulator_lib" -headers "$headers" \
  -output "$ios_dir/Frameworks/$XCFRAMEWORK"

# Seul le Swift va dans le projet : l'en-tête et le modulemap sont dans le XCFramework.
rm -rf "$ios_dir/Generated"
mkdir -p "$ios_dir/Generated"
cp "$swift_file" "$ios_dir/Generated/"

mkdir -p "$ios_dir/Config"
cat >"$ios_dir/Config/Version.xcconfig" <<EOF
// Généré par scripts/build-ios-xcframework.sh à partir du Cargo.toml racine :
// ne pas modifier, ne pas versionner.
MARKETING_VERSION = $version
CURRENT_PROJECT_VERSION = $build_number
EOF

echo "==> Terminé : version $version (build $build_number), module $module_name"
echo "    $ios_dir/Frameworks/$XCFRAMEWORK"
echo "    $ios_dir/Generated/$(basename "$swift_file")"
echo "    $ios_dir/Config/Version.xcconfig"
