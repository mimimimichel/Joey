#!/usr/bin/env bash
# Installation automatique de Joey : surveille la branche, recompile à chaque nouvelle version et l'installe sur le
# téléphone par le Wi-Fi (débogage sans fil). À lancer sur le PC toujours allumé (voir README.md).
set -u

REPO="${JOEY_REPO:-$HOME/Joey}"
BRANCH="${JOEY_BRANCH:-claude/roomba-joey-upgrade-o87jto}"
ENV_FILE="${JOEY_ENV:-$HOME/.config/joey/env}"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/joey"
EVERY="${JOEY_EVERY:-120}"   # secondes entre deux vérifications
mkdir -p "$STATE_DIR"
LOG="$STATE_DIR/auto-install.log"

log() { echo "$(date '+%F %T') $*" | tee -a "$LOG"; }

# Clés (jamais dans le dépôt) : GEMINI_API_KEY, SUPABASE_URL, SUPABASE_KEY, et éventuellement JOEY_PHONE, NTFY_TOPIC.
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

notify() { [ -n "${NTFY_TOPIC:-}" ] && curl -s -m 10 -d "$1" "https://ntfy.sh/$NTFY_TOPIC" >/dev/null || true; }

# Trouve le téléphone : déjà connecté, adresse fixe (JOEY_PHONE=ip:port), ou découverte automatique (Android 11+).
phone() {
  local d
  d=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
  if [ -z "$d" ] && [ -n "${JOEY_PHONE:-}" ]; then adb connect "$JOEY_PHONE" >/dev/null 2>&1; fi
  if [ -z "$d" ]; then
    local m
    m=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/{print $NF; exit}')
    [ -n "$m" ] && adb connect "$m" >/dev/null 2>&1
  fi
  adb devices | awk 'NR>1 && $2=="device"{print $1; exit}'
}

# Compile la version [commit] (une seule fois par version, même si l'installation doit attendre le téléphone).
build() {
  local commit="$1" rev="$2"
  [ "$(cat "$STATE_DIR/built" 2>/dev/null)" = "$commit" ] && return 0
  [ "$(cat "$STATE_DIR/failed" 2>/dev/null)" = "$commit" ] && return 1 # déjà échoué : on attend la suivante
  log "Nouvelle version ($rev) : compilation…"
  if ! (cd "$REPO" && ./gradlew assembleDebug --no-daemon -q >>"$LOG" 2>&1); then
    echo "$commit" > "$STATE_DIR/failed"
    log "Échec de la compilation (voir $LOG)"; notify "Joey : la compilation de $rev a échoué"; return 1
  fi
  echo "$commit" > "$STATE_DIR/built"
}

install() {
  local apk="$REPO/app/build/outputs/apk/debug/app-debug.apk"
  local version
  version=$(grep -o '"versionName": "[^"]*"' "$REPO/app/build/outputs/apk/debug/output-metadata.json" | cut -d'"' -f4)
  local dev
  dev=$(phone)
  if [ -z "$dev" ]; then
    log "Téléphone introuvable (débogage sans fil activé ? même Wi-Fi ?) : nouvel essai plus tard"; return 1
  fi
  log "Installation de Joey $version sur $dev…"
  if adb -s "$dev" install -r "$apk" >>"$LOG" 2>&1; then
    adb -s "$dev" shell am start -n com.joey.app/.MainActivity >/dev/null 2>&1
    log "Joey $version installé et relancé."
    notify "Joey $version installé ✅"
  else
    log "Échec de l'installation (voir $LOG)"; notify "Joey : l'installation de $version a échoué"; return 1
  fi
}

log "Surveillance de $BRANCH dans $REPO (toutes les ${EVERY}s)"
while true; do
  if (cd "$REPO" && git fetch -q origin "$BRANCH" 2>>"$LOG"); then
    remote=$(cd "$REPO" && git rev-parse "origin/$BRANCH")
    installed=$(cat "$STATE_DIR/installed" 2>/dev/null || echo none)
    if [ "$remote" != "$installed" ]; then
      (cd "$REPO" && git checkout -q "$BRANCH" 2>>"$LOG" && git reset -q --hard "origin/$BRANCH")
      rev=$(cd "$REPO" && git log -1 --format='%h %s' | cut -c1-60)
      build "$remote" "$rev" && install && echo "$remote" > "$STATE_DIR/installed"
    fi
  else
    log "git fetch impossible (Internet ?)"
  fi
  sleep "$EVERY"
done
