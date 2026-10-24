package com.joey.app.senses

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Voir les obstacles avec la profondeur estimée par un réseau (MiDaS small, sur le téléphone), pas avec la couleur.
 * (24/10 : la couleur du sol ne voyait pas un pied de chaise en bois clair sur un tapis crème ; MiDaS le voit à 0,38 m.)
 *
 * MiDaS donne une profondeur « inverse » relative (plus grand = plus proche), à une échelle et un décalage près. Mais on
 * connaît la géométrie : la caméra est à [cameraHeight] du sol, inclinée de [pitchDeg] ; pour chaque ligne de l'image,
 * on sait ce que vaudrait l'inverse de la profondeur si c'était du sol. On ajuste échelle et décalage sur le bas de
 * l'image (la médiane de chaque ligne : un pied de chaise y est minoritaire), puis, dans chaque colonne, on remonte
 * depuis le bas jusqu'au premier endroit nettement plus proche que le sol attendu : c'est le pied de l'obstacle, et sa
 * ligne donne sa distance. Pur (sans Android) : testé sur de vraies cartes de profondeur.
 */
class DepthGround(var cameraHeight: Double = 0.40) {
    class Result(val rays: List<FloorVision.Ray>, val clearAhead: Double, val ok: Boolean)

    /**
     * [inv] : profondeur inverse relative, [w] × [h], ligne par ligne (0 en haut). [pitchDeg] : inclinaison de la caméra
     * (négatif = vers le sol). [hfov]/[vfov] : champ de la caméra (degrés).
     */
    fun analyse(inv: FloatArray, w: Int, h: Int, pitchDeg: Double, hfov: Double, vfov: Double,
                halfWidth: Double = 0.19, columns: Int = 16): Result {
        val tanHalf = tan(Math.toRadians(vfov / 2))
        // Pour chaque ligne : inverse de la profondeur du sol (à un facteur près) et distance au sol (m).
        val g = DoubleArray(h) { Double.NaN }
        val dist = DoubleArray(h) { Double.POSITIVE_INFINITY }
        for (r in 0 until h) {
            val beta = atan(((r + 0.5 - h / 2.0) / (h / 2.0)) * tanHalf)
            val alpha = Math.toRadians(-pitchDeg) + beta
            if (alpha > Math.toRadians(1.0)) {
                g[r] = sin(alpha) / (cameraHeight * cos(beta))
                dist[r] = cameraHeight / tan(alpha)
            }
        }
        // Ajustement sur le bas de l'image (30 %) : médiane de chaque ligne = a * sol + b.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        val row = FloatArray(w)
        for (r in (h * 0.70).toInt() until h) {
            if (g[r].isNaN()) continue
            System.arraycopy(inv, r * w, row, 0, w)
            row.sort()
            xs.add(g[r]); ys.add(row[w / 2].toDouble())
        }
        if (xs.size < 8) return Result(emptyList(), Double.NaN, false)
        val mx = xs.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0
        for (k in xs.indices) { sxy += (xs[k] - mx) * (ys[k] - my); sxx += (xs[k] - mx) * (xs[k] - mx) }
        if (sxx <= 0) return Result(emptyList(), Double.NaN, false)
        val a = sxy / sxx; val b = my - a * mx
        // Le sol doit se rapprocher vers le bas de l'image : sinon le bas n'est pas du sol (face à un mur…), on ne sait pas.
        if (a <= 0) return Result(emptyList(), Double.NaN, false)
        val colW = w / columns
        val rays = ArrayList<FloorVision.Ray>(columns)
        val colVals = FloatArray(colW)
        for (c in 0 until columns) {
            val x0 = c * colW
            var bound = -1; var bad = 0; var badSum = 0.0
            var r = h - 1
            while (r >= 0) {
                if (g[r].isNaN()) break
                System.arraycopy(inv, r * w + x0, colVals, 0, colW)
                colVals.sort()
                val v = colVals[colW / 2].toDouble()
                val expected = a * g[r] + b
                if (v > expected * 1.15 + 0.02 * abs(a)) {
                    badSum += v
                    if (++bad >= 4) { bound = r + 3; break }
                } else { bad = 0; badSum = 0.0 }
                r--
            }
            val bearing = ((x0 + colW / 2.0) / w - 0.5) * hfov
            var d = Double.POSITIVE_INFINITY
            if (bound >= 0) {
                d = dist[min(bound, h - 1)]
                // Un objet vertical ne se distingue du sol qu'un peu au-dessus de son pied : la ligne seule le croyait plus
                // loin qu'il n'est (un poteau à 0,8 m vu à 1 m). Sa propre profondeur donne sa distance : on garde la plus
                // prudente. Profondeur le long de l'axe Z = a / (v - b) ; distance au sol = Z cos(alpha) / cos(beta).
                val v = badSum / 4
                if (v - b > 1e-6) {
                    val z = a / (v - b)
                    val rr = min(bound, h - 1)
                    val beta = atan(((rr + 0.5 - h / 2.0) / (h / 2.0)) * tanHalf)
                    val alpha = Math.toRadians(-pitchDeg) + beta
                    val horiz = z * cos(alpha) / cos(beta)
                    if (horiz > 0.05) d = min(d, horiz)
                }
            }
            rays.add(FloorVision.Ray(bearing, d))
        }
        var clear = Double.POSITIVE_INFINITY
        for (ray in rays) {
            val lateral = abs(tan(Math.toRadians(ray.bearing))) * min(ray.distance, 3.0)
            if (lateral <= halfWidth || abs(ray.bearing) < 8) clear = min(clear, ray.distance)
        }
        return Result(rays, clear, true)
    }
}
