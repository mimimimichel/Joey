package com.joey.app.robot

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exploration en autonomie dans un appartement simulé : deux pièces de 3 m × 3 m, une cloison avec une porte de 80 cm.
 * Il part de la première pièce, sans rien connaître, avec une caméra qui voit le sol : il doit trouver la porte,
 * passer dans la deuxième pièce, et cartographier l'essentiel de l'appartement.
 */
class NavExploreSimTest {
    private val flat = { x: Double, y: Double ->
        x < -0.5 || x > 5.5 || y < -1.5 || y > 1.5 ||          // murs extérieurs
            (x in 2.4..2.6 && !(y in 0.2..1.0))                  // cloison, porte entre y = 0,2 et 1,0
    }

    @Test(timeout = 240_000)
    fun trouveLaPorteEtCartographieLesDeuxPieces() {
        val f = FakeRoomba()
        f.wall = flat
        val r = RoombaRobot(f)
        r.connect()
        Thread.sleep(200)
        val g = NavGrid()
        val n = Navigator(r, g) { println("[nav] %.2f,%.2f : %s".format(f.x, f.y, it)) }
        r.onContact = { k, s -> n.contact(k, s) }
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        thread(isDaemon = true) { while (running.get()) { n.tick(); Thread.sleep(100) } }
        // Caméra simulée qui voit le sol sur ±30° (une direction tous les 4°), jusqu'à 3 m.
        fun ray(b: Double): Double {
            val a = Math.toRadians(f.h + b)
            var d = 0.0
            while (d < 3.0) { if (flat(f.x + d * cos(a), f.y + d * sin(a))) return d; d += 0.02 }
            return Double.POSITIVE_INFINITY
        }
        var last = listOf<Pair<Double, Double>>()
        r.visionClear = {
            last.filter { (b, d) -> kotlin.math.abs(b) < 8 || kotlin.math.abs(kotlin.math.tan(Math.toRadians(b))) * minOf(d, 3.0) <= 0.19 }
                .minOfOrNull { it.second }
        }
        thread(isDaemon = true) {
            while (running.get()) {
                last = (-30..30 step 4).map { b -> b.toDouble() to ray(b.toDouble()) }
                n.vision(r.heading, last)
                Thread.sleep(150)
            }
        }
        // Regarder autour (comme la tête qui balaie) : un tour sur lui-même en 4 arrêts.
        fun look() = repeat(4) { r.turn(90.0); Thread.sleep(250) }
        try {
            look()
            var room2 = false
            val t0 = System.currentTimeMillis()
            var steps = 0
            while (System.currentTimeMillis() - t0 < 150_000) {
                val res = n.frontierStep(budget = 4.0) ?: break
                steps++
                if (f.x > 2.8) room2 = true
                if (res.startsWith("arrêté")) break
                look()
            }
            val area = g.knownArea()
            println("exploration : %d pas, %.1f m² connus, 2e pièce : %s, %d chocs, %.0f s".format(steps, area, room2, f.bumps,
                (System.currentTimeMillis() - t0) / 1000.0))
            println(g.ascii(2.5, 0.0, 32))
            assertTrue("il n'a pas trouvé la porte (2e pièce jamais atteinte)", room2)
            assertTrue("carte trop petite : %.1f m² (l'appartement fait ~17 m²)".format(area), area > 10.0)
        } finally {
            running.set(false)
            r.disconnect()
        }
    }
}
