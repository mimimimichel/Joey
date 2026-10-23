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

# Trouve les téléphones : déjà connectés, adresses fixes (JOEY_PHONE=ip:port, plusieurs séparées par des virgules),
# ou découverte automatique (Android 11+). Un même téléphone peut apparaître deux fois (adresse et nom) : on ne le garde
# qu'une fois, d'après son numéro de série.
phones() {
  local a m d s seen=" "
  for a in $(echo "${JOEY_PHONE:-}" | tr ',' ' '); do adb connect "$a" >/dev/null 2>&1; done
  for m in $(adb mdns services 2>/dev/null | awk '/_adb-tls-connect|_adb\._tcp/{print $NF}'); do adb connect "$m" >/dev/null 2>&1; done
  for d in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
    s=$(adb -s "$d" shell getprop ro.serialno 2>/dev/null | tr -d '\r')
    [ -z "$s" ] && continue
    case "$seen" in *" $s "*) continue ;; esac
    seen="$seen$s "
    echo "$d $s"
  done
}

# Compile la version [commit] (une seule fois par version, même si l'installation doit attendre un téléphone).
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

# Installe la version compilée sur un téléphone ([adresse adb] [numéro de série]) et relance Joey.
install_on() {
  local dev="$1" serial="$2"
  local apk="$REPO/app/build/outputs/apk/debug/app-debug.apk"
  local version model
  version=$(grep -o '"versionName": "[^"]*"' "$REPO/app/build/outputs/apk/debug/output-metadata.json" | cut -d'"' -f4)
  model=$(adb -s "$dev" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  log "Installation de Joey $version sur $model ($dev)…"
  if adb -s "$dev" install -r "$apk" >>"$LOG" 2>&1; then
    adb -s "$dev" shell am start -n com.joey.app/.MainActivity >/dev/null 2>&1
    log "Joey $version installé et relancé sur $model."
    notify "Joey $version installé sur $model ✅"
  else
    log "Échec de l'installation sur $model (voir $LOG)"; notify "Joey : l'installation de $version sur $model a échoué"; return 1
  fi
}

log "Surveillance de $BRANCH dans $REPO (toutes les ${EVERY}s)"
while true; do
  if (cd "$REPO" && git fetch -q origin "$BRANCH" 2>>"$LOG"); then
    remote=$(cd "$REPO" && git rev-parse "origin/$BRANCH")
    if [ "$remote" != "$(cat "$STATE_DIR/built" 2>/dev/null)" ]; then
      (cd "$REPO" && git checkout -q "$BRANCH" 2>>"$LOG" && git reset -q --hard "origin/$BRANCH")
      rev=$(cd "$REPO" && git log -1 --format='%h %s' | cut -c1-60)
      build "$remote" "$rev"
    fi
    # Chaque téléphone reçoit la version compilée s'il ne l'a pas encore (un téléphone absent la reçoit à son retour).
    if [ "$remote" = "$(cat "$STATE_DIR/built" 2>/dev/null)" ]; then
      found=0
      while read -r dev serial; do
        [ -z "$dev" ] && continue
        found=1
        f="$STATE_DIR/installed-$serial"
        [ "$(cat "$f" 2>/dev/null)" = "$remote" ] && continue
        install_on "$dev" "$serial" && echo "$remote" > "$f"
      done < <(phones)
      [ "$found" = 0 ] && [ "$(cat "$STATE_DIR/nophone" 2>/dev/null)" != "$remote" ] && {
        log "Aucun téléphone trouvé (débogage sans fil activé ? même Wi-Fi ?) : nouvel essai toutes les ${EVERY}s"
        echo "$remote" > "$STATE_DIR/nophone"
      }
    fi
  else
    log "git fetch impossible (Internet ?)"
  fi
  sleep "$EVERY"
done
