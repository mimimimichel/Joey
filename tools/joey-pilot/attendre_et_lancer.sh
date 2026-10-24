#!/bin/bash
# Attend que la batterie du téléphone dépasse SEUIL %, puis lance le garde-fou et une série d'essais.
# Usage : JOEY_HOST=… ADB_DEV=ip:port attendre_et_lancer.sh DOSSIER SEUIL essai…
set -u
DIR="$1"; SEUIL="$2"; shift 2
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="$HOME/Android/platform-tools/adb"
mkdir -p "$DIR"
while true; do
  lvl=$("$ADB" -s "$ADB_DEV" shell dumpsys battery 2>/dev/null | awk '/level:/{print $2; exit}' | tr -d '\r')
  echo "$(date +%H:%M:%S) batterie ${lvl:-?} %" >> "$DIR/attente.log"
  # Batterie assez chargée ET Roomba branché (le téléphone n'a qu'une prise : en charge, le câble du Roomba est débranché).
  roomba=$(cd "$HERE" && python3 joey.py state 2>/dev/null | grep '"roomba"' | grep -v null)
  echo "$(date +%H:%M:%S) roomba ${roomba:-non branché}" >> "$DIR/attente.log"
  if [ -n "${lvl:-}" ] && [ "$lvl" -ge "$SEUIL" ] && [ -n "$roomba" ]; then break; fi
  sleep 60
done
cd "$HERE"
python3 garde.py "$DIR" &
GARDE=$!
python3 campagne.py "$DIR" "$@"
touch "$DIR/STOP"
wait "$GARDE" 2>/dev/null
echo "$(date +%H:%M:%S) série finie" >> "$DIR/attente.log"
