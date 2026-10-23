package com.joey.app.robot

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La carte de la maison telle que Joey la connaît, en carreaux de 10 cm, dans le repère de ses roues (x devant au
 * départ, y à droite, en mètres) :
 * - là où il est passé : libre ;
 * - là où il a touché (pare-chocs), vu un obstacle de près (détecteurs de proximité) ou senti un vide : occupé ;
 * - le reste : inconnu.
 * Il s'en sert pour aller quelque part par le plus court chemin (A*, en gardant ses distances avec les obstacles)
 * et pour explorer méthodiquement : il va vers les « frontières », là où le connu touche l'inconnu.
 */
class NavGrid(val cell: Double = 0.1) {
    companion object {
        const val ROBOT_RADIUS = 0.17
        private const val FREE = -1.0f
        private const val HIT = 2.5f
        private const val MAX = 6f
        private const val MIN = -6f
        private const val OCC = 1.5f   // au-dessus : occupé
        private const val CLEAR = -0.9f // en dessous : libre
        fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
        fun ki(k: Long) = (k shr 32).toInt()
        fun kj(k: Long) = k.toInt()
    }

    /** Indice (log) d'occupation par carreau : < 0 libre, > 0 occupé. */
    private val cells = HashMap<Long, Float>()
    /** Les vides (marches) sont retenus à part : jamais traversés, même s'il croit y être passé. */
    private val cliffs = HashSet<Long>()

    val size: Int get() = synchronized(cells) { cells.size }

    fun idx(v: Double) = Math.floor(v / cell).toInt()
    fun center(i: Int) = (i + 0.5) * cell

    private fun add(i: Int, j: Int, d: Float) {
        val k = key(i, j)
        cells[k] = ((cells[k] ?: 0f) + d).coerceIn(MIN, MAX)
    }

    /** Il est passé là : le disque de son corps est libre. */
    fun markRobot(x: Double, y: Double) = synchronized(cells) {
        val r = ROBOT_RADIUS - cell / 2
        val n = (r / cell).roundToInt()
        val ci = idx(x); val cj = idx(y)
        for (di in -n..n) for (dj in -n..n) {
            if (hypot(di * cell, dj * cell) > r) continue
            val k = key(ci + di, cj + dj)
            if (k in cliffs) continue
            // Ce qu'il traverse est libre pour de bon (on efface un ancien obstacle : on l'avait peut-être poussé).
            cells[k] = ((cells[k] ?: 0f) + FREE * 2).coerceIn(MIN, MAX)
        }
    }

    /**
     * Quelque chose devant lui, à [dist] m du centre, du côté [side] (-1 gauche, 0 devant, 1 droite), cap [headingDeg]
     * (+ = droite). Marque un petit arc (le pare-chocs est large).
     */
    fun markObstacle(x: Double, y: Double, headingDeg: Double, dist: Double = ROBOT_RADIUS + 0.06, side: Int = 0) = synchronized(cells) {
        val center = headingDeg + side * 45.0
        for (a in listOf(-25.0, 0.0, 25.0)) {
            val t = Math.toRadians(center + a)
            // Un contact, c'est sûr : occupé tout de suite, même là où il était passé avant (une chaise a bougé).
            val k = key(idx(x + dist * cos(t)), idx(y + dist * sin(t)))
            cells[k] = max((cells[k] ?: 0f) + HIT, OCC + 1f).coerceIn(MIN, MAX)
        }
    }

    /**
     * La vision dit qu'il y a [dist] m de sol libre dans la direction [headingDeg] : on le note comme probablement libre
     * (moins sûr que s'il y était passé : un passage suffit à le confirmer, un contact à le contredire).
     */
    fun markFreeRay(x: Double, y: Double, headingDeg: Double, dist: Double) = synchronized(cells) {
        val t = Math.toRadians(headingDeg)
        var d = ROBOT_RADIUS
        while (d < dist) {
            val k = key(idx(x + d * cos(t)), idx(y + d * sin(t)))
            if (k !in cliffs && (cells[k] ?: 0f) < OCC) cells[k] = ((cells[k] ?: 0f) - 1.0f).coerceAtLeast(-1.5f)
            d += cell / 2
        }
    }

    /**
     * La caméra voit le bord d'un obstacle à [dist] m dans la direction [headingDeg] : indice d'obstacle (moins sûr qu'un
     * contact : il faut le voir deux fois de suite pour qu'il compte, une ombre ne bloque pas le chemin).
     */
    fun markSeenObstacle(x: Double, y: Double, headingDeg: Double, dist: Double) = synchronized(cells) {
        val t = Math.toRadians(headingDeg)
        val k = key(idx(x + dist * cos(t)), idx(y + dist * sin(t)))
        if (k !in cliffs) cells[k] = ((cells[k] ?: 0f) + 1.0f).coerceAtMost(3.5f)
    }

    /** Un vide (bord de marche) devant lui : interdit pour toujours. */
    fun markCliff(x: Double, y: Double, headingDeg: Double) = synchronized(cells) {
        val t = Math.toRadians(headingDeg)
        for (d in listOf(0.18, 0.25)) for (a in listOf(-30.0, 0.0, 30.0)) {
            val ta = t + Math.toRadians(a)
            val k = key(idx(x + d * cos(ta)), idx(y + d * sin(ta)))
            cliffs.add(k); cells[k] = MAX
        }
    }

    fun value(i: Int, j: Int): Float? = synchronized(cells) { cells[key(i, j)] }
    fun occupied(i: Int, j: Int) = (value(i, j) ?: 0f) > OCC
    fun free(i: Int, j: Int) = (value(i, j) ?: 0f) < CLEAR

    /** Un carreau trop près d'un obstacle pour que son corps y passe. */
    private fun blocked(i: Int, j: Int, inflate: Int): Boolean {
        for (di in -inflate..inflate) for (dj in -inflate..inflate) {
            if (di * di + dj * dj > inflate * inflate) continue
            if (occupied(i + di, j + dj)) return true
        }
        return false
    }

    /**
     * Plus court chemin de (x0, y0) à (x1, y1), en évitant les obstacles (avec une marge pour son corps) ; l'inconnu
     * coûte plus cher que le connu (il préfère ce qu'il connaît, mais ose). Renvoie des points (m), ou null.
     */
    fun plan(x0: Double, y0: Double, x1: Double, y1: Double, maxCells: Int = 60_000): List<Pair<Double, Double>>? {
        val inflate = (ROBOT_RADIUS / cell).toInt()
        val si = idx(x0); val sj = idx(y0); val gi = idx(x1); val gj = idx(y1)
        val start = key(si, sj); val goal = key(gi, gj)
        val g = HashMap<Long, Double>(); val from = HashMap<Long, Long>()
        val open = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        fun h(i: Int, j: Int) = hypot((i - gi).toDouble(), (j - gj).toDouble())
        g[start] = 0.0; open.add(start to h(si, sj))
        var visited = 0
        // Le but lui-même peut être tout près d'un mur (une porte, un meuble) : on accepte d'arriver à côté.
        var best = start; var bestH = h(si, sj)
        while (open.isNotEmpty() && visited < maxCells) {
            val (k, _) = open.poll()!!
            visited++
            val i = ki(k); val j = kj(k)
            val hh = h(i, j)
            if (hh < bestH) { bestH = hh; best = k }
            if (k == goal || hh < 1.5) { best = k; bestH = 0.0; break }
            for (di in -1..1) for (dj in -1..1) {
                if (di == 0 && dj == 0) continue
                val ni = i + di; val nj = j + dj
                val nk = key(ni, nj)
                if (nk in cliffs) continue
                // Le départ peut être « bloqué » (il est collé à un mur) : on le laisse en sortir.
                if (blocked(ni, nj, inflate) && hypot((ni - si).toDouble(), (nj - sj).toDouble()) > inflate) continue
                val step = if (di != 0 && dj != 0) sqrt(2.0) else 1.0
                val unknownCost = if (value(ni, nj) == null) 0.6 else 0.0
                // Près d'un obstacle : un peu plus cher (il passe au milieu des portes plutôt qu'en frôlant).
                val wallCost = if (blocked(ni, nj, inflate + 1)) 0.8 else 0.0
                val ng = (g[k] ?: 0.0) + step + unknownCost + wallCost
                if (ng < (g[nk] ?: Double.MAX_VALUE)) {
                    g[nk] = ng; from[nk] = k
                    open.add(nk to ng + h(ni, nj))
                }
            }
        }
        if (bestH > 3.0) return null // rien de mieux qu'un cul-de-sac loin du but
        val path = ArrayList<Pair<Double, Double>>()
        var c: Long? = best
        while (c != null) { path.add(center(ki(c)) to center(kj(c))); c = from[c] }
        path.reverse()
        return simplify(path)
    }

    /** Garde les virages seulement (des segments droits) : moins d'arrêts. */
    fun simplify(p: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (p.size <= 2) return p
        val out = ArrayList<Pair<Double, Double>>()
        out.add(p.first())
        var anchor = p.first()
        for (n in 1 until p.size - 1) {
            val a = anchor; val b = p[n + 1]
            // Le segment anchor -> point suivant reste-t-il dans un couloir libre ? Sinon on garde ce point.
            if (!lineClear(a.first, a.second, b.first, b.second) || hypot(b.first - a.first, b.second - a.second) > 1.5) {
                out.add(p[n]); anchor = p[n]
            }
        }
        out.add(p.last())
        return out
    }

    /** Une ligne droite sans obstacle (avec la marge du corps). */
    fun lineClear(x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        val inflate = (ROBOT_RADIUS / cell).toInt()
        val d = hypot(x1 - x0, y1 - y0)
        val n = max(1, (d / (cell / 2)).toInt())
        for (s in 0..n) {
            val t = s.toDouble() / n
            val i = idx(x0 + (x1 - x0) * t); val j = idx(y0 + (y1 - y0) * t)
            if (key(i, j) in cliffs || (s > 2 && blocked(i, j, inflate))) return false
        }
        return true
    }

    /**
     * Les frontières : carreaux libres qui touchent l'inconnu, regroupées ; renvoie le centre de chaque groupe d'au
     * moins [minSize] carreaux, du plus proche au plus loin de (x, y).
     */
    fun frontiers(x: Double, y: Double, minSize: Int = 3): List<Pair<Double, Double>> {
        val front = HashSet<Long>()
        synchronized(cells) {
            for ((k, v) in cells) {
                if (v >= CLEAR) continue
                val i = ki(k); val j = kj(k)
                if (listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (a, b) -> cells[key(i + a, j + b)] == null }) front.add(k)
            }
        }
        val groups = ArrayList<List<Long>>()
        val seen = HashSet<Long>()
        for (k in front) {
            if (k in seen) continue
            val g = ArrayList<Long>(); val stack = ArrayDeque<Long>(); stack.add(k); seen.add(k)
            while (stack.isNotEmpty()) {
                val c = stack.removeLast(); g.add(c)
                for (a in -1..1) for (b in -1..1) {
                    val n = key(ki(c) + a, kj(c) + b)
                    if (n in front && seen.add(n)) stack.add(n)
                }
            }
            if (g.size >= minSize) groups.add(g)
        }
        return groups.map { g -> g.map { center(ki(it)) }.average() to g.map { center(kj(it)) }.average() }
            .sortedBy { hypot(it.first - x, it.second - y) }
    }

    /**
     * À quelle allure rouler ici (0..1 de la vitesse max) : pleine vitesse si les 70 cm devant lui sont connus et libres,
     * plus prudent dans l'inconnu, lentement si un obstacle connu est tout près sur sa route.
     */
    fun speedFactor(x: Double, y: Double, headingDeg: Double): Double {
        val t = Math.toRadians(headingDeg)
        var unknown = 0
        for (d in listOf(0.25, 0.45, 0.7)) {
            val i = idx(x + d * cos(t)); val j = idx(y + d * sin(t))
            if (key(i, j) in cliffs || blocked(i, j, 1)) return if (d < 0.4) 0.3 else 0.5
            if (value(i, j) == null || !free(i, j)) unknown++
        }
        return when (unknown) { 0 -> 1.0; 1 -> 0.8; else -> 0.6 }
    }

    /** Part de la surface connue (m²) : pour dire « j'ai exploré 18 m² ». */
    fun knownArea(): Double = synchronized(cells) { cells.values.count { it < CLEAR } * cell * cell }

    fun clear() = synchronized(cells) { cells.clear(); cliffs.clear() }

    /** Les carreaux connus autour de (x, y), à [radius] carreaux au plus : (i, j, valeur), et les vides. Pour la tablette. */
    fun around(x: Double, y: Double, radius: Int = 50): Pair<List<Triple<Int, Int, Float>>, List<Pair<Int, Int>>> = synchronized(cells) {
        val ci = idx(x); val cj = idx(y)
        val c = cells.entries.filter { abs(ki(it.key) - ci) <= radius && abs(kj(it.key) - cj) <= radius && abs(it.value) > 0.8f }
            .map { Triple(ki(it.key), kj(it.key), it.value) }
        val v = cliffs.filter { abs(ki(it) - ci) <= radius && abs(kj(it) - cj) <= radius }.map { ki(it) to kj(it) }
        c to v
    }

    // ---- sauvegarde (seulement quand le repère est sa base : sinon d'une fois sur l'autre les repères diffèrent) ----
    fun save(f: File) = synchronized(cells) {
        val o = JSONObject().put("cell", cell)
        o.put("c", JSONArray().also { a -> cells.forEach { (k, v) -> if (abs(v) > 0.5f) a.put(JSONArray().put(ki(k)).put(kj(k)).put(v.toDouble())) } })
        o.put("v", JSONArray().also { a -> cliffs.forEach { a.put(JSONArray().put(ki(it)).put(kj(it))) } })
        runCatching { f.writeText(o.toString()) }
    }

    fun load(f: File): Boolean = synchronized(cells) {
        if (!f.exists()) return false
        runCatching {
            val o = JSONObject(f.readText())
            if (o.optDouble("cell") != cell) return false
            val a = o.getJSONArray("c")
            for (n in 0 until a.length()) { val e = a.getJSONArray(n); cells[key(e.getInt(0), e.getInt(1))] = e.getDouble(2).toFloat() }
            val v = o.optJSONArray("v")
            if (v != null) for (n in 0 until v.length()) { val e = v.getJSONArray(n); cliffs.add(key(e.getInt(0), e.getInt(1))) }
            true
        }.getOrDefault(false)
    }

    /** Petit dessin texte (pour le journal) : # occupé, . libre, espace inconnu, R le robot. */
    fun ascii(x: Double, y: Double, half: Int = 20): String {
        val ci = idx(x); val cj = idx(y)
        val sb = StringBuilder()
        for (i in ci + half downTo ci - half) {
            for (j in cj - half..cj + half) sb.append(when {
                i == ci && j == cj -> 'R'
                key(i, j) in cliffs -> '!'
                occupied(i, j) -> '#'
                free(i, j) -> '.'
                else -> ' '
            })
            sb.append('\n')
        }
        return sb.toString()
    }
}
