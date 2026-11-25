#!/usr/bin/env bash
# Vérifie que des secrets (ou variables) GitHub sont renseignés, sans jamais en
# afficher la valeur. Les valeurs arrivent par l'environnement de l'étape, sous
# leur propre nom ; seuls les noms manquants sont écrits dans le journal.
#
# Usage : check-secrets.sh [--strict] NOM...
#   Écrit « ready=true » ou « ready=false » dans $GITHUB_OUTPUT.
#   --strict : échoue (code 1) s'il en manque, au lieu de seulement le signaler.
#
# Compatible avec le bash 3.2 de macOS.
set -euo pipefail

strict=0
if [[ "${1:-}" == "--strict" ]]; then
  strict=1
  shift
fi

if [[ $# -eq 0 ]]; then
  echo "Usage : $0 [--strict] NOM..." >&2
  exit 2
fi

missing=""
for name in "$@"; do
  if [[ -z "${!name:-}" ]]; then
    missing="$missing $name"
  fi
done

output="${GITHUB_OUTPUT:-/dev/stdout}"

if [[ -z "$missing" ]]; then
  echo "ready=true" >>"$output"
  exit 0
fi

echo "ready=false" >>"$output"
if [[ "$strict" -eq 1 ]]; then
  echo "::error title=Secrets manquants::À créer dans les réglages du dépôt (voir docs/CI.md) :$missing"
  exit 1
fi
echo "::notice title=Envoi ignoré::Secrets ou variables absents (voir docs/CI.md) :$missing"
