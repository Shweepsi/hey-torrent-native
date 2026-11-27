#!/usr/bin/env bash
# Démarre la release d'une édition sur l'émulateur, en état « connecté », et
# vérifie qu'elle tient. Lancé par .github/workflows/android-smoke.yml, depuis
# la racine du dépôt.
#
# Usage : android-smoke.sh public|admin
set -euo pipefail

flavor="$1"
package="io.github.shweepsi.heywatchlist"
if [[ "$flavor" == "admin" ]]; then
  package="$package.admin"
fi
outputs="android/app/build/outputs/apk"
app_apk="$(find "$outputs/$flavor/release" -name '*.apk' | head -n 1)"
test_apk="$(find "$outputs/androidTest/$flavor/release" -name '*.apk' | head -n 1)"

adb install -r "$app_apk"
adb install -r "$test_apk"
adb logcat -c
adb logcat -b crash -c || true

report() {
  echo "::group::Plantages (logcat -b crash)"
  adb logcat -d -b crash || true
  echo "::endgroup::"
  echo "::group::Journal de l'app"
  adb logcat -d | grep -E "TestRunner|Instrumentation|ActivityManager.*heywatchlist|AndroidRuntime|FATAL|Fatal signal|ANR in|DEBUG   :|libc    :|heywatchlist|HeyWatchlist|Firebase|Crashlytics|JNA|uniffi|System.err" | tail -n 400 || true
  echo "::endgroup::"
}

# Session factice au format de l'app (SeedSessionTest), écrite dans son processus.
# Délai borné : une instrumentation bloquée ne doit pas tenir le job une heure.
seed="$(timeout 180 adb shell am instrument -w -r -e class app.heywatchlist.smoke.SeedSessionTest "$package.test/androidx.test.runner.AndroidJUnitRunner" || true)"
echo "$seed"
if ! grep -q "INSTRUMENTATION_CODE: -1" <<<"$seed"; then
  echo "::error::Échec de la préparation de la session factice."
  adb shell pm list instrumentation || true
  report
  exit 1
fi
# L'instrumentation a démarré l'app : on repart d'un processus neuf.
adb shell am force-stop "$package"
adb logcat -c

adb shell am start -W -n "$package/app.heywatchlist.MainActivity"
sleep 30

screen="$(adb exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
pid="$(adb shell pidof "$package" | tr -d '\r' || true)"
report

if [[ -z "$pid" ]]; then
  echo "::error::L'app ($flavor) ne tourne plus après 30 s : plantage au démarrage."
  exit 1
fi
if adb logcat -d | grep -qE "FATAL EXCEPTION|Fatal signal|ANR in $package"; then
  echo "::error::L'app ($flavor) a planté ou s'est figée (voir le journal ci-dessus)."
  exit 1
fi
if ! grep -q "Découvrir" <<<"$screen"; then
  echo "::group::Écran (uiautomator)"
  echo "$screen"
  echo "::endgroup::"
  echo "::error::L'onglet « Découvrir » n'est pas affiché après 30 s."
  exit 1
fi
echo "L'app ($flavor) démarre connectée et affiche « Découvrir »."
