#!/usr/bin/env bash
# Compile le cœur Rust pour Android (cargo-ndk) et génère les liaisons Kotlin
# d'UniFFI. Appelé par Gradle (android/buildSrc), utilisable à la main.
#
# Usage :
#   scripts/build-android-core.sh [--admin] [--release] [--only libs|bindings]
#                                 [--libs-dir DOSSIER] [--bindings-dir DOSSIER]
#
#   --admin             active la fonctionnalité Cargo `admin` (édition admin)
#   --release           compile en release (debug par défaut)
#   --only libs         ne fait que les bibliothèques .so (cargo-ndk, NDK requis)
#   --only bindings     ne fait que les liaisons Kotlin (compilation de l'hôte)
#   --libs-dir DOSSIER  sortie des .so : DOSSIER/<abi>/libheywatchlist_core.so
#   --bindings-dir DOSSIER  sortie des liaisons Kotlin
#
# Sans `--only`, les deux étapes sont faites. Sans `--*-dir`, les sorties vont
# dans android/app/build/rustJniLibs/<édition>/<type> et
# android/app/build/generated/uniffi/<édition>/kotlin (édition : public|admin,
# type : debug|release).
set -euo pipefail

package="heywatchlist-core"
library_name="libheywatchlist_core"
# Android 12 : niveau d'API minimal de l'app.
api_level=31
# Architectures livrées : appareils récents et émulateur.
abis=(arm64-v8a x86_64)
rust_targets=(aarch64-linux-android x86_64-linux-android)

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  echo "Erreur : $*" >&2
  exit 1
}

# Affiche l'aide (l'en-tête de ce fichier) puis quitte avec le code donné :
# 0 pour --help (sortie standard), 2 pour une erreur d'usage (sortie d'erreur).
usage() {
  local status="${1:-2}" text
  text="$(sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed -e '/^set -euo/d' -e 's/^# \{0,1\}//')"
  if [[ "$status" -eq 0 ]]; then
    printf '%s\n' "$text"
  else
    printf '%s\n' "$text" >&2
  fi
  exit "$status"
}

# Rend un chemin absolu sans exiger qu'il existe (on change de dossier ensuite).
absolute() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s/%s\n' "$PWD" "$1" ;;
  esac
}

# Vide puis recrée un dossier de sortie, en refusant les chemins dangereux.
reset_dir() {
  case "$1" in
    "" | / | "$repo_root" | "${HOME:-/}") fail "dossier de sortie refusé : « $1 »." ;;
  esac
  rm -rf -- "$1"
  mkdir -p -- "$1"
}

admin=0
release=0
only=""
libs_dir=""
bindings_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --admin) admin=1 ;;
    --release) release=1 ;;
    --only | --libs-dir | --bindings-dir)
      [[ $# -ge 2 ]] || { echo "Erreur : $1 attend une valeur." >&2; usage; }
      case "$1" in
        --only) only="$2" ;;
        --libs-dir) libs_dir="$(absolute "$2")" ;;
        --bindings-dir) bindings_dir="$(absolute "$2")" ;;
      esac
      shift
      ;;
    -h | --help) usage 0 ;;
    *) echo "Erreur : option inconnue « $1 »." >&2; usage ;;
  esac
  shift
done

case "$only" in
  "" | libs | bindings) ;;
  *) echo "Erreur : --only attend « libs » ou « bindings », pas « $only »." >&2; usage ;;
esac

edition="public"
features=()
if [[ "$admin" -eq 1 ]]; then
  edition="admin"
  features=(--features admin)
fi

profile_dir="debug"
profile_args=()
if [[ "$release" -eq 1 ]]; then
  profile_dir="release"
  profile_args=(--release)
fi

[[ -n "$libs_dir" ]] || libs_dir="$repo_root/android/app/build/rustJniLibs/$edition/$profile_dir"
[[ -n "$bindings_dir" ]] || bindings_dir="$repo_root/android/app/build/generated/uniffi/$edition/kotlin"

cd "$repo_root"

command -v cargo >/dev/null 2>&1 \
  || fail "cargo est introuvable. Installez Rust : https://rustup.rs"

# Liaisons Kotlin : lues dans une bibliothèque de l'hôte compilée avec les
# mêmes fonctionnalités que celle de l'app (les symboles exportés en dépendent).
build_bindings() {
  local target_dir host_extension host_library
  target_dir="${CARGO_TARGET_DIR:-$repo_root/target}"
  case "$target_dir" in
    /*) ;;
    *) target_dir="$repo_root/$target_dir" ;;
  esac
  case "$(uname -s)" in
    Linux) host_extension="so" ;;
    Darwin) host_extension="dylib" ;;
    *) fail "système hôte non pris en charge : $(uname -s)." ;;
  esac
  host_library="$target_dir/debug/$library_name.$host_extension"

  echo "==> Compilation du cœur pour l'hôte ($edition)"
  cargo build -p "$package" ${features[@]+"${features[@]}"}
  [[ -f "$host_library" ]] || fail "bibliothèque de l'hôte introuvable : $host_library"

  echo "==> Génération des liaisons Kotlin : $bindings_dir"
  reset_dir "$bindings_dir"
  bash "$repo_root/scripts/generate-bindings.sh" kotlin "$host_library" "$bindings_dir"
}

# Bibliothèques Android : une .so par architecture, copiée par cargo-ndk.
build_libs() {
  command -v cargo-ndk >/dev/null 2>&1 \
    || fail "cargo-ndk est introuvable. Installez-le : cargo install cargo-ndk (version 4 ou plus)"
  [[ -n "${ANDROID_NDK_HOME:-}" ]] \
    || fail "ANDROID_NDK_HOME n'est pas défini. Indiquez le dossier du NDK Android (ex. \$ANDROID_HOME/ndk/<version>)."
  [[ -d "$ANDROID_NDK_HOME" ]] \
    || fail "ANDROID_NDK_HOME ne désigne pas un dossier : $ANDROID_NDK_HOME"

  if command -v rustup >/dev/null 2>&1; then
    local installed target
    installed="$(rustup target list --installed)"
    for target in "${rust_targets[@]}"; do
      grep -qx "$target" <<<"$installed" \
        || fail "la cible Rust $target est absente. Installez-la : rustup target add $target"
    done
  fi

  local target_args=() abi
  for abi in "${abis[@]}"; do
    target_args+=(-t "$abi")
  done

  echo "==> Compilation du cœur pour Android ($edition, $profile_dir) : $libs_dir"
  reset_dir "$libs_dir"
  # cargo-ndk 4+ aligne les .so sur des pages de 16 Ko (exigé par Android 15+).
  cargo ndk "${target_args[@]}" --platform "$api_level" --output-dir "$libs_dir" \
    build -p "$package" ${profile_args[@]+"${profile_args[@]}"} ${features[@]+"${features[@]}"}

  local library
  for abi in "${abis[@]}"; do
    library="$libs_dir/$abi/$library_name.so"
    [[ -f "$library" ]] || fail "cargo-ndk n'a pas produit $library"
  done
}

case "$only" in
  bindings) build_bindings ;;
  libs) build_libs ;;
  *)
    build_bindings
    build_libs
    ;;
esac
