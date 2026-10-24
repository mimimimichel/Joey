#!/usr/bin/env python3
"""Campagne d'essais de Joey : enchaîne les essais sans pause, enregistre tout (résultats, journal, images).

Usage : JOEY_HOST=… python3 campagne.py DOSSIER [essai …]
Chaque essai écrit une ligne JSON dans DOSSIER/journal.jsonl ; les images vont dans DOSSIER/.
Le garde-fou (garde.py) tourne à côté et arrête tout si le câble se tord trop ou si la batterie baisse.
"""
import json, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
JOURNAL = os.path.join(OUT, "journal.jsonl")
STOP = os.path.join(OUT, "STOP")


def pilot(*args, timeout=120):
    try:
        r = subprocess.run([sys.executable, os.path.join(HERE, "joey.py"), *args], capture_output=True, text=True, timeout=timeout)
        return (r.stdout + r.stderr).strip()
    except subprocess.TimeoutExpired:
        return "(délai dépassé)"


def tool(name, args=None, timeout=120):
    out = pilot("tool", name, json.dumps(args or {}), timeout=timeout)
    m = re.search(r'"result":\s*"(.*?)"\s*[,}]', out, re.S)
    return m.group(1) if m else out[-400:]


def etat():
    out = pilot("tool", "etat", "{}", timeout=30)
    def g(k):
        m = re.search(r'"%s":\s*"(.*?)"' % k, out)
        return m.group(1) if m else None
    return {"tete": g("tete"), "cable": g("cable_tete"), "roues": g("roues"), "tel": g("batterie_telephone")}


def frame(name):
    p = os.path.join(OUT, name + ".jpg")
    pilot("frame", p, timeout=40)
    return p


def feed():
    return [l for l in pilot("feed", timeout=30).splitlines() if "coup d'œil" not in l and "ronde du regard" not in l]


def note(step, **kw):
    kw.update(step=step, t=time.strftime("%H:%M:%S"))
    with open(JOURNAL, "a") as f:
        f.write(json.dumps(kw, ensure_ascii=False) + "\n")
    print(json.dumps(kw, ensure_ascii=False)[:600], flush=True)


def stopped():
    return os.path.exists(STOP)


def wait_log(pattern, secs):
    """Attend une ligne du journal de Joey qui contient [pattern] ; rend toutes les lignes lues."""
    lines = []
    end = time.time() + secs
    while time.time() < end and not stopped():
        time.sleep(3)
        new = feed()
        lines += new
        if any(re.search(pattern, l) for l in new):
            break
    return lines


def tilt_to(target):
    t = etat()["tete"] or ""
    m = re.search(r"inclinée à (-?\d+)", t)
    cur = int(m.group(1)) if m else 0
    d = cur - target
    if d >= 0:
        tool("regarder", {"direction": "bas", "degres": d})
    else:
        tool("regarder", {"direction": "haut", "degres": -d})
    time.sleep(0.8)


# ---- essais ------------------------------------------------------------------------------------------
def nacelle():
    """Mode de la nacelle : le corps tourne de 45°, la tête doit rester fixe (verrouillée)."""
    a = etat(); r1 = tool("tourner_corps", {"degres": 45}); b = etat(); time.sleep(2); c = etat()
    r2 = tool("tourner_corps", {"degres": -45}); d = etat()
    note("nacelle", avant=a, tourne=r1, apres=b, apres2s=c, retour=r2, fin=d)


def pastilles():
    """Images tête baissée à plusieurs angles (tête seule), pour le détecteur hors ligne."""
    tool("regarder", {"direction": "gauche", "degres": 50}); k = 0
    for step in range(6):
        if step:
            tool("regarder", {"direction": "droite", "degres": 20})
        for target in (-72, -60):
            if stopped():
                return
            tilt_to(target)
            e = etat(); k += 1
            p = frame("pastilles-%02d" % k)
            note("pastilles", image=p, cible=target, **e)
    tool("regarder", {"direction": "gauche", "degres": 50})


def alignement(n=3):
    for i in range(n):
        if stopped():
            return
        if i:
            tool("regarder", {"direction": "gauche" if i % 2 else "droite", "degres": 30})
        feed()
        pilot("cmd", "center")
        lines = wait_log(r"Tête : |Je ne tourne pas|mode suivi", 90)
        e = etat()
        tilt_to(-72); p = frame("alignement-%d" % (i + 1)); tilt_to(0)
        note("alignement", essai=i + 1, journal=lines, image=p, **e)


def ligne_droite():
    tilt_to(0); a = frame("droit-avant")
    r1 = tool("avancer", {"metres": 0.5}); b = frame("droit-apres")
    r2 = tool("avancer", {"metres": -0.5}); c = frame("droit-retour")
    note("ligne_droite", aller=r1, retour=r2, images=[a, b, c], journal=feed())


def rotations():
    tilt_to(0); a = frame("rot-0"); res = []
    for d in (90, -90, 90, -90):
        if stopped():
            return
        res.append(tool("tourner_corps", {"degres": d}))
    b = frame("rot-fin")
    note("rotations", resultats=res, images=[a, b], journal=feed(), **etat())


def capteurs():
    note("capteurs", sortie=pilot("tool", "capteurs", json.dumps({"secondes": 3}), timeout=30)[-900:])


def scan():
    feed(); pilot("cmd", "scan")
    lines = wait_log(r"Tour d'horizon|tour d'horizon", 150)
    note("scan", journal=lines, carte=pilot("map", timeout=30)[-3000:], **etat())


def explorer():
    r = tool("explorer", {"secondes": 60}, timeout=150)
    note("explorer", resultat=r, journal=feed(), carte=pilot("map", timeout=30)[-3000:], **etat())
    r2 = tool("revenir", {}, timeout=150)
    note("revenir", resultat=r2, journal=feed(), **etat())


def accostage():
    feed(); pilot("cmd", "dock"); t0 = time.time(); ok = False; lines = []
    while time.time() - t0 < 240 and not stopped():
        time.sleep(10); lines += feed()
        if "sur ta base" in (etat()["roues"] or ""):
            ok = True; break
    note("accostage", reussi=ok, secondes=int(time.time() - t0), journal=lines, **etat())


def sortie():
    r = tool("avancer", {"metres": -0.05})
    note("sortie_base", resultat=r, journal=feed(), capteurs=pilot("tool", "capteurs", '{"secondes":1}', timeout=30)[-500:], **etat())


def brc():
    note("brc", resultat=pilot("cmd", "brc", timeout=90), journal=feed())


def carto():
    feed(); pilot("cmd", "carto", "secondes=120")
    lines = wait_log(r"Cartographie (finie|terminée)|carto.*fin", 200)
    note("carto", journal=lines, carte=pilot("map", timeout=30)[-3000:], **etat())


ESSAIS = {"nacelle": nacelle, "pastilles": pastilles, "alignement": alignement, "ligne_droite": ligne_droite,
          "rotations": rotations, "capteurs": capteurs, "scan": scan, "explorer": explorer, "accostage": accostage,
          "sortie": sortie, "brc": brc, "carto": carto}

if __name__ == "__main__":
    pilot("cmd", "test")
    for name in sys.argv[2:] or list(ESSAIS):
        if stopped():
            note("arret", raison=open(STOP).read().strip())
            break
        t0 = time.time()
        try:
            ESSAIS[name]()
        except Exception as e:  # un essai qui casse ne casse pas la campagne
            note(name, erreur=repr(e))
        note("fin_essai", essai=name, secondes=int(time.time() - t0))
    note("campagne_finie")
