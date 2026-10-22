#!/usr/bin/env bash
# Prépare le PC : outils Android (sans Android Studio), dépôt Joey, fichier de clés, service qui tourne en permanence.
set -eu
REPO="${JOEY_REPO:-$HOME/Joey}"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "1/5 Java 17"
if ! java -version 2>&1 | grep -q 'version "\(17\|21\)'; then
  echo "   Installe Java 17 (Debian/Ubuntu : sudo apt install openjdk-17-jdk-headless ; Fedora : sudo dnf install java-17-openjdk-devel ; Arch : sudo pacman -S jdk17-openjdk)"
  exit 1
fi

echo "2/5 Outils Android dans $SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  tmp=$(mktemp -d)
  curl -sSL -o "$tmp/tools.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$tmp/tools.zip" -d "$tmp" && mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null

echo "3/5 Dépôt Joey dans $REPO"
[ -d "$REPO/.git" ] || git clone https://github.com/mimimimichel/Joey.git "$REPO"

echo "4/5 Clés dans ~/.config/joey/env"
mkdir -p "$HOME/.config/joey"
if [ ! -f "$HOME/.config/joey/env" ]; then
  cat > "$HOME/.config/joey/env" <<'K'
# Clés de Joey (ne jamais les mettre dans le dépôt)
GEMINI_API_KEY=
SUPABASE_URL=
SUPABASE_KEY=
# Code du foyer (mémoire partagée entre les téléphones) : facultatif, les téléphones le gardent déjà
JOEY_FOYER=
# Facultatif : adresse fixe du téléphone (ip:port, débogage sans fil) et sujet ntfy pour être prévenu
JOEY_PHONE=
NTFY_TOPIC=
K
  chmod 600 "$HOME/.config/joey/env"
  echo "   -> remplis ~/.config/joey/env (clé Gemini, adresse et clé Supabase)"
fi

echo "5/5 Service permanent (systemd utilisateur)"
mkdir -p "$HOME/.config/systemd/user"
sed "s#@SCRIPT@#$HERE/joey-auto-install.sh#; s#@SDK@#$SDK#; s#@REPO@#$REPO#" "$HERE/joey-auto-install.service" \
  > "$HOME/.config/systemd/user/joey-auto-install.service"
systemctl --user daemon-reload
systemctl --user enable --now joey-auto-install.service
loginctl enable-linger "$USER" 2>/dev/null || true
echo "C'est prêt. Journal : ~/.local/state/joey/auto-install.log (systemctl --user status joey-auto-install)"
