package com.joey.app.robot

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Recaler un tour d'horizon sur la carte (méthode des aspirateurs à lidar et des logiciels de cartographie comme Karto :
 * recalage par corrélation, d'après Olson, ICRA 2009). Joey a vu, tout autour de lui, des bords d'obstacles (les points
 * du tour d'horizon, à partir de sa position supposée) ; sa position dérive (roues qui patinent). On essaie de petits
 * décalages de position et d'angle, et on garde celui qui fait le mieux tomber les points sur les obstacles déjà connus
 * de la carte. La carte ne se tord plus d'un passage à l'autre. Pur : testé dans un appartement simulé.
 */
object ScanMatcher {
    /** Correction à appliquer à la pose (m, m, degrés autour de sa position), et la qualité du recalage. */
    data class Fix(val dx: Double, val dy: Double, val dthDeg: Double, val score: Double, val baseScore: Double, val used: Int)

    /**
     * [occupied] : carreaux occupés de la carte (clés de [NavGrid.key]) ; [cell] : taille d'un carreau ; ([x], [y]) : où il
     * croit être ; [points] : bords vus, en vecteurs (m) depuis sa position, dans le repère de la carte tel qu'il le croit.
     * Renvoie la meilleure correction, ou null si le recalage n'est pas sûr (trop peu de points, carte trop vide, gain faible).
     */
    fun match(occupied: Set<Long>, cell: Double, x: Double, y: Double, points: List<Pair<Double, Double>>,
              maxShift: Double = 0.35, maxTurnDeg: Double = 10.0, sigma: Double = 0.08): Fix? {
        if (points.size < 15 || occupied.size < 20) return null
        // Champ de vraisemblance : proximité des obstacles connus, jusqu'à 3 sigma.
        val reach = Math.ceil(3 * sigma / cell).toInt()
        val field = HashMap<Long, Double>(occupied.size * 9)
        for (k in occupied) {
            val i = NavGrid.ki(k); val j = NavGrid.kj(k)
            for (di in -reach..reach) for (dj in -reach..reach) {
                val d = hypot(di * cell, dj * cell)
                if (d > 3 * sigma) continue
                val v = exp(-(d * d) / (2 * sigma * sigma))
                val nk = NavGrid.key(i + di, j + dj)
                if (v > (field[nk] ?: 0.0)) field[nk] = v
            }
        }
        fun score(dx: Double, dy: Double, dth: Double): Double {
            val c = cos(Math.toRadians(dth)); val s = sin(Math.toRadians(dth))
            var sum = 0.0
            for ((px, py) in points) {
                val wx = x + dx + px * c - py * s; val wy = y + dy + px * s + py * c
                sum += field[NavGrid.key(Math.floor(wx / cell).toInt(), Math.floor(wy / cell).toInt())] ?: 0.0
            }
            return sum
        }
        val base = score(0.0, 0.0, 0.0)
        // Recherche grossière puis fine.
        var best = Triple(0.0, 0.0, 0.0); var bestScore = base
        var step = cell; var turn = 1.0
        val coarse = generateSequence(-maxShift) { it + step }.takeWhile { it <= maxShift + 1e-9 }.toList()
        val turns = generateSequence(-maxTurnDeg) { it + turn }.takeWhile { it <= maxTurnDeg + 1e-9 }.toList()
        for (th in turns) for (dx in coarse) for (dy in coarse) {
            val sc = score(dx, dy, th)
            // Un petit avantage au « ne rien changer » : à score égal, on ne bouge pas.
            val pen = sc - 0.002 * points.size * (hypot(dx, dy) / maxShift + kotlin.math.abs(th) / maxTurnDeg)
            if (pen > bestScore) { bestScore = pen; best = Triple(dx, dy, th) }
        }
        step = cell / 4; turn = 0.25
        val (bx, by, bt) = best
        for (th in listOf(-1.0, -0.5, -0.25, 0.0, 0.25, 0.5, 1.0)) for (ox in -2..2) for (oy in -2..2) {
            val dx = bx + ox * step; val dy = by + oy * step; val t = bt + th
            val sc = score(dx, dy, t) - 0.002 * points.size * (hypot(dx, dy) / maxShift + kotlin.math.abs(t) / maxTurnDeg)
            if (sc > bestScore) { bestScore = sc; best = Triple(dx, dy, t) }
        }
        val fit = bestScore / points.size
        // Pas sûr : peu de points tombent sur la carte, ou le gain est négligeable.
        if (fit < 0.30 || bestScore < base * 1.05 + 0.5) return null
        return Fix(best.first, best.second, best.third, bestScore, base, points.size)
    }
}
