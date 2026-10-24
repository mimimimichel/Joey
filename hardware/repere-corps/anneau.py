#!/usr/bin/env python3
"""Joey : anneau d'orientation (autour du mât, sur le capot du Roomba), en quatre quarts.

Anneau noir (comme le capot) de 1,2 mm, symboles blancs en relief de 1 mm par-dessus (changement de filament à 1,2 mm
de hauteur). Un symbole différent à chaque point cardinal : quelle que soit la direction de la tête, la caméra en voit
au moins un et sait lequel.
  avant   : une pointe en dard, tournée vers l'extérieur
  droite  : une barre (dans le sens de l'anneau)
  arrière : deux points
  gauche  : trois points
Python pur. Usage : python3 anneau.py [rayon_interieur_mm] [rayon_exterieur_mm]
Écrit anneau-quart-avant.stl, -droite, -arriere, -gauche (chaque quart porte son symbole en son milieu) et
apercu_anneau.png. Les quarts se touchent bout à bout : coller l'avant dans l'axe du Roomba, puis les autres à la suite.
"""
import math, os, struct, sys

HERE = os.path.dirname(os.path.abspath(__file__))
R_IN = float(sys.argv[1]) if len(sys.argv) > 1 else 90.0
R_OUT = float(sys.argv[2]) if len(sys.argv) > 2 else 114.0
BASE = 1.2
RELIEF = 1.0
STEP = 3.0  # degrés par facette de l'anneau


def band(a0, a1):
    """Morceaux convexes de l'anneau entre les angles a0 et a1 (degrés, 0 = avant, + = vers la droite)."""
    out = []
    n = max(1, int(math.ceil((a1 - a0) / STEP)))
    for k in range(n):
        u = math.radians(a0 + (a1 - a0) * k / n); v = math.radians(a0 + (a1 - a0) * (k + 1) / n)
        out.append([(R_IN * math.cos(u), R_IN * math.sin(u)), (R_OUT * math.cos(u), R_OUT * math.sin(u)),
                    (R_OUT * math.cos(v), R_OUT * math.sin(v)), (R_IN * math.cos(v), R_IN * math.sin(v))])
    return out


def place(poly, angle):
    """Symbole dessiné autour de (0, 0) avec l'extérieur vers +x, posé au milieu de la bande à l'angle donné."""
    rm = (R_IN + R_OUT) / 2
    c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    return [((rm + x) * c - y * s, (rm + x) * s + y * c) for x, y in poly]


def circle(cx, cy, r, n=20):
    return [(cx + r * math.cos(2 * math.pi * k / n), cy + r * math.sin(2 * math.pi * k / n)) for k in range(n)]


def symbol(name):
    w = R_OUT - R_IN  # largeur de la bande
    if name == "avant":   # dard : pointe vers l'extérieur, ailes vers l'intérieur
        h = w * 0.42
        return [[(h, 0.0), (-h, w * 0.5), (-h * 0.45, 0.0)], [(h, 0.0), (-h * 0.45, 0.0), (-h, -w * 0.5)]]
    if name == "droite":  # une barre dans le sens de l'anneau
        return [[(-w * 0.16, -w * 0.62), (w * 0.16, -w * 0.62), (w * 0.16, w * 0.62), (-w * 0.16, w * 0.62)]]
    if name == "arriere":  # deux points
        return [circle(0, -w * 0.38, w * 0.2), circle(0, w * 0.38, w * 0.2)]
    if name == "gauche":   # trois points
        return [circle(0, -w * 0.62, w * 0.17), circle(0, 0, w * 0.17), circle(0, w * 0.62, w * 0.17)]
    raise ValueError(name)


QUARTS = {"avant": 0.0, "droite": 90.0, "arriere": 180.0, "gauche": -90.0}


def prism(poly, z0, z1):
    tris = []; n = len(poly)
    for k in range(1, n - 1):
        a, b, c = poly[0], poly[k], poly[k + 1]
        tris.append(((a[0], a[1], z1), (b[0], b[1], z1), (c[0], c[1], z1)))
        tris.append(((a[0], a[1], z0), (c[0], c[1], z0), (b[0], b[1], z0)))
    for k in range(n):
        a, b = poly[k], poly[(k + 1) % n]
        tris.append(((a[0], a[1], z0), (b[0], b[1], z0), (b[0], b[1], z1)))
        tris.append(((a[0], a[1], z0), (b[0], b[1], z1), (a[0], a[1], z1)))
    return tris


def ccw(poly):
    area = sum(poly[k][0] * poly[(k + 1) % len(poly)][1] - poly[(k + 1) % len(poly)][0] * poly[k][1] for k in range(len(poly)))
    return poly if area > 0 else list(reversed(poly))


def normal(t):
    (ax, ay, az), (bx, by, bz), (cx, cy, cz) = t
    ux, uy, uz = bx - ax, by - ay, bz - az; vx, vy, vz = cx - ax, cy - ay, cz - az
    nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
    l = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
    return nx / l, ny / l, nz / l


def write_stl(path, tris):
    with open(path, "wb") as f:
        f.write(b"Joey anneau".ljust(80, b" ")); f.write(struct.pack("<I", len(tris)))
        for t in tris:
            f.write(struct.pack("<3f", *normal(t)))
            for v in t:
                f.write(struct.pack("<3f", *v))
            f.write(b"\0\0")


def quart(name):
    a = QUARTS[name]
    tris = []
    for p in band(a - 45, a + 45):
        tris += prism(ccw(p), 0.0, BASE)
    for p in symbol(name):
        tris += prism(ccw(place(p, a)), BASE, BASE + RELIEF)
    return tris


def preview(path):
    from PIL import Image, ImageDraw, ImageFont
    s = 2.6; W = int(2 * (R_OUT + 40) * s); im = Image.new("RGB", (W, W), (40, 40, 44)); d = ImageDraw.Draw(im)
    c = W / 2
    # Le capot du Roomba (340 mm) et le mât au centre, pour l'échelle.
    d.ellipse((c - 170 * s, c - 170 * s, c + 170 * s, c + 170 * s), outline=(90, 90, 95), width=3)
    d.ellipse((c - 14 * s, c - 14 * s, c + 14 * s, c + 14 * s), fill=(15, 15, 15))
    def P(poly): return [(c + y * s, c - x * s) for x, y in poly]  # avant vers le haut de l'image, droite à droite
    for name, a in QUARTS.items():
        for p in band(a - 45, a + 45):
            d.polygon(P(p), fill=(18, 18, 20))
        for p in symbol(name):
            d.polygon(P(place(p, a)), fill=(240, 240, 235))
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 16)
    except OSError:
        f = None
    d.text((10, 10), "Joey : anneau Ø %.0f à %.0f mm, 4 quarts. Avant en haut." % (2 * R_IN, 2 * R_OUT), fill=(200, 200, 200), font=f)
    for name, a in QUARTS.items():
        r = R_OUT + 22
        x, y = r * math.cos(math.radians(a)), r * math.sin(math.radians(a))
        d.text((c + y * s - 22, c - x * s - 8), name, fill=(200, 200, 120), font=f)
    im = im.resize((min(W, 1000), min(W, 1000)))
    im.save(path)


if __name__ == "__main__":
    for name in QUARTS:
        write_stl(os.path.join(HERE, "anneau-quart-%s.stl" % name), quart(name))
    preview(os.path.join(HERE, "apercu_anneau.png"))
    print("rayons %.0f-%.0f mm, 4 quarts écrits" % (R_IN, R_OUT))
