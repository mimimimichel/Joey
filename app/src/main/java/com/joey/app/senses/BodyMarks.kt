package com.joey.app.senses

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Où est l'avant du corps ? Deux pastilles blanches collées par Michael dans l'axe du Roomba (sur le dôme infrarouge de
 * l'avant et sur l'anneau du capot), seules choses blanches sur le Roomba noir. La tête baissée, la caméra les voit : la
 * droite qui passe par elles est l'axe du Roomba, et son point de fuite (avec l'inclinaison de la caméra) donne l'angle
 * entre la tête et le corps. (24/10 : après un « recentrage » de la nacelle, la tête regardait 30° à droite, puis 15° à
 * gauche ; Joey se croyait « droit devant ».) Pur (sans Android) : testé sur les images de la séance du 24/10.
 */
object BodyMarks {
    /** Une tache claire : centre, taille (pixels) ; [strict] = entourée par le Roomba sur au moins 60 % de son pourtour. */
    data class Mark(val x: Double, val y: Double, val size: Int, val strict: Boolean = true,
                    /** Direction du grand axe (vecteur unitaire dans l'image) et allongement (grand / petit axe). */
                    val ax: Double = 0.0, val ay: Double = 0.0, val elong: Double = 1.0,
                    /** Luminosité et couleur moyennes (0-255) : une pastille est d'un blanc franc. */
                    val lum: Double = 0.0, val chr: Double = 0.0)
    /** [panDeg] : où regarde la tête par rapport à l'avant du corps (+ = à droite) ; [front] : la pastille de l'avant. */
    data class Result(val panDeg: Double, val front: Mark, val back: Mark)

    private fun luma(c: Int) = (0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255))
    private fun chroma(c: Int): Int {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    /**
     * Les taches claires et grises entourées de sombre (pixels ARGB, [w] × [h]), de la plus contrastée à la moins. La
     * luminosité d'une pastille varie beaucoup avec l'exposition (155 à 255 sur les images du 24/10) : on juge le contraste
     * avec ce qui l'entoure (le noir du Roomba), pas un seuil fixe. Un reflet sur le parquet clair n'est pas entouré de
     * sombre : il ne compte pas.
     */
    fun find(px: IntArray, w: Int, h: Int): List<Mark> = analyze(px, w, h).first

    /** Les taches (comme [find]) et l'intérieur du Roomba (masque refermé). */
    private fun analyze(px: IntArray, w: Int, h: Int): Pair<List<Mark>, BooleanArray> {
        // Le Roomba : la grande zone sombre qui touche le bas de l'image (la tête baissée, il occupe le bas). Seules comptent
        // les taches claires prises dedans (24/10, idée de Michael : à ce niveau, le seul clair sur le Roomba, ce sont les
        // pastilles) ; le parquet clair au-delà de son bord ne compte pas.
        val body = BooleanArray(w * h)
        // Seuil du sombre adapté à l'image (méthode d'Otsu, entre le Roomba et le sol), entre 90 et 150 : sous un éclairage
        // vif, l'anneau gris du Roomba dépasse 100 et sortait de la zone sombre (pastille de l'anneau ratée).
        val dark = run {
            val hist = IntArray(256); for (c in px) hist[luma(c).toInt().coerceIn(0, 255)]++
            val total = px.size.toDouble(); var sum = 0.0; for (i in 0..255) sum += i * hist[i]
            var sumB = 0.0; var wB = 0.0; var best = 0.0; var t = 100
            for (i in 0..255) {
                wB += hist[i]; if (wB == 0.0) continue
                val wF = total - wB; if (wF == 0.0) break
                sumB += i * hist[i]
                val mB = sumB / wB; val mF = (sum - sumB) / wF
                val between = wB * wF * (mB - mF) * (mB - mF)
                if (between > best) { best = between; t = i }
            }
            t.coerceIn(90, 150).toDouble()
        }
        run {
            val st = IntArray(w * h); var sp = 0
            for (x in 0 until w) { val k = (h - 1) * w + x; if (luma(px[k]) < dark && !body[k]) { body[k] = true; st[sp++] = k } }
            while (sp > 0) {
                val k = st[--sp]; val x = k % w; val y = k / w
                for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val nk = ny * w + nx
                    if (!body[nk] && luma(px[nk]) < dark) { body[nk] = true; st[sp++] = nk }
                }
            }
        }
        // L'intérieur du Roomba : la zone sombre refermée (petits trous bouchés, dont les pastilles), sans le sol au-delà de
        // son bord. (24/10, S10 : la pastille du bord, collée contre le tapis clair, se fondait en une grande tache avec lui
        // et disparaissait.)
        val inside = closedInside(body, w, h, 6)
        val bright = BooleanArray(w * h) { k -> inside[k] && luma(px[k]) > maxOf(110.0, dark + 10) && chroma(px[k]) < 45 }
        val seen = BooleanArray(w * h)
        val out = ArrayList<Pair<Mark, Double>>()
        val stack = IntArray(w * h)
        val members = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!bright[start] || seen[start]) continue
            var n = 0; var sp = 0; stack[sp++] = start; seen[start] = true
            var sx = 0.0; var sy = 0.0; var sl = 0.0; var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
            var maxL = 0.0; var sc = 0.0
            while (sp > 0) {
                val k = stack[--sp]; members[n] = k; n++
                maxL = maxOf(maxL, luma(px[k])); sc += chroma(px[k])
                val x = k % w; val y = k / w
                sx += x; sy += y; sl += luma(px[k]); x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                if (n > w * h / 40) break // une grande zone claire (parquet, mur) : pas une pastille
                for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val nk = ny * w + nx
                    if (bright[nk] && !seen[nk]) { seen[nk] = true; stack[sp++] = nk }
                }
            }
            // Une pastille : petite, à peu près ramassée (pas une longue bande de lumière).
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (n < 6 || n > w * h / 60) continue
            if (bw > 8 * bh || bh > 8 * bw || n < 0.25 * bw * bh) continue
            val mean = sl / n
            // Entourée de sombre : un anneau autour de la boîte, nettement plus sombre que la tache.
            val m = maxOf(3, maxOf(bw, bh) / 2)
            var dark = 0; var total = 0; var ringSum = 0.0
            for (x in (x0 - m)..(x1 + m)) for (y in (y0 - m)..(y1 + m)) {
                if (x in x0..x1 && y in y0..y1) continue
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                total++
                val l = luma(px[y * w + x]); ringSum += l
                if (body[y * w + x]) dark++
            }
            // Entourée par le Roomba : sur 60 % de son pourtour (sûre), ou 30 % (la pastille du dôme, au bord avant : la
            // moitié de son pourtour donne sur le sol ; elle ne compte qu'à côté d'une pastille sûre).
            if (total < 8 || dark < 0.3 * total) continue
            // Centre : celui du cœur blanc seulement (les pixels proches du plus clair). Sur le dôme gris, la tache débordait
            // sur le métal et son centre glissait de 5 pixels : l'angle était faux de 15° (tête très baissée).
            var cxs = 0.0; var cys = 0.0; var nc = 0
            for (q in 0 until n) { val k = members[q]; if (luma(px[k]) >= maxL - 30) { cxs += k % w; cys += k / w; nc++ } }
            val mx = if (nc > 0) cxs / nc else sx / n; val my = if (nc > 0) cys / nc else sy / n
            // Grand axe (moments d'ordre 2) : une flèche allongée donne directement l'axe du Roomba.
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            val gx = sx / n; val gy = sy / n
            for (q in 0 until n) { val k = members[q]; val dx = k % w - gx; val dy = k / w - gy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
            val th = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
            val tr = (sxx + syy) / n; val det = (sxx * syy - sxy * sxy) / (n.toDouble() * n)
            val disc = kotlin.math.sqrt(maxOf(0.0, tr * tr / 4 - det))
            val l1 = tr / 2 + disc; val l2 = maxOf(1e-6, tr / 2 - disc)
            out.add(Mark(mx, my, n, dark >= 0.6 * total, kotlin.math.cos(th), kotlin.math.sin(th), kotlin.math.sqrt(l1 / l2), mean, sc / n) to
                mean / maxOf(1.0, ringSum / total))
        }
        return out.sortedByDescending { it.second }.map { it.first } to inside
    }

    /** Masque refermé : dilatation de [r] pixels, trous bouchés (ce que le dehors ne rejoint pas), puis érosion de [r]. */
    private fun closedInside(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        fun grow(src: BooleanArray, value: Boolean): BooleanArray {
            // value = true : dilatation (un voisin vrai suffit) ; false : érosion (un voisin faux suffit à éteindre).
            val tmp = BooleanArray(w * h); val out = BooleanArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var any = false
                for (dx in -r..r) { val nx = x + dx; if (nx in 0 until w && src[y * w + nx] == value) { any = true; break } }
                tmp[y * w + x] = if (any) value else !value
            }
            for (y in 0 until h) for (x in 0 until w) {
                var any = false
                for (dy in -r..r) { val ny = y + dy; if (ny in 0 until h && tmp[ny * w + x] == value) { any = true; break } }
                out[y * w + x] = if (any) value else !value
            }
            return out
        }
        val dil = grow(mask, true)
        // Trous : les pixels hors du masque que le bord de l'image ne rejoint pas.
        val outside = BooleanArray(w * h); val st = IntArray(w * h); var sp = 0
        for (x in 0 until w) for (y in intArrayOf(0, h - 1)) { val k = y * w + x; if (!dil[k] && !outside[k]) { outside[k] = true; st[sp++] = k } }
        for (y in 0 until h) for (x in intArrayOf(0, w - 1)) { val k = y * w + x; if (!dil[k] && !outside[k]) { outside[k] = true; st[sp++] = k } }
        while (sp > 0) {
            val k = st[--sp]; val x = k % w; val y = k / w
            if (x > 0 && !dil[k - 1] && !outside[k - 1]) { outside[k - 1] = true; st[sp++] = k - 1 }
            if (x < w - 1 && !dil[k + 1] && !outside[k + 1]) { outside[k + 1] = true; st[sp++] = k + 1 }
            if (y > 0 && !dil[k - w] && !outside[k - w]) { outside[k - w] = true; st[sp++] = k - w }
            if (y < h - 1 && !dil[k + w] && !outside[k + w]) { outside[k + w] = true; st[sp++] = k + w }
        }
        val filled = BooleanArray(w * h) { !outside[it] }
        return grow(filled, false)
    }

    /**
     * La paire par sa forme (24/10, S10) : la pastille de l'anneau est allongée en travers de l'axe ; celle du bord, devant
     * elle (plus loin du centre), est ramassée ou allongée le long de l'axe. L'avant ne dépend donc pas de la place de la
     * caméra (sur le S10, elle est à ~10 cm de l'axe de la nacelle : la droite des pastilles ne passe pas sous elle).
     */
    private fun shapePair(marks: List<Mark>, inside: BooleanArray, w: Int, h: Int,
                          ground: (Mark) -> Pair<Double, Double>?): Pair<Mark, Mark>? {
        val cand = marks.filter { it.size >= 8 && it.chr <= 15 }.take(20)
        if (cand.isEmpty()) return null
        // Blanc franc : au moins 170, et pas loin de la tache la plus claire (l'exposition varie d'une image à l'autre).
        // Plafonné à 200 : un reflet saturé (255) ne doit pas faire rejeter de vraies pastilles à 210.
        val white = maxOf(170.0, minOf(200.0, 0.85 * cand.maxOf { it.lum }))
        fun isInside(x: Double, y: Double): Boolean? {
            val ix = x.toInt(); val iy = y.toInt()
            if (ix < 0 || iy < 0 || ix >= w || iy >= h) return null
            return inside[iy * w + ix]
        }
        var best: Pair<Mark, Mark>? = null; var bestScore = Double.MAX_VALUE
        for (inner in cand) {
            if (!inner.strict || inner.size < 15 || inner.elong < 1.8 || inner.elong > 5.0 || inner.lum < white) continue
            val gi = ground(inner) ?: continue
            for (outer in cand) {
                if (outer === inner || outer.lum < white - 25) continue
                val dx = outer.x - inner.x; val dy = outer.y - inner.y
                val d = hypot(dx, dy)
                if (d < 0.05 * w || d > 0.25 * w) continue
                val ratio = maxOf(outer.size, inner.size).toDouble() / minOf(outer.size, inner.size)
                if (ratio > 4) continue
                val ux = dx / d; val uy = dy / d
                val across = abs(inner.ax * ux + inner.ay * uy) // 0 : l'allongement est bien en travers de l'axe
                if (across > 0.45) continue
                // Celle du bord n'est pas allongée en travers (sinon ce sont deux bandes parallèles : reflets, fils).
                if (outer.elong >= 1.8 && abs(outer.ax * ux + outer.ay * uy) < 0.6) continue
                // Écart sur le capot (hauteur de la caméra = 1) : ~0,18 (4,5 cm sous une caméra à 25 cm).
                val go = ground(outer) ?: continue
                val gd = hypot(go.first - gi.first, go.second - gi.second)
                if (gd < 0.08 || gd > 0.40) continue
                // Le bord du Roomba juste après la pastille de l'avant : un peu plus loin, on sort du Roomba (ou de
                // l'image). Entre les deux, on est sur le Roomba. (La bague claire du mât, près du centre, échoue.)
                if (isInside(outer.x + 0.8 * dx, outer.y + 0.8 * dy) == true) continue
                if (isInside(inner.x + 0.5 * dx, inner.y + 0.5 * dy) == false) continue
                val score = across + 0.3 * (ratio - 1) + (if (outer.strict) 0.0 else 0.2) + abs(gd - 0.18) * 2
                if (score < bestScore) { bestScore = score; best = outer to inner }
            }
        }
        return best
    }

    /**
     * Mesure l'angle tête-corps : il faut les deux pastilles. [pitchDeg] : inclinaison de la caméra (négatif = vers le bas),
     * [hfov]/[vfov] : champ (degrés). Null si on ne voit pas les deux pastilles (tête pas assez baissée, cachées…).
     */
    /** [arrow] : la flèche imprimée est collée (sinon on ne la cherche pas : un faisceau de fils allongé la simulait). */
    /**
     * [centered] : la caméra est au-dessus de l'axe de la nacelle (S8+). Sinon (S10, caméra à ~10 cm de l'axe), seule la
     * paire reconnue par sa forme compte : les replis qui supposent l'axe sous la caméra donnaient de fausses mesures
     * (reflets du mât, fils), et le corps tournait pour rien.
     */
    fun measure(px: IntArray, w: Int, h: Int, pitchDeg: Double, hfov: Double, vfov: Double, arrow: Boolean = false,
                centered: Boolean = true): Result? {
        // La tête doit être bien baissée vers son corps (sinon ce qu'il voit n'est pas le Roomba).
        if (pitchDeg > -35) return null
        val fx = (w / 2.0) / tan(Math.toRadians(hfov / 2)); val fy = (h / 2.0) / tan(Math.toRadians(vfov / 2))
        val cx = w / 2.0; val cy = h / 2.0
        // La caméra est au-dessus du centre du Roomba (la tige de la nacelle est au milieu) : l'axe du Roomba passe sous elle.
        // Le point du dessus du Roomba juste sous la caméra se voit (ou se verrait) à la colonne du milieu, à cette ligne :
        val below = cy + fy / tan(Math.toRadians(-pitchDeg))
        // Ni les reflets sur la tige de la nacelle (près du bas de l'image, autour du point sous la caméra), ni les têtes de
        // vis (minuscules) : seules les taches assez grosses, devant.
        val (all, inside) = analyze(px, w, h)
        // La paire reconnue par sa forme d'abord (valable où que soit la caméra).
        val a = Math.toRadians(-pitchDeg)
        val ground = { m: Mark ->
            val xn = (m.x - cx) / fx; val yn = (m.y - cy) / fy
            val den = yn * cos(a) + kotlin.math.sin(a)
            if (den < 1e-3) null else (xn / den) to ((cos(a) - yn * kotlin.math.sin(a)) / den)
        }
        shapePair(all.filter { it.y < 0.97 * h }, inside, w, h, ground)?.let { (f, b) -> return vanish(f, b, cx, cy, fx, fy, pitchDeg) }
        if (!centered) return null
        val marks = all.filter { it.size >= 12 && it.y < 0.95 * h && it.y < below - 0.08 * h }
        if (marks.isEmpty()) return null
        fun residual(a: Mark, b: Mark): Double {
            val dy = b.y - a.y
            if (abs(dy) < 1e-6) return Double.MAX_VALUE
            return abs(a.x + (b.x - a.x) * (below - a.y) / dy - cx)
        }
        // 1. Une paire : au moins une pastille sûre, écartées de 3 à 30 % de la largeur, de tailles comparables, et leur
        //    droite passe près du point sous la caméra (une fausse paire, non).
        val cand = marks.take(6)
        var best: Pair<Mark, Mark>? = null; var bestScore = Double.MAX_VALUE
        for (i in cand.indices) for (j in i + 1 until cand.size) {
            val a = cand[i]; val b = cand[j]
            if (!a.strict || !b.strict) continue // deux pastilles sûres (une pastille douteuse faussait l'angle de 15°)
            val d = hypot(a.x - b.x, a.y - b.y)
            if (d < 0.03 * w || d > 0.30 * w) continue
            val ratio = maxOf(a.size, b.size).toDouble() / minOf(a.size, b.size)
            if (ratio > 3) continue
            val res = residual(a, b)
            if (res > 0.20 * w) continue
            // Les plus grosses d'abord : une tête de vis brillante est bien plus petite qu'une pastille.
            val biggest = cand.maxOf { it.size }.toDouble()
            val score = res / w + 0.05 * (i + j) + 0.5 * (1 - minOf(a.size, b.size) / biggest)
            if (score < bestScore) { bestScore = score; best = a to b }
        }
        val front: Mark; val back: Mark
        // 0. La flèche imprimée : une tache sûre, grosse et allongée ; son grand axe est l'axe du Roomba, la pointe (l'avant)
        //    est le bout le plus haut dans l'image (le plus loin de la caméra).
        // (Son axe doit passer près du point sous la caméra : un faisceau de fils allongé au bord du Roomba, non.)
        val arrowMark = if (!arrow) null else marks.filter { m -> m.strict && m.elong >= 3.5 && m.size >= 30 && abs(m.ay) > 1e-3 &&
            abs(m.x + m.ax * (below - m.y) / m.ay - cx) < 0.20 * w }.maxByOrNull { it.size }
        if (arrowMark != null) {
            val arrow = arrowMark
            // La pointe : le bout de l'axe le plus loin du point sous la caméra (pas forcément le plus haut dans l'image : la
            // tête peut regarder de côté).
            val sgn = if ((arrow.x - cx) * arrow.ax + (arrow.y - below) * arrow.ay >= 0) 1.0 else -1.0
            val ux = arrow.ax * sgn; val uy = arrow.ay * sgn
            val half = kotlin.math.sqrt(arrow.size.toDouble())
            val f = Mark(arrow.x + ux * half, arrow.y + uy * half, arrow.size); val b = Mark(arrow.x - ux * half, arrow.y - uy * half, arrow.size)
            return vanish(f, b, cx, cy, fx, fy, pitchDeg)
        }
        if (best != null) {
            // La pastille de l'avant est la plus loin du point sous la caméra (24/10 : tête 70° à gauche, l'axe était presque
            // horizontal dans l'image ; « la plus haute » donnait le mauvais sens).
            fun far(m: Mark) = hypot(m.x - cx, m.y - below)
            front = if (far(best.first) > far(best.second)) best.first else best.second
            back = if (front === best.first) best.second else best.first
        } else {
            // 2. Une seule pastille sûre : l'axe passe par elle et par le point sous la caméra (un peu moins juste : la
            //    caméra n'est pas exactement au-dessus du centre).
            front = marks.firstOrNull { it.strict } ?: return null // la plus contrastée (une pastille blanche sur le noir)
            back = Mark(cx, below, 0)
        }
        return vanish(front, back, cx, cy, fx, fy, pitchDeg)
    }

    /**
     * L'angle tête-corps d'après deux points de l'axe (arrière -> avant) : on les ramène sur le plan du capot (rayon de la
     * caméra inclinée de [pitchDeg], hauteur 1), et la direction arrière -> avant donne l'angle. Même résultat que le point
     * de fuite, mais juste aussi quand l'axe est horizontal dans l'image (tête à 90° du corps). La place de la caméra
     * au-dessus du capot ne change pas la direction d'une droite du capot.
     */
    private fun vanish(front: Mark, back: Mark, cx: Double, cy: Double, fx: Double, fy: Double, pitchDeg: Double): Result? {
        val a = Math.toRadians(-pitchDeg)
        fun ground(m: Mark): Pair<Double, Double>? {
            val xn = (m.x - cx) / fx; val yn = (m.y - cy) / fy
            val den = yn * cos(a) + kotlin.math.sin(a)
            if (den < 1e-3) return null // au-dessus de l'horizon
            return xn / den to (cos(a) - yn * kotlin.math.sin(a)) / den
        }
        val f = ground(front) ?: return null; val b = ground(back) ?: return null
        val dX = f.first - b.first; val dY = f.second - b.second
        if (hypot(dX, dY) < 1e-9) return null
        // L'axe part vers la gauche (dX < 0) : la tête est tournée à droite du corps.
        return Result(-Math.toDegrees(kotlin.math.atan2(dX, dY)), front, back)
    }
}
