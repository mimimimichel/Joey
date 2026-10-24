#!/usr/bin/env python3
"""Garde-fou pendant une campagne : arrête Joey (cmd stop) et la campagne si le câble se tord trop, si la batterie
du téléphone baisse trop, ou si les roues se taisent. Usage : JOEY_HOST=… python3 garde.py DOSSIER"""
import json, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1]
STOP = os.path.join(OUT, "STOP")
CABLE_MAX = 160
BATTERIE_MIN = 25


def pilot(*args, timeout=20):
    try:
        r = subprocess.run([sys.executable, os.path.join(HERE, "joey.py"), *args], capture_output=True, text=True, timeout=timeout)
        return r.stdout + r.stderr
    except subprocess.TimeoutExpired:
        return ""


def halt(why):
    pilot("cmd", "stop")
    with open(STOP, "w") as f:
        f.write(why)
    with open(os.path.join(OUT, "garde.log"), "a") as f:
        f.write(time.strftime("%H:%M:%S ") + "ARRÊT : " + why + "\n")
    print("ARRÊT :", why, flush=True)


while not os.path.exists(STOP):
    out = pilot("tool", "etat", "{}")
    c = re.search(r'tournée de (-?\d+)°', out)
    b = re.search(r'"batterie_telephone":\s*"(\d+)"', out)
    line = time.strftime("%H:%M:%S ") + "câble %s, téléphone %s %%" % (c.group(1) if c else "?", b.group(1) if b else "?")
    with open(os.path.join(OUT, "garde.log"), "a") as f:
        f.write(line + "\n")
    if c and abs(int(c.group(1))) > CABLE_MAX:
        halt("câble tordu de %s°" % c.group(1))
    elif b and int(b.group(1)) < BATTERIE_MIN:
        halt("batterie du téléphone à %s %%" % b.group(1))
    time.sleep(4)
