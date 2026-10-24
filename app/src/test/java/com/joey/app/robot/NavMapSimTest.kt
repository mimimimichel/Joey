package com.joey.app.robot

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cartographier une pièce en vrai : le cap vient du gyroscope du téléphone (il dérive d'1° par minute), la roue droite
 * compte 3 % de trop (sa position dérive, comme sur un tapis), la
 * « caméra » donne des distances bruitées de 10 % (comme MiDaS). À chaque arrêt, un tour d'horizon (36 directions) est
 * recalé sur la carte ([ScanMatcher]) avant d'y être ajouté. On compare avec et sans recalage : écart de position à la
 * fin, et netteté des murs (part des obstacles de la carte à moins de 10 cm d'un vrai mur).
 */
class NavMapSimTest {
    // Un salon de 4 m × 3 m, un canapé, une table ronde.
    private fun wall(x: Double, y: Double) = x < -0.5 || x > 3.5 || y < -1.5 || y > 1.5 ||
        (x in 2.2..3.0 && y in 0.7..1.5) || hypot(x - 1.0, y + 0.8) < 0.3

    private fun nearWall(x: Double, y: Double): Boolean {
        for (a in 0 until 360 step 30) if (wall(x + 0.10 * cos(Math.toRadians(a.toDouble())), y + 0.10 * sin(Math.toRadians(a.toDouble())))) return true
        return wall(x, y)
    }

    private fun run(match: Boolean, gyro: Boolean, maxTurn: Double = 10.0): Triple<Double, Double, Int> {
        val f = FakeRoomba(); f.wall = ::wall; f.slipR = 1.03
        val r = RoombaRobot(f)
        // Le cap vient du gyroscope du téléphone (il dérive un peu : 1° par minute), pas des roues.
        val t0 = System.currentTimeMillis()
        if (gyro) r.gyroHeading = { f.h + (System.currentTimeMillis() - t0) / 60_000.0 }
        r.connect(); Thread.sleep(200)
        val g = NavGrid()
        val rnd = Random(3)
        var fixes = 0
        // Un tour d'horizon depuis la vraie position, exprimé depuis la position qu'il croit avoir.
        fun scan() {
            val pts = ArrayList<Pair<Double, Double>>()
            val rays = ArrayList<Pair<Double, Double>>()
            for (k in 0 until 36) {
                val b = k * 10.0
                val a = Math.toRadians(f.h + b)
                var d = 0.0
                while (d < 3.0 && !wall(f.x + d * cos(a), f.y + d * sin(a))) d += 0.01
                if (d >= 3.0) continue
                val noisy = d * (1 + rnd.nextDouble(-0.10, 0.10))
                val wb = Math.toRadians(r.heading + b) // direction telle qu'il la croit
                pts.add(noisy * cos(wb) to noisy * sin(wb))
                rays.add(b to noisy)
            }
            if (match) {
                ScanMatcher.match(g.occupiedKeys(), g.cell, r.x, r.y, pts, maxTurnDeg = maxTurn)?.let { fx ->
                    r.setPose(r.x + fx.dx, r.y + fx.dy, r.heading + fx.dthDeg); fixes++
                }
            }
            // Ajout à la carte (comme Navigator.vision : sol libre jusqu'au bord, bord occupé), depuis la pose corrigée.
            for ((b, d) in rays) {
                g.markFreeRay(r.x, r.y, r.heading + b, d - 0.08, halfDeg = 5.0)
                repeat(2) { g.markSeenObstacle(r.x, r.y, r.heading + b, d + 0.05) }
            }
        }
        try {
            scan()
            // Un tour du salon : des allers-retours entre des points sûrs (loin des meubles).
            val stops = listOf(1.5 to 0.5, 2.8 to 0.0, 2.8 to -1.0, 1.0 to 0.3, 0.0 to 1.0, 0.0 to -1.0, 2.0 to -1.0, 2.6 to 0.2, 0.2 to 0.0)
            for ((tx, ty) in stops) {
                // Il vise sa cible d'après sa position supposée (comme le vrai).
                val turn = com.joey.app.gimbal.Head.wrap(Math.toDegrees(kotlin.math.atan2(ty - r.y, tx - r.x)) - r.heading)
                r.turn(turn)
                r.drive(hypot(tx - r.x, ty - r.y))
                scan()
            }
            val err = hypot(f.x - r.x, f.y - r.y)
            val occ = g.occupiedKeys()
            val sharp = occ.count { k -> nearWall((NavGrid.ki(k) + 0.5) * g.cell, (NavGrid.kj(k) + 0.5) * g.cell) }.toDouble() / maxOf(1, occ.size)
            println("recalage=$match gyro=$gyro : écart de position %.2f m, murs nets %.0f %%, %d recalages, cap vrai %.0f° cru %.0f°".format(
                err, sharp * 100, fixes, f.h, r.heading))
            println(g.ascii(1.5, 0.0, 40))
            return Triple(err, sharp, fixes)
        } finally { r.disconnect() }
    }

    /** Cap par le gyroscope (téléphone fixé au corps) : la carte est déjà nette ; le recalage ne l'abîme pas. */
    @Test(timeout = 300_000)
    fun avecLeGyroscopeLaCarteEstNette() {
        val (errNo, sharpNo, _) = run(match = false, gyro = true)
        val (errYes, sharpYes, _) = run(match = true, gyro = true)
        assertTrue("position : %.2f m avec recalage, %.2f m sans".format(errYes, errNo), errYes < 0.15 && errNo < 0.2)
        assertTrue("murs nets : %.0f %% avec, %.0f %% sans".format(sharpYes * 100, sharpNo * 100), sharpYes > 0.9)
    }

    /** Cap par les roues seulement (3 % de patinage) : sans recalage la carte se tord ; le recalage la redresse. */
    @Test(timeout = 300_000)
    fun sansGyroscopeLeRecalageRedresseLaCarte() {
        val (errNo, sharpNo, _) = run(match = false, gyro = false)
        val (errYes, sharpYes, fixes) = run(match = true, gyro = false, maxTurn = 20.0)
        assertTrue("recalages : $fixes", fixes >= 3)
        // Le gain varie d'un passage à l'autre (24/10 : de 33 % à 92 % sur quatre passages) : au moins 20 % de mieux.
        assertTrue("position : %.2f m avec, %.2f m sans".format(errYes, errNo), errYes < errNo * 0.8)
        assertTrue("murs nets : %.0f %% avec, %.0f %% sans".format(sharpYes * 100, sharpNo * 100), sharpYes > sharpNo)
    }
}
