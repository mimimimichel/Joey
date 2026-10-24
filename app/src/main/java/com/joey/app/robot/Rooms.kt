package com.joey.app.robot

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Découpe la carte en pièces (méthode « par carte de distances », d'après Bormann et al., ICRA 2016, ipa_room_segmentation) :
 * 1. pour chaque carreau libre, la distance au plus proche obstacle ou inconnu ;
 * 2. les « cœurs » de pièce : les carreaux à plus de [doorHalf] de tout bord (une porte de 80 cm les sépare) ;
 * 3. chaque carreau libre rejoint le cœur le plus proche (vague depuis les cœurs) ;
 * 4. deux zones qui se touchent sur une longue frontière (plus de [mergeBorder]) ne sont pas séparées par une porte
 *    mais par un meuble : on les fusionne.
 * Sert à explorer pièce par pièce (finir la pièce où il est avant d'aller ailleurs) et à nommer les lieux. Pur : testé seul.
 */
object Rooms {
    data class Room(val id: Int, val cells: Int, val area: Double, val cx: Double, val cy: Double)
    data class Door(val a: Int, val b: Int, val x: Double, val y: Double, val width: Double)
    class Result(val rooms: List<Room>, val doors: List<Door>, private val label: Map<Long, Int>, private val cell: Double) {
        /** La pièce du point (x, y), ou null (pas un carreau libre connu). */
        fun roomAt(x: Double, y: Double): Int? = label[NavGrid.key(Math.floor(x / cell).toInt(), Math.floor(y / cell).toInt())]
    }

    /**
     * [free] : les carreaux libres (clés de [NavGrid.key]) ; [cell] : taille d'un carreau (m). Le reste (obstacles,
     * inconnu) fait bord.
     */
    fun segment(free: Set<Long>, cell: Double, doorHalf: Double = 0.40, minArea: Double = 0.8, mergeBorder: Double = 1.2): Result {
        if (free.isEmpty()) return Result(emptyList(), emptyList(), emptyMap(), cell)
        val n8 = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
        // 1. Distance au bord (en m), par vagues depuis les carreaux libres qui touchent un bord (approximation à 8 voisins).
        val dist = HashMap<Long, Double>(free.size * 2)
        val queue = java.util.PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        for (k in free) {
            val i = NavGrid.ki(k); val j = NavGrid.kj(k)
            if (n8.any { (a, b) -> NavGrid.key(i + a, j + b) !in free }) { dist[k] = cell / 2; queue.add(k to cell / 2) }
        }
        while (queue.isNotEmpty()) {
            val (k, d) = queue.poll()!!
            if (d > (dist[k] ?: Double.MAX_VALUE)) continue
            val i = NavGrid.ki(k); val j = NavGrid.kj(k)
            for ((a, b) in n8) {
                val nk = NavGrid.key(i + a, j + b)
                if (nk !in free) continue
                val nd = d + cell * if (a != 0 && b != 0) sqrt(2.0) else 1.0
                if (nd < (dist[nk] ?: Double.MAX_VALUE)) { dist[nk] = nd; queue.add(nk to nd) }
            }
        }
        // 2. Cœurs : composantes des carreaux loin des bords.
        val core = HashMap<Long, Int>()
        var next = 0
        val coreSize = ArrayList<Int>()
        for (k in free) {
            if (k in core || (dist[k] ?: 0.0) < doorHalf) continue
            val id = next++
            var count = 0
            val stack = ArrayDeque<Long>(); stack.add(k); core[k] = id
            while (stack.isNotEmpty()) {
                val c = stack.removeLast(); count++
                val i = NavGrid.ki(c); val j = NavGrid.kj(c)
                for ((a, b) in n8) {
                    val nk = NavGrid.key(i + a, j + b)
                    if (nk in free && nk !in core && (dist[nk] ?: 0.0) >= doorHalf) { core[nk] = id; stack.add(nk) }
                }
            }
            coreSize.add(count)
        }
        // Trop petits pour être une pièce (un recoin) : on les oublie, ils rejoindront une voisine.
        val minCells = (minArea * 0.25 / (cell * cell)).toInt() // le cœur est plus petit que la pièce
        core.entries.removeIf { coreSize[it.value] < minCells }
        if (core.isEmpty()) {
            // Pas de vraie pièce (couloir, petite carte) : une seule zone.
            val all = free.associateWith { 0 }
            return finish(all, cell, free, mergeBorder)
        }
        // 3. Chaque carreau libre rejoint le cœur le plus proche (vague à 8 voisins depuis les cœurs).
        val label = HashMap<Long, Int>(core)
        val wave = ArrayDeque(core.keys)
        while (wave.isNotEmpty()) {
            val c = wave.removeFirst()
            val id = label[c]!!
            val i = NavGrid.ki(c); val j = NavGrid.kj(c)
            for ((a, b) in n8) {
                val nk = NavGrid.key(i + a, j + b)
                if (nk in free && nk !in label) { label[nk] = id; wave.add(nk) }
            }
        }
        return finish(label, cell, free, mergeBorder)
    }

    private fun finish(label0: Map<Long, Int>, cell: Double, free: Set<Long>, mergeBorder: Double): Result {
        // 4. Fusion des zones séparées par une longue frontière (un meuble, pas une porte).
        val parent = HashMap<Int, Int>()
        fun find(x: Int): Int { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
        val n4 = listOf(1 to 0, 0 to 1)
        fun borders(lab: Map<Long, Int>): Map<Pair<Int, Int>, MutableList<Long>> {
            val m = HashMap<Pair<Int, Int>, MutableList<Long>>()
            for ((k, id) in lab) {
                val i = NavGrid.ki(k); val j = NavGrid.kj(k)
                for ((a, b) in n4) {
                    val o = lab[NavGrid.key(i + a, j + b)] ?: continue
                    val x = find(id); val y = find(o)
                    if (x != y) m.getOrPut(minOf(x, y) to maxOf(x, y)) { ArrayList() }.add(k)
                }
            }
            return m
        }
        var changed = true
        while (changed) {
            changed = false
            for ((pair, cells) in borders(label0)) {
                if (cells.size * cell >= mergeBorder) { parent[find(pair.second)] = find(pair.first); changed = true; break }
            }
        }
        // Renumérote 0, 1, 2… par taille décroissante.
        val byRoom = label0.entries.groupBy({ find(it.value) }, { it.key })
        val order = byRoom.entries.sortedByDescending { it.value.size }.mapIndexed { idx, e -> e.key to idx }.toMap()
        val label = label0.mapValues { order[find(it.value)]!! }
        val rooms = byRoom.entries.map { (root, cells) ->
            Room(order[root]!!, cells.size, cells.size * cell * cell,
                cells.map { (NavGrid.ki(it) + 0.5) * cell }.average(), cells.map { (NavGrid.kj(it) + 0.5) * cell }.average())
        }.sortedBy { it.id }
        // Portes : les frontières restantes (milieu et largeur).
        val doors = borders(label).map { (pair, cells) ->
            Door(pair.first, pair.second, cells.map { (NavGrid.ki(it) + 0.5) * cell }.average(),
                cells.map { (NavGrid.kj(it) + 0.5) * cell }.average(), cells.size * cell)
        }
        return Result(rooms, doors, label, cell)
    }

    /** Distance d'un point à une pièce (au plus proche de ses carreaux), pour dire « je suis dans le salon ». */
    fun nearestRoom(r: Result, x: Double, y: Double): Int? = r.roomAt(x, y) ?: r.rooms.minByOrNull { hypot(it.cx - x, it.cy - y) }?.id
}
