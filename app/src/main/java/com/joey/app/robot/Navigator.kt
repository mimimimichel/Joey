package com.joey.app.robot

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * La navigation de Joey, à part du reste (pour la tester dans un monde simulé) :
 * - tenir la carte à jour : là où il passe (libre), ce qu'il touche ou sent (pare-chocs, vides, détecteurs de
 *   proximité), ce que voit la caméra (sol libre, bords d'obstacles) ;
 * - aller quelque part : plus court chemin sur la carte, suivi d'un mouvement continu, et s'il rencontre quelque chose
 *   (noté sur la carte), il se dégage s'il l'a touché, puis recalcule.
 */
class Navigator(val robot: RoombaRobot, val grid: NavGrid, private val log: (String) -> Unit = {}) {
    private var lastX = Double.NaN
    private var lastY = Double.NaN

    /** Le pare-chocs regarde vers l'arrière de Joey s'il est monté à l'envers. */
    private fun bumperHeading() = robot.heading + if (robot.reversed) 180.0 else 0.0

    /** Souvent (10 fois par seconde) : là où il passe est libre. */
    fun tick() {
        if (!robot.linkOk) return
        val x = robot.x; val y = robot.y
        if (lastX.isNaN() || hypot(x - lastX, y - lastY) > 0.05) { grid.markRobot(x, y); lastX = x; lastY = y }
    }

    /** L'origine a changé (recalage sur la base) : on repart de là. */
    fun resetTrack() { lastX = Double.NaN }

    /** Un contact signalé par le Roomba (voir [RoombaRobot.onContact]). */
    fun contact(kind: String, side: Int) {
        val h = bumperHeading()
        when (kind) {
            "vide" -> { grid.markCliff(robot.x, robot.y, h); log("🗺 Vide noté sur ma carte") }
            "mur virtuel" -> { grid.markCliff(robot.x, robot.y, h); log("🗺 Mur virtuel : je ne le franchirai pas") }
            "proche" -> grid.markObstacle(robot.x, robot.y, h, 0.30, side)
            else -> grid.markObstacle(robot.x, robot.y, h, side = side)
        }
    }

    /** Un obstacle senti par un détecteur de proximité (direction par rapport au pare-chocs, distance au pare-chocs). */
    fun proximity(angle: Double, dist: Double) =
        grid.markObstacle(robot.x, robot.y, bumperHeading() + angle, NavGrid.ROBOT_RADIUS + dist)

    /**
     * Ce que la caméra voit du sol : pour chaque direction [rays] (degrés par rapport à [baseHeading], distance de sol
     * libre en m, l'infini = tout le sol vu est libre) : sol libre jusque-là, bord d'obstacle (à confirmer) au bout.
     */
    fun vision(baseHeading: Double, rays: List<Pair<Double, Double>>) {
        val x = robot.x; val y = robot.y
        var prev: Pair<Double, Double>? = null // bout du rayon précédent (x, y), s'il touchait un obstacle
        for ((bearing, dist) in rays.sortedBy { it.first }) {
            val b = baseHeading + bearing
            val d = if (dist.isInfinite()) 2.5 else dist.coerceAtMost(2.5)
            if (d > 0.3) grid.markFreeRay(x, y, b, d - 0.08)
            if (!dist.isInfinite() && dist < 2.0) {
                val t = Math.toRadians(b)
                val px = x + (dist + 0.05) * kotlin.math.cos(t); val py = y + (dist + 0.05) * kotlin.math.sin(t)
                grid.markSeenObstacle(x, y, b, dist + 0.05)
                // Deux directions voisines qui touchent un obstacle à peu près à la même distance : c'est le même bord
                // (le devant d'un meuble) : on le trace en entier, sinon le chemin passait entre les points.
                prev?.let { (qx, qy) ->
                    val gap = hypot(px - qx, py - qy)
                    if (gap in 0.05..0.45) {
                        val n = (gap / (grid.cell / 2)).toInt()
                        for (k in 1 until n) {
                            val ex = qx + (px - qx) * k / n; val ey = qy + (py - qy) * k / n
                            grid.markSeenObstacle(x, y, Math.toDegrees(atan2(ey - y, ex - x)), hypot(ex - x, ey - y))
                        }
                    }
                }
                prev = px to py
            } else prev = null
        }
    }

    /**
     * Va au point (tx, ty) de la carte. [beforeLeg] est appelé avant chaque essai (pour baisser la tête vers le sol…).
     * Renvoie « arrivé », « presque arrivé », « arrêté : … » (sécurité) ou pourquoi il n'y arrive pas.
     */
    fun navigateTo(tx: Double, ty: Double, tries: Int = 8, budget: Double = 25.0, beforeLeg: (Int) -> Unit = {}): String {
        val r = robot
        val startM = r.x to r.y
        var travelled = 0.0
        var unsticks = 0
        var lastStop: Pair<Double, Double>? = null
        var dodge = 0
        var cliffs = 0
        var attempt = 0
        var replans = 0
        while (attempt < tries) {
            if (hypot(tx - r.x, ty - r.y) < 0.3) return "arrivé"
            val path = grid.plan(r.x, r.y, tx, ty) ?: return "pas de chemin sur ma carte (tout est bouché par là)"
            beforeLeg(attempt)
            val before = r.x to r.y
            val leg = path.drop(1).ifEmpty { listOf(tx to ty) }
            // Recalcul en route : la suite du chemin (le segment visé et le suivant) reste-t-elle libre sur la carte ? Au
            // plus 25 recalculs par trajet (une carte qui hésite ne le fait pas tourner en rond).
            val check: ((Double, Double, Int) -> Boolean)? = if (replans < 25) { x, y, seg ->
                val a = leg.getOrNull(seg); val b = leg.getOrNull(seg + 1)
                (a == null || grid.lineClear(x, y, a.first, a.second)) && (a == null || b == null || grid.lineClear(a.first, a.second, b.first, b.second))
            } else null
            val res = r.followPath(leg, r.maxSpeed, check) { x, y, h ->
                grid.speedFactor(x, y, h + if (r.reversed) 180.0 else 0.0).let { if (r.reversed) minOf(it, 0.6) else it }
            }
            travelled += hypot(r.x - before.first, r.y - before.second)
            log("🧭 trajet ${attempt + 1} : ${path.size - 1} segment(s) vers (%.1f ; %.1f) -> $res".format(tx, ty))
            if (res.startsWith("chemin bouché")) {
                replans++
                if (travelled > budget) return "trajet trop long (%.1f m), je m'arrête".format(travelled)
                continue // pas un échec : il recalcule avec ce qu'il vient de voir
            }
            attempt++
            when {
                // Un vide (marche, escalier) : noté sur la carte comme infranchissable ; il recule, se tourne et cherche un
                // autre chemin (comme un aspirateur robot), et n'abandonne qu'après plusieurs vides.
                res.contains("vide") && !res.contains("roue soulevée") && cliffs < 8 -> {
                    cliffs++
                    log("🕳️ Vide devant : je recule et je contourne")
                    r.drive(if (r.reversed) 0.2 else -0.2)
                    r.turn(if (cliffs % 2 == 1) 60.0 else -120.0)
                }
                res.contains("liaison") || res.contains("vide") || res.startsWith("je ne roule pas") || res == "interrompu" ||
                    res.contains("roue soulevée") || res.startsWith("arrêté") -> return if (res.startsWith("arrêté")) res else "arrêté : $res"
                res.startsWith("obstacle vu") -> {
                    log("👁️ $res") // vu à temps, noté sur la carte : on recalcule le chemin
                    // Arrêté deux fois au même endroit : il recule un peu et pivote avant de recalculer (sinon impasse).
                    val here = r.x to r.y
                    val again = lastStop?.let { hypot(it.first - here.first, it.second - here.second) < 0.06 } == true
                    lastStop = here
                    if (again) {
                        r.drive(-0.12)
                        r.turn(if (dodge++ % 2 == 0) 35.0 else -70.0)
                    }
                }
                res.contains("obstacle") || res.contains("bloqué") -> log("🛞 ${r.unstick(unsticks++ % 3)}")
            }
            if (hypot(tx - r.x, ty - r.y) < 0.35) return "arrivé"
            if (travelled > budget) return "trajet trop long (%.1f m), je m'arrête".format(travelled)
        }
        return if (hypot(tx - r.x, ty - r.y) < 0.6) "presque arrivé" else
            "pas réussi à y arriver (trop d'obstacles ; %.1f m parcourus, à %.1f m du départ)".format(travelled,
                hypot(startM.first - r.x, startM.second - r.y))
    }

    /** Frontières où il n'a pas réussi à aller (il ne s'y acharne pas). */
    private val failedFrontiers = ArrayList<Pair<Double, Double>>()

    /**
     * Un pas d'exploration méthodique : il va vers la frontière (le bord entre ce qu'il connaît et l'inconnu) la plus
     * proche vers laquelle un chemin existe. Renvoie le résultat du trajet, ou null s'il n'y a plus de frontière :
     * tout ce qui est accessible est connu.
     */
    // ---- pièces ---------------------------------------------------------------------------------------------------
    @Volatile private var roomsCache: Rooms.Result? = null
    @Volatile private var roomsAt = 0L

    /** La carte découpée en pièces (recalculée au plus toutes les 30 s). */
    fun rooms(): Rooms.Result {
        val now = System.currentTimeMillis()
        roomsCache?.let { if (now - roomsAt < 30_000) return it }
        val r = Rooms.segment(grid.freeKeys(), grid.cell)
        roomsCache = r; roomsAt = now
        return r
    }

    fun frontierStep(budget: Double = 3.0, beforeLeg: (Int) -> Unit = {}): String? {
        val x = robot.x; val y = robot.y
        // Il finit la pièce où il est avant d'aller ailleurs (sinon il faisait des allers-retours d'une pièce à l'autre).
        val rooms = runCatching { rooms() }.getOrNull()
        val here = rooms?.let { Rooms.nearestRoom(it, x, y) }
        val f = grid.frontiers(x, y, minSize = 4)
            .filter { (fx, fy) -> hypot(fx - x, fy - y) > 0.5 && failedFrontiers.none { hypot(it.first - fx, it.second - fy) < 0.5 } }
            .take(8)
            .sortedBy { (fx, fy) -> if (here != null && rooms.roomAt(fx, fy) == here) 0 else 1 }
            .firstOrNull { grid.plan(x, y, it.first, it.second) != null } ?: return null
        log("🧭 vers l'inconnu : (%.1f ; %.1f)".format(f.first, f.second))
        val res = navigateTo(f.first, f.second, tries = 3, budget = budget, beforeLeg = beforeLeg)
        if (!res.startsWith("arrivé") && !res.startsWith("presque")) failedFrontiers.add(f)
        return res
    }

    /** Il reste des frontières (pas encore ratées) à plus de 50 cm : la maison n'est pas toute connue. */
    fun frontiersLeft(): Boolean {
        val x = robot.x; val y = robot.y
        return grid.frontiers(x, y, minSize = 4)
            .any { (fx, fy) -> hypot(fx - x, fy - y) > 0.5 && failedFrontiers.none { hypot(it.first - fx, it.second - fy) < 0.5 } }
    }

    /**
     * Le point où s'arrêter avant d'accoster : 50 cm devant la base, du côté où il en sort. La carte part de la base
     * (origine, cap 0 = là où regarde Joey) ; le Roomba accoste en avançant, donc il quitte sa base en reculant : du côté
     * des x négatifs si Joey regarde vers l'avant du Roomba, positifs s'il est monté à l'envers. (Avant la 0.70 : toujours
     * +0,5 m, c'est-à-dire derrière la base, contre le mur, quand Joey regarde vers l'avant du Roomba.)
     */
    fun dockApproach(): Pair<Double, Double> = (if (robot.reversed) 0.5 else -0.5) to 0.0

    /** Cap (degrés, + = droite) pour regarder le point (x, y) depuis là où il est. */
    fun bearingTo(x: Double, y: Double) = Math.toDegrees(atan2(y - robot.y, x - robot.x))

    /** Distance au point. */
    fun distanceTo(x: Double, y: Double) = hypot(x - robot.x, y - robot.y)
}
