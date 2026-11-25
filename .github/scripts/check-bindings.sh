#!/usr/bin/env bash
# Génère les liaisons Kotlin et Swift du cœur sur Linux, avec puis sans la
# fonctionnalité Cargo `admin`, et vérifie le contrat entre le cœur et les
# deux apps :
#   - core_version, ping, ping_async et has_admin sont exportées ;
#   - admin_ping n'existe que dans la compilation admin : ni l'édition publique
#     d'Android ni l'app iPhone ne doivent en avoir la moindre trace.
#
# Il ne faut ni NDK ni Xcode. Une erreur de génération (version d'UniFFI,
# macro mal posée) se repère ainsi sur Linux, avant de dépenser des minutes
# macOS (10 fois plus chères).
#
# Usage : .github/scripts/check-bindings.sh   (Linux, depuis n'importe où)
set -euo pipefail

fail() {
  echo "Erreur : $*" >&2
  exit 1
}

[[ "$(uname -s)" == "Linux" ]] || fail "ce contrôle est prévu pour Linux (bibliothèque .so)."

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

target_dir="${CARGO_TARGET_DIR:-$repo_root/target}"
case "$target_dir" in
  /*) ;;
  *) target_dir="$repo_root/$target_dir" ;;
esac
host_library="$target_dir/debug/libheywatchlist_core.so"

out="$(mktemp -d "${TMPDIR:-/tmp}/heywatchlist-bindings.XXXXXX")"
trap 'rm -rf "$out"' EXIT

# Vérifie qu'une fonction est déclarée dans un fichier généré (Kotlin met le
# nom entre accents graves : « fun `ping`( », Swift écrit « func ping( »).
expect_function() {
  local file="$1" name="$2"
  grep -Eq "(fun|func) \`?${name}\`?\(" "$file" \
    || fail "$name est absente de $file."
}

check_edition() {
  local edition="$1" features=()
  if [[ "$edition" == "admin" ]]; then
    features=(--features admin)
  fi

  echo "==> Cœur ($edition)"
  cargo build --locked -p heywatchlist-core ${features[@]+"${features[@]}"}
  [[ -f "$host_library" ]] || fail "bibliothèque introuvable : $host_library"

  local language
  for language in kotlin swift; do
    echo "==> Liaisons $language ($edition)"
    scripts/generate-bindings.sh "$language" "$host_library" "$out/$edition/$language"
  done

  local kotlin_file="$out/$edition/kotlin/uniffi/heywatchlist_core/heywatchlist_core.kt"
  local swift_dir="$out/$edition/swift"
  local swift_file="$swift_dir/heywatchlist_core.swift"
  local header_file="$swift_dir/heywatchlist_coreFFI.h"
  local modulemap_file="$swift_dir/heywatchlist_coreFFI.modulemap"

  [[ -f "$kotlin_file" ]] || fail "liaisons Kotlin attendues dans $kotlin_file."
  grep -q '^package uniffi.heywatchlist_core$' "$kotlin_file" \
    || fail "le paquet Kotlin n'est pas uniffi.heywatchlist_core."
  for file in "$swift_file" "$header_file" "$modulemap_file"; do
    [[ -f "$file" ]] || fail "fichier Swift attendu absent : $file."
  done

  local name
  for name in coreVersion ping pingAsync hasAdmin; do
    expect_function "$kotlin_file" "$name"
    expect_function "$swift_file" "$name"
  done

  if [[ "$edition" == "admin" ]]; then
    expect_function "$kotlin_file" adminPing
    echo "    admin_ping présente (attendu pour l'édition admin)."
  elif grep -Eqi 'adminPing|admin_ping' "$kotlin_file" "$swift_file" "$header_file"; then
    fail "l'édition publique expose du code d'administration (admin_ping)."
  else
    echo "    aucune trace d'admin_ping (attendu pour l'édition publique)."
  fi
}

# La compilation publique d'abord : les deux passent par le même fichier .so.
check_edition public
check_edition admin
echo "==> Liaisons conformes au contrat."
