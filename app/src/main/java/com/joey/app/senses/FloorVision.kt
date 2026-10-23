package com.joey.app.senses

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Voir l'obstacle avant de le toucher, avec la caméra seule (sur le téléphone, sans quota, plusieurs fois par seconde).
 *
 * Méthode classique des petits robots (détection d'obstacles « par l'apparence du sol ») :
 * 1. il apprend à quoi ressemble le sol (couleur, luminosité), d'après le bas de l'image quand la voie est libre ;
 * 2. dans chaque colonne de l'image, il remonte depuis le bas jusqu'au premier endroit qui ne ressemble plus au sol ;
 * 3. connaissant la hauteur de la caméra et son inclinaison, la ligne de l'image donne la distance au sol de cet endroit.
 *
 * Résultat : pour chaque direction, la distance de sol libre ; et surtout la place libre droit devant (le couloir de
 * la largeur de son corps). Ça ne marche que si la caméra voit le sol (tête un peu baissée) ; sinon : « je ne sais pas ».
 */
class FloorVision(
    /** Hauteur de la caméra au-dessus du sol (m). */
    var cameraHeight: Double = 0.40,
) {
    /** Une direction (degrés, + = droite) et la distance de sol libre (m) ; [Double.POSITIVE_INFINITY] = tout le sol vu est libre. */
    data class Ray(val bearing: Double, val distance: Double)

    class Result(val rays: List<Ray>, val clearAhead: Double, val floorVisible: Boolean)

    // Modèle du sol : moyenne et dispersion de (luminosité, chrominances).
    private var model: DoubleArray? = null
    private var spread: DoubleArray = doubleArrayOf(18.0, 8.0, 8.0)

    private fun yuv(c: Int): DoubleArray {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        return doubleArrayOf(y, (b - y) * 0.565, (r - y) * 0.713)
    }

    /** Écart (en « dispersions ») entre un pixel et le sol appris ; la luminosité compte moins (ombres, reflets). */
    private fun distance(p: DoubleArray, m: DoubleArray): Double {
        val dy = (p[0] - m[0]) / (spread[0] * 1.8)
        val du = (p[1] - m[1]) / spread[1]
        val dv = (p[2] - m[2]) / spread[2]
        return sqrt(dy * dy + du * du + dv * dv)
    }

    /**
     * Apprend (ou corrige un peu) le sol d'après le bas-centre de l'image : à appeler quand on sait que c'est du sol
     * (il vient de rouler là sans rien toucher). [rate] : 1 = remplace, 0,1 = corrige doucement.
     */
    fun learnFloor(px: IntArray, w: Int, h: Int, rate: Double = 0.15) {
        val samples = ArrayList<DoubleArray>()
        for (y in (h * 0.85).toInt() until h) for (x in (w * 0.35).toInt() until (w * 0.65).toInt()) samples.add(yuv(px[y * w + x]))
        if (samples.isEmpty()) return
        val mean = DoubleArray(3) { k -> samples.sumOf { it[k] } / samples.size }
        val sd = DoubleArray(3) { k -> sqrt(samples.sumOf { (it[k] - mean[k]) * (it[k] - mean[k]) } / samples.size) }
        // Un bas d'image très bariolé, ce n'est pas un sol uni : on n'apprend pas (mieux vaut « je ne sais pas »).
        if (sd[0] > 45) return
        val m = model
        model = if (m == null || rate >= 1) mean else DoubleArray(3) { m[it] * (1 - rate) + mean[it] * rate }
        spread = DoubleArray(3) { k -> max(if (k == 0) 12.0 else 6.0, spread[k] * (1 - rate) + (sd[k] * 2.5) * rate) }
    }

    val ready: Boolean get() = model != null

    /**
     * Analyse une image (pixels ARGB, [w] × [h], à l'endroit). [pitchDeg] : inclinaison de la caméra (négatif = vers le
     * sol), [hfov]/[vfov] : champ de la caméra en degrés. [halfWidth] : demi-largeur de son corps (m).
     */
    fun analyse(px: IntArray, w: Int, h: Int, pitchDeg: Double, hfov: Double, vfov: Double, halfWidth: Double = 0.19, columns: Int = 16): Result {
        val m = model ?: return Result(emptyList(), Double.NaN, false)
        val rays = ArrayList<Ray>()
        val colW = w / columns
        var floorSeen = 0
        for (c in 0 until columns) {
            val x0 = c * colW; val x1 = min(w, x0 + colW)
            var boundary = -1 // première ligne (depuis le bas) qui n'est plus du sol
            var bad = 0
            var y = h - 1
            while (y >= 0) {
                // Moyenne de la petite bande (ligne y, colonne c) : moins sensible au bruit qu'un pixel.
                var a = 0.0; var b = 0.0; var d = 0.0; var n = 0
                var x = x0
                while (x < x1) { val p = yuv(px[y * w + x]); a += p[0]; b += p[1]; d += p[2]; n++; x += 2 }
                val dist = distance(doubleArrayOf(a / n, b / n, d / n), m)
                if (dist > 3.0) { if (++bad >= 2) { boundary = y + 1; break } } else bad = 0
                y--
            }
            if (boundary != h) floorSeen++ // le bas de cette colonne ressemble au sol
            val bearing = ((x0 + x1) / 2.0 / w - 0.5) * hfov
            rays.add(Ray(bearing, rowToDistance(if (boundary < 0) 0 else boundary, h, pitchDeg, vfov)))
        }
        // Couloir droit devant : les colonnes dont la direction, à la distance trouvée, tombe dans la largeur du corps.
        var clear = Double.POSITIVE_INFINITY
        for (r in rays) {
            val lateral = abs(tan(Math.toRadians(r.bearing))) * min(r.distance, 3.0)
            if (lateral <= halfWidth || abs(r.bearing) < 8) clear = min(clear, r.distance)
        }
        return Result(rays, clear, floorSeen >= columns / 2)
    }

    /** Ligne de l'image (0 en haut) -> distance au sol (m) devant la caméra ; l'infini si la ligne est au-dessus de l'horizon. */
    fun rowToDistance(row: Int, h: Int, pitchDeg: Double, vfov: Double): Double {
        // Angle sous l'horizontale du rayon qui passe par cette ligne (le centre de l'image suit l'axe de la caméra).
        val off = (row + 0.5 - h / 2.0) / (h / 2.0) // -1 en haut, +1 en bas
        val below = -pitchDeg + Math.toDegrees(atan(off * tan(Math.toRadians(vfov / 2))))
        if (below <= 1.0) return Double.POSITIVE_INFINITY
        return cameraHeight / tan(Math.toRadians(below))
    }
}
