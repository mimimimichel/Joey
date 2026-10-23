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
        for ((bearing, dist) in rays) {
            val b = baseHeading + bearing
            val d = if (dist.isInfinite()) 2.5 else dist.coerceAtMost(2.5)
            if (d > 0.3) grid.markFreeRay(x, y, b, d - 0.08)
            if (!dist.isInfinite() && dist < 2.0) grid.markSeenObstacle(x, y, b, dist + 0.05)
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
        for (attempt in 0 until tries) {
            if (hypot(tx - r.x, ty - r.y) < 0.3) return "arrivé"
            val path = grid.plan(r.x, r.y, tx, ty) ?: return "pas de chemin sur ma carte (tout est bouché par là)"
            beforeLeg(attempt)
            val before = r.x to r.y
            val res = r.followPath(path.drop(1).ifEmpty { listOf(tx to ty) }, r.maxSpeed) { x, y, h ->
                grid.speedFactor(x, y, h + if (r.reversed) 180.0 else 0.0).let { if (r.reversed) minOf(it, 0.6) else it }
            }
            travelled += hypot(r.x - before.first, r.y - before.second)
            when {
                res.contains("liaison") || res.contains("vide") || res.startsWith("je ne roule pas") || res == "interrompu" ||
                    res.contains("roue soulevée") || res.startsWith("arrêté") -> return "arrêté : $res"
                res.startsWith("obstacle vu") -> log("👁️ $res") // vu à temps, noté sur la carte : on recalcule le chemin
                res.contains("obstacle") || res.contains("bloqué") -> log("🛞 ${r.unstick(unsticks++ % 3)}")
            }
            if (hypot(tx - r.x, ty - r.y) < 0.35) return "arrivé"
            if (travelled > budget) return "trajet trop long (%.1f m), je m'arrête".format(travelled)
        }
        return if (hypot(tx - r.x, ty - r.y) < 0.6) "presque arrivé" else
            "pas réussi à y arriver (trop d'obstacles ; %.1f m parcourus, à %.1f m du départ)".format(travelled,
                hypot(startM.first - r.x, startM.second - r.y))
    }

    /** Cap (degrés, + = droite) pour regarder le point (x, y) depuis là où il est. */
    fun bearingTo(x: Double, y: Double) = Math.toDegrees(atan2(y - robot.y, x - robot.x))

    /** Distance au point. */
    fun distanceTo(x: Double, y: Double) = hypot(x - robot.x, y - robot.y)
}
