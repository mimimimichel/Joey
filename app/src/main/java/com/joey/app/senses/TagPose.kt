package com.joey.app.senses

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Se repérer avec les étiquettes imprimées (AprilTag tag36h11, carré noir de [size] m) collées aux murs :
 * 1. [measure] : depuis les 4 coins de l'étiquette dans l'image, où est la caméra par rapport à elle (homographie du
 *    plan de l'étiquette, décomposée avec le champ de la caméra) ;
 * 2. [register] : la première fois, sa position à lui (sûre, près de sa base) donne la place de l'étiquette sur la carte ;
 * 3. [locate] : ensuite, chaque fois qu'il la revoit, l'étiquette donne sa position et le cap de sa caméra, sans dérive.
 *
 * Repères : carte de Joey (x devant au départ, y à droite, cap en degrés, + = à droite) ; étiquette vue de face :
 * X à droite, Y vers le bas, Z dans le mur. Pur (sans Android) : testé sur des étiquettes projetées.
 */
object TagPose {
    /** Ce que la caméra voit d'une étiquette : à [distance] m devant elle, décalée de [lateral] m (vers la droite de celui
     *  qui la regarde), la caméra tournée de [yawDeg] (+ = à droite) par rapport à « bien en face ». */
    data class View(val distance: Double, val lateral: Double, val yawDeg: Double)

    /** Une étiquette sur la carte : son centre et la direction qui sort du mur vers la pièce (degrés, repère de la carte). */
    data class Placed(val id: Int, val x: Double, val y: Double, val outDeg: Double, val room: String = "")

    data class Pose(val x: Double, val y: Double, val headingDeg: Double)

    /**
     * [corners] : les 4 coins de l'étiquette dans l'image (pixels), dans l'ordre haut-gauche, haut-droit, bas-droit,
     * bas-gauche (vus de face) ; [w] × [h] : taille de l'image ; [hfov]/[vfov] : champ (degrés). Null si incohérent.
     *
     * Un carré vu presque de face est ambigu (deux inclinaisons donnent presque la même image : un pixel de bruit faisait
     * se tromper de 1,5 m). On se sert de ce qu'on sait : l'étiquette est verticale sur un mur, la caméra et elle sont
     * à peu près à la même hauteur ([heightGap] m d'écart attendu). On ajuste alors 5 valeurs (distance, décalage, angle,
     * inclinaison de la caméra, petit écart de hauteur) aux 4 coins, par moindres carrés (Levenberg-Marquardt), en
     * partant d'une première estimation par la taille apparente.
     *
     * Loin de l'étiquette, l'angle sous lequel on la voit est mal connu (il se lit à la différence de hauteur entre ses
     * bords : à 2,5 m, un pixel de bruit le fausse d'une vingtaine de degrés), alors que sa distance et sa direction sont
     * précises. [yawPrior] : l'angle attendu (d'après le cap que Joey croit avoir et la place connue de l'étiquette), à
     * [yawSigma] degrés près : avec lui, la position devient précise.
     */
    fun measure(corners: List<Pair<Double, Double>>, w: Int, h: Int, hfov: Double, vfov: Double, size: Double = 0.16,
                heightGap: Double = 0.0, yawPrior: Double? = null, yawSigma: Double = 5.0): View? {
        if (corners.size != 4) return null
        val fx = (w / 2.0) / tan(Math.toRadians(hfov / 2)); val fy = (h / 2.0) / tan(Math.toRadians(vfov / 2))
        val cx = w / 2.0; val cy = h / 2.0
        // Première estimation : distance par la hauteur apparente (côtés gauche et droit), direction par le centre.
        val hl = hypot(corners[3].first - corners[0].first, corners[3].second - corners[0].second)
        val hr = hypot(corners[2].first - corners[1].first, corners[2].second - corners[1].second)
        if (hl < 3 || hr < 3) return null
        val dist0 = size * fy / ((hl + hr) / 2)
        val uc = corners.sumOf { it.first } / 4; val vc = corners.sumOf { it.second } / 4
        val bearing0 = Math.toDegrees(kotlin.math.atan((uc - cx) / fx))   // l'étiquette est à droite de l'axe : bearing > 0
        val pitch0 = -Math.toDegrees(kotlin.math.atan((vc - cy) / fy))    // l'étiquette est sous l'axe : caméra levée
        // Paramètres : distance (m devant l'étiquette), décalage (m, droite de qui la regarde), angle (° : + = à droite),
        // inclinaison (° : + = vers le haut), écart de hauteur caméra - étiquette (m).
        // Départ : juste en face (décalage nul), tourné de façon à voir l'étiquette là où elle est dans l'image (si la caméra
        // tourne à droite, l'étiquette passe à gauche : angle = -direction).
        var p = doubleArrayOf(dist0, 0.0, yawPrior ?: -bearing0, pitch0, heightGap)
        // Avec un angle attendu : le décalage qui place l'étiquette là où elle est dans l'image.
        if (yawPrior != null) p[1] = dist0 * kotlin.math.tan(Math.toRadians(yawPrior + bearing0))
        val target = DoubleArray(8) { k -> if (k % 2 == 0) corners[k / 2].first else corners[k / 2].second }
        fun residuals(q: DoubleArray): DoubleArray? {
            val pr = projectLocal(q, size, w, h, fx, fy) ?: return null
            val r = DoubleArray(10)
            for (k in 0 until 8) r[k] = pr[k] - target[k]
            r[8] = (q[4] - heightGap) * fy / 0.5 // écart de hauteur : de l'ordre de quelques centimètres, pas plus
            r[9] = if (yawPrior != null) wrap(q[2] - yawPrior) / yawSigma * 3.0 else 0.0 // 1 sigma ≈ 3 pixels
            return r
        }
        var r = residuals(p) ?: return null
        var cost = r.sumOf { it * it }
        var mu = 1e-3
        repeat(60) {
            // Jacobien numérique (9 × 5).
            val jac = Array(10) { DoubleArray(5) }
            for (j in 0 until 5) {
                val step = if (j == 0 || j == 1 || j == 4) 1e-4 else 1e-3
                val q = p.copyOf(); q[j] += step
                val rq = residuals(q) ?: return@repeat
                for (i in 0 until 10) jac[i][j] = (rq[i] - r[i]) / step
            }
            // (JᵀJ + mu diag) δ = -Jᵀ r
            val a = Array(5) { DoubleArray(6) }
            for (i in 0 until 5) {
                for (j in 0 until 5) { var sum = 0.0; for (k in 0 until 10) sum += jac[k][i] * jac[k][j]; a[i][j] = sum }
                a[i][i] *= (1 + mu)
                var g = 0.0; for (k in 0 until 10) g += jac[k][i] * r[k]; a[i][5] = -g
            }
            val delta = solve(a) ?: return@repeat
            val q = DoubleArray(5) { p[it] + delta[it] }
            val rq = residuals(q)
            val cq = rq?.sumOf { it * it } ?: Double.MAX_VALUE
            if (cq < cost) { p = q; r = rq!!; cost = cq; mu = maxOf(mu / 3, 1e-7) } else mu *= 4
        }
        if (p[0] <= 0.05) return null
        // Erreur moyenne de reprojection trop grande : ce n'était pas une étiquette à plat sur un mur.
        if (sqrt(r.take(8).sumOf { it * it } / 8) > 6.0) return null
        return View(p[0], p[1], p[2])
    }

    /** Projette les coins de l'étiquette (repère local : étiquette en (0, 0), sortie du mur vers +x) vus avec les paramètres [q]. */
    private fun projectLocal(q: DoubleArray, size: Double, w: Int, h: Int, fx: Double, fy: Double): DoubleArray? {
        val dist = q[0]; val lat = q[1]; val yaw = q[2]; val pitch = q[3]; val dz = q[4]
        // Caméra : devant l'étiquette (x = dist), la droite de qui regarde l'étiquette est vers -y : décalage lat -> y = -lat.
        val camX = dist; val camY = -lat; val camZ = dz
        val head = Math.toRadians(180.0 + yaw); val pt = Math.toRadians(pitch)
        val f = doubleArrayOf(cos(head) * cos(pt), sin(head) * cos(pt), sin(pt))
        val rgt = doubleArrayOf(cos(head + Math.PI / 2), sin(head + Math.PI / 2), 0.0)
        val down = cross(rgt, f)
        val s = size / 2
        val out = DoubleArray(8)
        var k = 0
        for ((X, Y) in listOf(-s to -s, s to -s, s to s, -s to s)) {
            // Coin de l'étiquette : sa droite (vue de face) est vers -y, son haut vers +z.
            val dx = 0.0 - camX; val dy = -X - camY; val dzp = -Y - camZ
            val zc = dx * f[0] + dy * f[1] + dzp * f[2]
            if (zc <= 0.01) return null
            val xc = dx * rgt[0] + dy * rgt[1] + dzp * rgt[2]
            val yc = dx * down[0] + dy * down[1] + dzp * down[2]
            out[k++] = w / 2.0 + fx * xc / zc
            out[k++] = h / 2.0 + fy * yc / zc
        }
        return out
    }

    /** Résout le système 5 × 5 (matrice augmentée), pivot partiel. */
    private fun solve(a: Array<DoubleArray>): DoubleArray? {
        val n = a.size
        for (col in 0 until n) {
            var p = col
            for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[p][col])) p = r
            if (kotlin.math.abs(a[p][col]) < 1e-15) return null
            val t = a[p]; a[p] = a[col]; a[col] = t
            for (r in 0 until n) if (r != col) { val fct = a[r][col] / a[col][col]; for (c in col..n) a[r][c] -= fct * a[col][c] }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    /** La place de l'étiquette sur la carte, d'après la pose de la caméra ([cam], cap de la caméra) et ce qu'elle voit. */
    fun register(id: Int, cam: Pose, v: View, room: String = ""): Placed {
        val out = cam.headingDeg - v.yawDeg - 180.0
        val (nx, ny) = dir(out); val (rx, ry) = dir(out - 90.0)
        return Placed(id, cam.x - v.distance * nx - v.lateral * rx, cam.y - v.distance * ny - v.lateral * ry, wrap(out), room)
    }

    /** La pose de la caméra sur la carte, d'après une étiquette connue et ce qu'elle voit. */
    fun locate(tag: Placed, v: View): Pose {
        val (nx, ny) = dir(tag.outDeg); val (rx, ry) = dir(tag.outDeg - 90.0)
        return Pose(tag.x + v.distance * nx + v.lateral * rx, tag.y + v.distance * ny + v.lateral * ry, wrap(tag.outDeg + 180.0 + v.yawDeg))
    }

    /** Projette les coins d'une étiquette posée [tag] vue depuis la caméra [cam] (pour les tests et la simulation). */
    fun project(tag: Placed, cam: Pose, camHeight: Double, tagHeight: Double, w: Int, h: Int, hfov: Double, vfov: Double,
                size: Double = 0.16): List<Pair<Double, Double>>? {
        val fx = (w / 2.0) / tan(Math.toRadians(hfov / 2)); val fy = (h / 2.0) / tan(Math.toRadians(vfov / 2))
        val (rx, ry) = dir(tag.outDeg - 90.0)
        val (fwx, fwy) = dir(cam.headingDeg); val (rgx, rgy) = dir(cam.headingDeg + 90.0)
        val s = size / 2
        return listOf(-s to -s, s to -s, s to s, -s to s).map { (X, Y) ->
            // Point du monde (x, y au sol, z vers le haut).
            val px = tag.x + X * rx; val py = tag.y + X * ry; val pz = tagHeight - Y
            val dx = px - cam.x; val dy = py - cam.y; val dz = pz - camHeight
            val zc = dx * fwx + dy * fwy; val xc = dx * rgx + dy * rgy; val yc = -dz
            if (zc <= 0.01) return null
            (w / 2.0 + fx * xc / zc) to (h / 2.0 + fy * yc / zc)
        }
    }

    // ---- calculs ----------------------------------------------------------------------------------------------------
    private fun dir(deg: Double) = cos(Math.toRadians(deg)) to sin(Math.toRadians(deg))
    fun wrap(a: Double): Double { var x = a % 360.0; if (x > 180) x -= 360.0; if (x <= -180) x += 360.0; return x }
    private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** Distance entre deux poses (m), pour les tests. */
    fun gap(a: Pose, b: Pose) = hypot(a.x - b.x, a.y - b.y)
}
