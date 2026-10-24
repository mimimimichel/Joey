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
 * La carte de la maison telle que Joey la connaît, en carreaux de 5 cm, dans le repère de ses roues (x devant au
 * départ, y à droite, en mètres) :
 * - là où il est passé : libre ;
 * - là où il a touché (pare-chocs), vu un obstacle de près (détecteurs de proximité) ou senti un vide : occupé ;
 * - le reste : inconnu.
 * Il s'en sert pour aller quelque part par le plus court chemin (A*, en gardant ses distances avec les obstacles)
 * et pour explorer méthodiquement : il va vers les « frontières », là où le connu touche l'inconnu.
 *
 * Marge autour des obstacles (24/10, salle à manger : un passage de 48 cm entre deux chaises était fermé) : interdit à
 * moins de son rayon + 3 cm, puis un coût qui décroît sur 20 cm (il passe au milieu des passages, sans les fermer).
 */
class NavGrid(val cell: Double = 0.05) {
    companion object {
        const val ROBOT_RADIUS = 0.17
        /** Marge dure en plus de son rayon (m). */
        const val HARD_MARGIN = 0.03
        /** Au-delà de la marge dure, distance (m) sur laquelle frôler coûte de plus en plus cher. */
        const val SOFT_MARGIN = 0.20
        private const val FREE = -1.0f
        private const val HIT = 2.5f
        private const val MAX = 6f
        private const val MIN = -6f
        private const val OCC = 1.5f   // au-dessus : occupé
        private const val CLEAR = -0.9f // en dessous : libre
        /** Au-delà : un obstacle sûr (plusieurs contacts) que la vision n'efface pas. */
        private const val SURE = 4.0f
        fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
        fun ki(k: Long) = (k shr 32).toInt()
        fun kj(k: Long) = k.toInt()
    }

    /** Indice (log) d'occupation par carreau : < 0 libre, > 0 occupé. */
    private val cells = HashMap<Long, Float>()
    /** Les vides (marches) sont retenus à part : jamais traversés, même s'il croit y être passé. */
    private val cliffs = HashSet<Long>()
    /**
     * Les carreaux où son centre est passé : son corps y tenait, donc il peut toujours y repasser, même si la marge
     * autour des meubles voisins dit le contraire (24/10 : glissé entre les chaises, il ne trouvait plus le chemin du retour).
     */
    private val trail = HashSet<Long>()
    /** Change à chaque modification (pour ne recalculer les distances aux obstacles que si besoin). */
    private var version = 0L

    val size: Int get() = synchronized(cells) { cells.size }

    fun idx(v: Double) = Math.floor(v / cell).toInt()
    fun center(i: Int) = (i + 0.5) * cell

    private fun set(k: Long, v: Float) { cells[k] = v.coerceIn(MIN, MAX); version++ }

    /** Il est passé là : le disque de son corps est libre. */
    fun markRobot(x: Double, y: Double) = synchronized(cells) {
        val r = ROBOT_RADIUS - cell / 2
        val n = (r / cell).roundToInt()
        val ci = idx(x); val cj = idx(y)
        if (key(ci, cj) !in cliffs) trail.add(key(ci, cj))
        for (di in -n..n) for (dj in -n..n) {
            if (hypot(di * cell, dj * cell) > r) continue
            val k = key(ci + di, cj + dj)
            if (k in cliffs) continue
            // Ce qu'il traverse est libre pour de bon (on efface un ancien obstacle : on l'avait peut-être poussé).
            set(k, (cells[k] ?: 0f) + FREE * 2)
        }
    }

    /**
     * Quelque chose devant lui, à [dist] m du centre, du côté [side] (-1 gauche, 0 devant, 1 droite), cap [headingDeg]
     * (+ = droite). Marque un petit arc (le pare-chocs est large).
     */
    fun markObstacle(x: Double, y: Double, headingDeg: Double, dist: Double = ROBOT_RADIUS + 0.06, side: Int = 0) = synchronized(cells) {
        val center = headingDeg + side * 45.0
        val keys = HashSet<Long>()
        var a = -25.0
        while (a <= 25.0) {
            val t = Math.toRadians(center + a)
            keys.add(key(idx(x + dist * cos(t)), idx(y + dist * sin(t))))
            a += 5.0
        }
        // Un contact, c'est sûr : occupé tout de suite, même là où il était passé avant (une chaise a bougé).
        for (k in keys) set(k, max((cells[k] ?: 0f) + HIT, OCC + 1f))
    }

    /**
     * La vision dit qu'il y a [dist] m de sol libre dans la direction [headingDeg] : on le note comme probablement libre
     * (moins sûr que s'il y était passé : un passage suffit à le confirmer, un contact à le contredire). Le rayon a une
     * largeur ([halfDeg] de chaque côté) : sinon, en carreaux de 5 cm, il restait de l'inconnu entre deux rayons voisins.
     * Un obstacle vu mais plus là (une chaise déplacée) s'efface peu à peu ; un obstacle touché plusieurs fois, non.
     */
    fun markFreeRay(x: Double, y: Double, headingDeg: Double, dist: Double, halfDeg: Double = 2.0) = synchronized(cells) {
        val t = Math.toRadians(headingDeg)
        val ux = cos(t); val uy = sin(t)
        val touched = HashSet<Long>()
        var d = ROBOT_RADIUS
        while (d < dist) {
            // Pas de largeur dans les 15 derniers centimètres : le rayon qui frappe un mur en biais débordait derrière lui.
            val w = if (d > dist - 0.15) 0.0 else d * kotlin.math.tan(Math.toRadians(halfDeg))
            var l = -w
            while (l <= w + 1e-9) {
                touched.add(key(idx(x + d * ux - l * uy), idx(y + d * uy + l * ux)))
                l += cell / 2
            }
            d += cell / 2
        }
        for (k in touched) {
            if (k in cliffs) continue
            val v = cells[k] ?: 0f
            when {
                v < OCC -> set(k, (v - 1.0f).coerceAtLeast(-1.5f).let { if (v < -1.5f) v else it })
                v < SURE -> set(k, v - 0.3f)
            }
        }
    }

    /**
     * La caméra voit le bord d'un obstacle à [dist] m dans la direction [headingDeg] : indice d'obstacle (moins sûr qu'un
     * contact : il faut le voir deux fois de suite pour qu'il compte, une ombre ne bloque pas le chemin).
     */
    fun markSeenObstacle(x: Double, y: Double, headingDeg: Double, dist: Double) = synchronized(cells) {
        val t = Math.toRadians(headingDeg)
        val k = key(idx(x + dist * cos(t)), idx(y + dist * sin(t)))
        if (k !in cliffs) set(k, ((cells[k] ?: 0f) + 1.0f).coerceAtMost(3.5f).let { if ((cells[k] ?: 0f) > 3.5f) cells[k]!! else it })
    }

    /** Un vide (bord de marche) devant lui : interdit pour toujours. */
    fun markCliff(x: Double, y: Double, headingDeg: Double) = synchronized(cells) {
        val t = Math.toRadians(headingDeg)
        // Un arc large et profond : le vide continue sûrement au-delà du bord senti (il en apprend la forme plus vite).
        // Sans trou entre les points, même en petits carreaux.
        var d = 0.16
        while (d <= 0.36) {
            var a = -45.0
            val step = Math.toDegrees(cell / 2 / d)
            while (a <= 45.0) {
                val ta = t + Math.toRadians(a)
                val k = key(idx(x + d * cos(ta)), idx(y + d * sin(ta)))
                cliffs.add(k); set(k, MAX)
                a += step
            }
            d += cell / 2
        }
    }

    fun value(i: Int, j: Int): Float? = synchronized(cells) { cells[key(i, j)] }
    fun occupied(i: Int, j: Int) = (value(i, j) ?: 0f) > OCC
    fun free(i: Int, j: Int) = (value(i, j) ?: 0f) < CLEAR

    // ---- distance aux obstacles (en carreaux), recalculée quand la carte change -----------------------------------
    /** Distance (carreaux) d'un centre de carreau au centre de l'obstacle le plus proche ; absent = plus loin que la marge. */
    private var clearCache: HashMap<Long, Float>? = null
    private var clearVersion = -1L
    private val hardCells get() = ((ROBOT_RADIUS + HARD_MARGIN) / cell + 0.5).toFloat()
    private val softCells get() = ((ROBOT_RADIUS + HARD_MARGIN + SOFT_MARGIN) / cell + 0.5).toFloat()

    private fun clearance(): HashMap<Long, Float> = synchronized(cells) {
        clearCache?.let { if (clearVersion == version) return it }
        val out = HashMap<Long, Float>()
        val r = Math.ceil(softCells.toDouble()).toInt()
        val offsets = ArrayList<Triple<Int, Int, Float>>()
        for (di in -r..r) for (dj in -r..r) {
            val d = hypot(di.toDouble(), dj.toDouble()).toFloat()
            if (d <= softCells) offsets.add(Triple(di, dj, d))
        }
        for ((k, v) in cells) {
            if (v <= OCC) continue
            val i = ki(k); val j = kj(k)
            for ((di, dj, d) in offsets) {
                val n = key(i + di, j + dj)
                val old = out[n]
                if (old == null || d < old) out[n] = d
            }
        }
        clearCache = out; clearVersion = version
        out
    }

    /** Un carreau trop près d'un obstacle pour que son corps y passe. */
    private fun blocked(c: HashMap<Long, Float>, i: Int, j: Int): Boolean = (c[key(i, j)] ?: Float.MAX_VALUE) < hardCells

    /** Coût de frôler (0 loin des obstacles, jusqu'à 1,5 au bord de la marge dure). */
    private fun nearCost(c: HashMap<Long, Float>, i: Int, j: Int): Double {
        val d = c[key(i, j)] ?: return 0.0
        if (d >= softCells) return 0.0
        return 1.5 * ((softCells - d) / (softCells - hardCells)).coerceIn(0f, 1f)
    }

    /** Un obstacle à moins de [m] mètres du carreau (i, j) : regarde seulement les voisins (rapide, appelé 20 fois/s). */
    private fun obstacleWithin(i: Int, j: Int, m: Double): Boolean = synchronized(cells) {
        val r = Math.ceil(m / cell).toInt()
        for (di in -r..r) for (dj in -r..r) {
            if (hypot(di * cell, dj * cell) > m) continue
            if ((cells[key(i + di, j + dj)] ?: 0f) > OCC) return true
        }
        false
    }

    /** Distance (m) au plus proche obstacle connu depuis ce point, si elle est dans la marge ; null sinon. */
    fun obstacleDistance(x: Double, y: Double): Double? = clearance()[key(idx(x), idx(y))]?.let { it * cell - cell / 2 }

    /**
     * Plus court chemin de (x0, y0) à (x1, y1), en évitant les obstacles (avec une marge pour son corps) ; l'inconnu
     * coûte plus cher que le connu (il préfère ce qu'il connaît, mais ose). Renvoie des points (m), ou null.
     */
    fun plan(x0: Double, y0: Double, x1: Double, y1: Double, maxCells: Int = 60_000): List<Pair<Double, Double>>? =
        plan(x0, y0, x1, y1, maxCells, useTrail = false)
            // Rien avec les marges habituelles : il repasse par où il est déjà passé (son corps y tenait).
            ?: plan(x0, y0, x1, y1, maxCells, useTrail = true)

    private fun plan(x0: Double, y0: Double, x1: Double, y1: Double, maxCells: Int, useTrail: Boolean): List<Pair<Double, Double>>? {
        val c = clearance()
        val si = idx(x0); val sj = idx(y0); val gi = idx(x1); val gj = idx(y1)
        val start = key(si, sj); val goal = key(gi, gj)
        val g = HashMap<Long, Double>(); val from = HashMap<Long, Long>()
        val open = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        fun h(i: Int, j: Int) = hypot((i - gi).toDouble(), (j - gj).toDouble())
        g[start] = 0.0; open.add(start to h(si, sj))
        var visited = 0
        // Le but lui-même peut être tout près d'un mur (une porte, un meuble) : on accepte d'arriver à côté (15 cm).
        val reach = 0.15 / cell
        var best = start; var bestH = h(si, sj)
        val escape = hardCells + 1
        while (open.isNotEmpty() && visited < maxCells) {
            val (k, _) = open.poll()!!
            visited++
            val i = ki(k); val j = kj(k)
            val hh = h(i, j)
            if (hh < bestH) { bestH = hh; best = k }
            if (k == goal || hh < reach) { best = k; bestH = 0.0; break }
            for (di in -1..1) for (dj in -1..1) {
                if (di == 0 && dj == 0) continue
                val ni = i + di; val nj = j + dj
                val nk = key(ni, nj)
                if (nk in cliffs) continue
                // Le départ peut être « bloqué » (il est collé à un mur) : on le laisse en sortir. Là où il est déjà passé,
                // il peut repasser (sauf si un contact y a mis un obstacle depuis).
                val walked = useTrail && synchronized(cells) { nk in trail } && !occupied(ni, nj)
                if (!walked && blocked(c, ni, nj) && hypot((ni - si).toDouble(), (nj - sj).toDouble()) > escape) continue
                val step = if (di != 0 && dj != 0) sqrt(2.0) else 1.0
                val unknownCost = if (value(ni, nj) == null) 0.6 else 0.0
                // Près d'un obstacle : de plus en plus cher (il passe au milieu des portes plutôt qu'en frôlant).
                val ng = (g[k] ?: 0.0) + step * (1.0 + unknownCost + nearCost(c, ni, nj))
                if (ng < (g[nk] ?: Double.MAX_VALUE)) {
                    g[nk] = ng; from[nk] = k
                    open.add(nk to ng + h(ni, nj))
                }
            }
        }
        if (bestH * cell > 0.3) return null // rien de mieux qu'un cul-de-sac loin du but
        val path = ArrayList<Pair<Double, Double>>()
        var cur: Long? = best
        while (cur != null) { path.add(center(ki(cur)) to center(kj(cur))); cur = from[cur] }
        path.reverse()
        return if (useTrail) path else simplify(path)
    }

    /**
     * Garde les virages seulement (des segments droits) : moins d'arrêts. Un raccourci ne passe jamais plus près des
     * obstacles que le chemin qu'il remplace (sinon les lignes droites frôlaient les coins de meubles à 3 cm).
     */
    fun simplify(p: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (p.size <= 2) return p
        val c = clearance()
        fun clr(q: Pair<Double, Double>) = c[key(idx(q.first), idx(q.second))] ?: Float.MAX_VALUE
        val out = ArrayList<Pair<Double, Double>>()
        out.add(p.first())
        var anchor = 0
        var pathMin = Float.MAX_VALUE // distance aux obstacles la plus faible du chemin depuis l'ancre
        for (n in 1 until p.size - 1) {
            val a = p[anchor]; val b = p[n + 1]
            pathMin = minOf(pathMin, clr(p[n]), clr(b))
            val need = minOf(pathMin, softCells) // pas plus près que le chemin, et au moins aussi loin qu'il le peut
            if (!lineClear(a.first, a.second, b.first, b.second, need) || hypot(b.first - a.first, b.second - a.second) > 1.5) {
                out.add(p[n]); anchor = n; pathMin = minOf(clr(p[n]), clr(b))
            }
        }
        out.add(p.last())
        return out
    }

    /**
     * Une ligne droite sans obstacle (avec la marge du corps). [minCells] : distance aux obstacles (carreaux) exigée tout
     * du long, au-delà du départ ; par défaut la marge dure.
     */
    fun lineClear(x0: Double, y0: Double, x1: Double, y1: Double, minCells: Float = hardCells): Boolean {
        val c = clearance()
        val need = maxOf(minCells, hardCells)
        val d = hypot(x1 - x0, y1 - y0)
        val n = max(1, (d / (cell / 2)).toInt())
        val skip = (hardCells + 1).toInt() * 2 // le départ peut être collé à un obstacle
        for (s in 0..n) {
            val t = s.toDouble() / n
            val i = idx(x0 + (x1 - x0) * t); val j = idx(y0 + (y1 - y0) * t)
            if (key(i, j) in cliffs || (s > skip && (c[key(i, j)] ?: Float.MAX_VALUE) < need)) return false
        }
        return true
    }

    /**
     * Les frontières : carreaux libres qui touchent l'inconnu, regroupées ; renvoie le centre de chaque groupe d'au
     * moins [minSize] carreaux, du plus proche au plus loin de (x, y).
     */
    fun frontiers(x: Double, y: Double, minSize: Int = 3): List<Pair<Double, Double>> {
        // [minSize] est en carreaux de 10 cm (la taille d'origine) : même longueur de frontière, quelle que soit la taille.
        val minCells = Math.ceil(minSize * 0.1 / cell).toInt()
        val front = HashSet<Long>()
        val c = clearance()
        synchronized(cells) {
            for ((k, v) in cells) {
                if (v >= CLEAR) continue
                // Collé à un obstacle : son corps ne peut pas s'y tenir (souvent un reste de rayon au pied d'un mur).
                if ((c[k] ?: Float.MAX_VALUE) < hardCells) continue
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
            if (g.size >= minCells) groups.add(g)
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
            val near = obstacleWithin(i, j, 0.12)
            if (key(i, j) in cliffs || near) return if (d < 0.4) 0.3 else 0.5
            if (value(i, j) == null || !free(i, j)) unknown++
        }
        return when (unknown) { 0 -> 1.0; 1 -> 0.8; else -> 0.6 }
    }

    /** Les carreaux libres connus (copie), pour découper la carte en pièces ([Rooms]). */
    fun freeKeys(): Set<Long> = synchronized(cells) { cells.entries.filter { it.value < CLEAR && it.key !in cliffs }.mapTo(HashSet()) { it.key } }

    /** Part de la surface connue (m²) : pour dire « j'ai exploré 18 m² ». */
    fun knownArea(): Double = synchronized(cells) { cells.values.count { it < CLEAR } * cell * cell }

    fun clear() = synchronized(cells) { cells.clear(); cliffs.clear(); trail.clear(); version++ }

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
            // Une carte gardée en carreaux plus grands (10 cm avant la 0.67) : chaque carreau devient 2 × 2 petits.
            val ratio = (o.optDouble("cell") / cell).roundToInt()
            if (ratio < 1 || abs(o.optDouble("cell") - ratio * cell) > 1e-6) return false
            fun put(i: Int, j: Int, v: Float?) {
                for (a in 0 until ratio) for (b in 0 until ratio) {
                    val k = key(i * ratio + a, j * ratio + b)
                    if (v == null) { cliffs.add(k); cells[k] = MAX } else cells[k] = v
                }
            }
            val a = o.getJSONArray("c")
            for (n in 0 until a.length()) { val e = a.getJSONArray(n); put(e.getInt(0), e.getInt(1), e.getDouble(2).toFloat()) }
            val v = o.optJSONArray("v")
            if (v != null) for (n in 0 until v.length()) { val e = v.getJSONArray(n); put(e.getInt(0), e.getInt(1), null) }
            version++
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
