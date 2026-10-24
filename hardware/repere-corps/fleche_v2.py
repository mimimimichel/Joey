#!/usr/bin/env python3
"""Joey : flèche d'orientation, version 2 (à imprimer en blanc mat, à coller dans l'axe du Roomba, pointe vers l'avant).

D'un seul tenant (la caméra la voit comme une seule forme allongée : son axe donne l'angle tête-corps, la pointe large
donne l'avant). Pointe en dard aux ailes inclinées vers l'arrière, tige qui s'affine vers l'arrière, et un relief en
biseau sur le dessus. 60 × 24 mm, 1,8 mm d'épaisseur. Python pur (pas besoin d'OpenSCAD) : écrit fleche_v2.stl et
apercu_v2.png à côté de ce fichier.
"""
import math, os, struct

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = 1.2   # épaisseur de la plaque (mm)
RELIEF = 0.6 # relief du dessus (mm)


def outline(inset=0.0):
    """Contour de la flèche (mm, pointe vers +x), en morceaux convexes ; [inset] : retrait pour le relief du dessus."""
    i = inset
    tip = 60.0 - 1.6 * i
    # Pointe en dard : ailes à 12 mm de l'axe, encoche au milieu de la base de la pointe.
    head_l = [(tip, 0.0), (38.0 + 1.2 * i, 12.0 - 1.3 * i), (42.0 + 0.6 * i, 3.6 - 0.4 * i), (46.0, 0.0)]
    head_r = [(tip, 0.0), (46.0, 0.0), (42.0 + 0.6 * i, -3.6 + 0.4 * i), (38.0 + 1.2 * i, -12.0 + 1.3 * i)]
    # Tige effilée : 7,2 mm de large contre la pointe, 4 mm à l'arrière.
    stem = [(3.0 + i, -2.0 + i * 0.5), (44.0, -3.6 + i), (44.0, 3.6 - i), (3.0 + i, 2.0 - i * 0.5)]
    # Talon : un petit losange qui termine la tige.
    heel = [(0.0 + i, 0.0), (3.5, -3.2 + i), (6.0, 0.0), (3.5, 3.2 - i)]
    return [head_l, head_r, stem, heel]


def prism(poly, z0, z1):
    """Triangles d'un prisme convexe (fond, dessus, flancs)."""
    tris = []
    n = len(poly)
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
    ux, uy, uz = bx - ax, by - ay, bz - az
    vx, vy, vz = cx - ax, cy - ay, cz - az
    nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
    l = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
    return nx / l, ny / l, nz / l


def write_stl(path, tris):
    with open(path, "wb") as f:
        f.write(b"Joey fleche v2".ljust(80, b" "))
        f.write(struct.pack("<I", len(tris)))
        for t in tris:
            f.write(struct.pack("<3f", *normal(t)))
            for v in t:
                f.write(struct.pack("<3f", *v))
            f.write(b"\0\0")


def preview(path):
    from PIL import Image, ImageDraw
    s = 12  # pixels par mm
    W, H = int(70 * s), int(34 * s)
    im = Image.new("RGB", (W, H), (22, 22, 24))   # le capot noir du Roomba
    d = ImageDraw.Draw(im)
    ox, oy = 5 * s, H / 2
    def P(poly): return [(ox + x * s, oy - y * s) for x, y in poly]
    for poly in outline():
        d.polygon(P(poly), fill=(215, 215, 210))
    for poly in outline(1.0):
        d.polygon(P(poly), fill=(245, 245, 240))
    from PIL import ImageFont
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 18)
    except OSError:
        font = None
    d.text((10, 8), "Joey : flèche v2, 60 × 24 mm, blanc mat, pointe vers l'avant", fill=(170, 170, 170), font=font)
    im.save(path)


if __name__ == "__main__":
    tris = []
    for poly in outline():
        tris += prism(ccw(poly), 0.0, BASE)
    for poly in outline(1.0):
        tris += prism(ccw(poly), BASE, BASE + RELIEF)
    write_stl(os.path.join(HERE, "fleche_v2.stl"), tris)
    preview(os.path.join(HERE, "apercu_v2.png"))
    print(len(tris), "triangles")
