#!/usr/bin/env bash
# Génère les liaisons UniFFI (Kotlin ou Swift) à partir d'une bibliothèque du
# cœur déjà compilée. Les liaisons ne sont jamais versionnées.
#
# Usage : scripts/generate-bindings.sh <kotlin|swift> <bibliotheque> <dossier-sortie>
#
# La bibliothèque peut être n'importe quelle compilation du cœur (.so, .dylib,
# .a), pour n'importe quelle architecture : les métadonnées UniFFI sont lues
# dans sa table des symboles, qui ne doit donc pas être retirée (voir
# `strip` dans le Cargo.toml racine).
set -euo pipefail

usage() {
  echo "Usage : $0 <kotlin|swift> <bibliotheque> <dossier-sortie>" >&2
  exit 2
}

# Rend un chemin absolu sans exiger qu'il existe (le dossier de sortie peut
# ne pas être créé), puisqu'on change de dossier avant de lancer cargo.
absolute() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s/%s\n' "$PWD" "$1" ;;
  esac
}

[[ $# -eq 3 ]] || usage

language="$1"
case "$language" in
  kotlin | swift) ;;
  *) usage ;;
esac

library="$(absolute "$2")"
out_dir="$(absolute "$3")"

if [[ ! -f "$library" ]]; then
  echo "Bibliothèque introuvable : $library" >&2
  exit 1
fi

# `cargo run -p` et la lecture des métadonnées du cœur se font depuis la
# racine de l'espace de travail, quel que soit le dossier d'appel.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

mkdir -p "$out_dir"

# `--no-format` : les liaisons ne sont pas versionnées, inutile de dépendre de
# ktlint ou de swiftformat, et la sortie reste identique d'une machine à l'autre.
if ! cargo run --release -p uniffi-bindgen -- generate \
  --library "$library" \
  --language "$language" \
  --out-dir "$out_dir" \
  --no-format; then
  echo "Échec de la génération. Vérifiez que la bibliothèque n'a pas été" >&2
  echo "dépouillée de ses symboles (strip) : $library" >&2
  exit 1
fi
