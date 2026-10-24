package com.joey.app.robot

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * La salle à manger du 24/10 : des chaises à pieds fins (4 cm) et un seul passage de 48 cm entre deux chaises (son
 * corps fait 34 cm). Il doit traverser, arriver, puis revenir, sans passer sous une assise (le téléphone taperait le
 * siège : le pare-chocs ne le sentirait pas) et en touchant le moins possible.
 */
class NavChairsSimTest {
    private data class Chair(val cx: Double, val cy: Double) {
        val half = 0.21
        fun leg(x: Double, y: Double) = listOf(-1, 1).any { sx -> listOf(-1, 1).any { sy ->
            hypot(x - (cx + sx * (half - 0.02)), y - (cy + sy * (half - 0.02))) < 0.02 } }
        fun seat(x: Double, y: Double) = abs(x - cx) < half && abs(y - cy) < half
    }

    // Deux chaises de part et d'autre du passage (y = 0), une troisième plus haut, un buffet en bas, la table en haut.
    private val chairs = listOf(Chair(1.6, 0.45), Chair(1.6, -0.45), Chair(1.6, 1.15))
    private fun walls(x: Double, y: Double) = x < -0.5 || x > 3.5 || y < -1.5 || y > 1.5
    private fun buffet(x: Double, y: Double) = x in 1.39..1.81 && y < -0.66
    private fun table(x: Double, y: Double) = hypot(x - 2.3, y - 1.0) < 0.3
    /** Ce que le pare-chocs touche : les pieds, pas l'assise (au-dessus de lui). */
    private val solid = { x: Double, y: Double -> walls(x, y) || buffet(x, y) || table(x, y) || chairs.any { it.leg(x, y) } }
    /** Sous une assise : le téléphone (à 40-50 cm) taperait le siège. */
    private fun underSeat(x: Double, y: Double) = chairs.any { it.seat(x, y) }

    private class Sim(val f: FakeRoomba, val r: RoombaRobot, val g: NavGrid, val n: Navigator) {
        @Volatile var running = true
        @Volatile var underSeat = 0
        fun stop() { running = false; r.disconnect() }
    }

    /**
     * [objects] : le détecteur d'objets voit la chaise entière (sa boîte va de l'assise au sol) ; sinon la caméra ne voit
     * que le sol, et donc seulement les pieds (comme la couleur du sol sur un sol uni).
     */
    private fun start(objects: Boolean): Sim {
        val f = FakeRoomba()
        f.wall = solid
        val r = RoombaRobot(f)
        r.cautiousWhenBlind = true
        r.connect()
        Thread.sleep(200)
        val g = NavGrid()
        val n = Navigator(r, g) { println("[nav] %.2f,%.2f cap %.0f : %s".format(f.x, f.y, f.h, it)) }
        r.onContact = { k, s -> n.contact(k, s) }
        val sim = Sim(f, r, g, n)
        thread(isDaemon = true) {
            while (sim.running) {
                n.tick()
                if (underSeat(f.x, f.y)) sim.underSeat++
                Thread.sleep(100)
            }
        }
        val seen = { x: Double, y: Double -> solid(x, y) || (objects && underSeat(x, y)) }
        fun ray(b: Double): Double {
            val a = Math.toRadians(f.h + b)
            var d = 0.0
            while (d < 3.0) { if (seen(f.x + d * cos(a), f.y + d * sin(a))) return d; d += 0.01 }
            return Double.POSITIVE_INFINITY
        }
        var last = listOf<Pair<Double, Double>>()
        r.visionClear = {
            last.filter { (b, d) -> abs(b) < 8 || abs(kotlin.math.tan(Math.toRadians(b))) * minOf(d, 3.0) <= 0.19 }
                .minOfOrNull { it.second }
        }
        thread(isDaemon = true) {
            while (sim.running) {
                last = (-30..30 step 4).map { b -> b.toDouble() to ray(b.toDouble()) }
                n.vision(r.heading, last)
                Thread.sleep(150)
            }
        }
        return sim
    }

    /**
     * [maxBumps] : sans le détecteur, la caméra ne voit que des pieds isolés (un tous les 4°) et la carte ne les connaît
     * pas tous : il en frôle quelques-uns, lentement (à améliorer : se centrer dans les passages étroits).
     */
    private fun run(objects: Boolean, maxBumps: Int): String {
        val sim = start(objects)
        try {
            val t0 = System.currentTimeMillis()
            val go = sim.n.navigateTo(3.0, 0.0, tries = 12, budget = 25.0)
            val there = hypot(sim.f.x - 3.0, sim.f.y)
            val back = sim.n.navigateTo(0.0, 0.0, tries = 12, budget = 25.0)
            val home = hypot(sim.f.x, sim.f.y)
            val s = "aller : %s (à %.2f m) ; retour : %s (à %.2f m) ; %d chocs ; sous une assise : %d ; %.0f s".format(
                go, there, back, home, sim.f.bumps, sim.underSeat, (System.currentTimeMillis() - t0) / 1000.0)
            println("chaises (objets=$objects) : $s")
            println(sim.g.ascii(1.5, 0.0, 30))
            assertTrue("aller raté : $s", there < 0.5)
            assertTrue("retour raté : $s", home < 0.5)
            assertTrue("passé sous une assise : $s", sim.underSeat == 0)
            assertTrue("trop de chocs : $s", sim.f.bumps <= maxBumps)
            return s
        } finally { sim.stop() }
    }

    @Test(timeout = 200_000)
    fun traverseEntreLesChaisesEtRevientAvecLeDetecteur() { run(objects = true, maxBumps = 2) }

    /** Sans le détecteur : la caméra ne voit que les pieds ; deux pieds voisins forment un bord, il ne passe pas dessous. */
    @Test(timeout = 200_000)
    fun traverseEntreLesChaisesEtRevientSansLeDetecteur() { run(objects = false, maxBumps = 10) }
}
