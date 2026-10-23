#!/usr/bin/env python3
"""
Piloter Joey depuis le PC (même Wi-Fi que le téléphone) : pour qu'une session Claude Code locale le teste en autonomie.

Adresse du téléphone : JOEY_HOST=192.168.1.42 (sinon trouvée par adb, comme l'installation automatique).
Tout est ouvert sur le Wi-Fi de la maison (pas de code).

  joey.py state                  état (humeur, conversation, batteries, roues, carte, place libre devant…)
  joey.py feed [--all]           ce qu'il a pensé/fait depuis la dernière fois (entendu, dit, outils, résultats)
  joey.py say "texte"            lui parler (comme si on le lui disait)
  joey.py cmd stop|come|explore|map|dock|vacuum|center|autonomy|sleep
  joey.py drive AVANT TOURNE SECONDES   rouler à la main (-1..1 chacun), puis s'arrêter
  joey.py tool NOM '{"arg": 1}'  lancer un de ses outils et attendre le résultat (ex. tool avancer '{"metres":0.3}')
  joey.py frame [fichier.jpg]    enregistrer ce qu'il voit (à regarder ensuite)
  joey.py map                    sa carte en texte (# obstacle, . libre, ! vide, R lui)
  joey.py wait [SECONDES]        attendre qu'il ait fini ce qu'il fait
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

PORT = 8080
SINCE_FILE = os.path.expanduser("~/.joey-pilot-since")

# Réglages du PC (comme l'installation automatique) : JOEY_HOST=… dans ~/.config/joey/env (facultatif)
_env = os.path.expanduser(os.environ.get("JOEY_ENV", "~/.config/joey/env"))
if os.path.exists(_env):
    for _l in open(_env):
        _l = _l.strip()
        if "=" in _l and not _l.startswith("#"):
            _k, _v = _l.split("=", 1)
            _k = _k.replace("export ", "").strip()
            os.environ.setdefault(_k, _v.strip().strip('"').strip("'"))


def host():
    h = os.environ.get("JOEY_HOST")
    if h:
        return h
    phone = os.environ.get("JOEY_PHONE", "")
    if phone:
        return phone.split(":")[0]
    try:
        out = subprocess.run(["adb", "shell", "ip", "-f", "inet", "addr", "show", "wlan0"],
                             capture_output=True, text=True, timeout=10).stdout
        m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out)
        if m:
            return m.group(1)
    except Exception:
        pass
    sys.exit("Adresse du téléphone inconnue : export JOEY_HOST=<adresse IP du téléphone>")


def get(path, timeout=20, raw=False):
    url = "http://%s:%d%s" % (host(), PORT, path)
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            data = r.read()
    except Exception as e:
        sys.exit("Joey ne répond pas (%s) : l'app est-elle ouverte, sur le même Wi-Fi ?" % e)
    return data if raw else json.loads(data.decode("utf-8"))


def show_feed(lines):
    for e in lines:
        if e["k"] in ("tete", "etat"):
            continue
        t = time.strftime("%H:%M:%S", time.localtime(e["t"] / 1000))
        print("%s %-8s %s" % (t, e["k"], e["x"]))


def main(argv):
    if not argv:
        print(__doc__)
        return
    c, rest = argv[0], argv[1:]
    if c == "state":
        s = get("/tab/state?since=999999999")
        s.pop("feed", None)
        print(json.dumps(s, ensure_ascii=False, indent=1))
    elif c == "feed":
        since = 0
        if "--all" not in rest and os.path.exists(SINCE_FILE):
            since = int(open(SINCE_FILE).read().strip() or 0)
        s = get("/tab/state?since=%d" % since)
        show_feed(s["feed"])
        if s["feed"]:
            open(SINCE_FILE, "w").write(str(max(e["id"] for e in s["feed"])))
    elif c == "say":
        print(get("/tab/say?t=" + urllib.parse.quote(" ".join(rest))).get("msg", ""))
    elif c == "cmd":
        print(get("/tab/cmd?c=" + rest[0]).get("msg", ""))
    elif c == "drive":
        f, t, secs = float(rest[0]), float(rest[1]), float(rest[2])
        end = time.time() + secs
        while time.time() < end:
            get("/tab/cmd?c=drive&f=%.2f&t=%.2f" % (f, t))
            time.sleep(0.15)
        get("/tab/cmd?c=drive&f=0&t=0")
        print("fait")
    elif c == "tool":
        args = rest[1] if len(rest) > 1 else "{}"
        q = "/tab/tool?name=%s&args=%s" % (urllib.parse.quote(rest[0]), urllib.parse.quote(args))
        print(json.dumps(get(q, timeout=400), ensure_ascii=False, indent=1))
    elif c == "frame":
        out = rest[0] if rest else "joey-%s.jpg" % time.strftime("%H%M%S")
        open(out, "wb").write(get("/frame?%d" % time.time(), raw=True))
        print(os.path.abspath(out))
    elif c == "map":
        m = get("/tab/map")
        cell = m["cell"]
        ri, rj = int(m["rx"] // cell), int(m["ry"] // cell)
        grid = {}
        for i, j, v in m["cells"]:
            grid[(i, j)] = "#" if v > 1.5 else ("." if v < -0.9 else " ")
        for i, j in m["cliffs"]:
            grid[(i, j)] = "!"
        rad = 25
        print(m["info"], "| x vers le haut (devant au départ), y vers la droite, 1 caractère = %d cm" % round(cell * 100))
        for i in range(ri + rad, ri - rad - 1, -1):
            print("".join("R" if (i, j) == (ri, rj) else grid.get((i, j), " ") for j in range(rj - rad, rj + rad + 1)).rstrip())
        for p in m.get("places", []):
            print("pièce %s : x=%.1f y=%.1f" % (p["n"], p["x"], p["y"]))
        for p in m.get("people", []):
            print("personne %s : x=%.1f y=%.1f" % (p["n"], p["x"], p["y"]))
        print("Joey : x=%.2f y=%.2f cap=%.0f°" % (m["rx"], m["ry"], m["rh"]))
    elif c == "wait":
        end = time.time() + (float(rest[0]) if rest else 120)
        while time.time() < end:
            if not get("/tab/state?since=999999999").get("busy"):
                print("libre")
                return
            time.sleep(1)
        print("toujours occupé")
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
