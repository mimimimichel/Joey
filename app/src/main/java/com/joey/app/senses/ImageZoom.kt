package com.joey.app.senses

import kotlin.math.abs

/**
 * Entre deux images prises avant et après un petit trajet tout droit : la scène a-t-elle grossi (il s'est rapproché de
 * ce que voit la caméra) ou rapetissé (il s'en est éloigné) ? Sert à l'auto-test du sens des roues : si « avancer »
 * l'éloigne de ce que voit son visage, c'est que le visage regarde vers l'arrière du Roomba.
 *
 * Les images sont de petites images en niveaux de gris (0..1), [w] × [h], ligne par ligne.
 */
object ImageZoom {
    data class Result(val scale: Double, val error: Double, val errorAtOne: Double) {
        /** Le zoom explique nettement mieux la différence que « rien n'a bougé ». */
        val confident get() = errorAtOne > 0 && error < errorAtOne * 0.85 && abs(scale - 1) >= 0.03
    }

    private fun sample(a: FloatArray, w: Int, h: Int, x: Double, y: Double): Float {
        val x0 = x.toInt().coerceIn(0, w - 2); val y0 = y.toInt().coerceIn(0, h - 2)
        val fx = (x - x0).toFloat().coerceIn(0f, 1f); val fy = (y - y0).toFloat().coerceIn(0f, 1f)
        val i = y0 * w + x0
        val top = a[i] * (1 - fx) + a[i + 1] * fx
        val bot = a[i + w] * (1 - fx) + a[i + w + 1] * fx
        return top * (1 - fy) + bot * fy
    }

    /** Écart moyen entre [b] et [a] agrandie de [s] autour du centre (zone centrale seulement, sans les bords). */
    fun errorAt(a: FloatArray, b: FloatArray, w: Int, h: Int, s: Double): Double {
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        var sum = 0.0; var n = 0
        // Moyennes pour ignorer un changement global de lumière.
        var ma = 0.0; var mb = 0.0
        val pts = ArrayList<Pair<Float, Float>>()
        for (y in (h * 0.2).toInt() until (h * 0.8).toInt()) for (x in (w * 0.2).toInt() until (w * 0.8).toInt()) {
            val va = sample(a, w, h, cx + (x - cx) / s, cy + (y - cy) / s)
            val vb = b[y * w + x]
            pts.add(va to vb); ma += va; mb += vb
        }
        if (pts.isEmpty()) return Double.MAX_VALUE
        ma /= pts.size; mb /= pts.size
        for ((va, vb) in pts) { sum += abs((va - ma) - (vb - mb)); n++ }
        return sum / n
    }

    /** Le meilleur zoom entre 0,8 et 1,25 (> 1 : il s'est rapproché de la scène). */
    fun estimate(a: FloatArray, b: FloatArray, w: Int, h: Int): Result {
        var best = 1.0; var bestErr = Double.MAX_VALUE
        var s = 0.80
        while (s <= 1.2501) {
            val e = errorAt(a, b, w, h, s)
            if (e < bestErr) { bestErr = e; best = s }
            s += 0.025
        }
        return Result(best, bestErr, errorAt(a, b, w, h, 1.0))
    }
}
