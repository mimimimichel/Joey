package com.joey.app.robot

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Joey dans un monde simulé (le faux Roomba cogne les murs comme un vrai) : il doit trouver seul son chemin jusqu'à
 * un but caché derrière un meuble qu'il ne connaît pas.
 */
class NavSimTest {
    /** Un canapé de 1 m de large entre lui et le but. */
    private val sofa = { x: Double, y: Double -> x in 1.0..1.5 && y in -0.5..0.5 }

    private class Sim(val f: FakeRoomba, val r: RoombaRobot, val g: NavGrid, val n: Navigator) {
        @Volatile var running = true
        fun stop() { running = false; r.disconnect() }
    }

    private fun start(world: (Double, Double) -> Boolean, vision: Boolean): Sim {
        val f = FakeRoomba()
        f.wall = world
        val r = RoombaRobot(f)
        r.connect()
        Thread.sleep(200)
        val g = NavGrid()
        val n = Navigator(r, g)
        r.onContact = { k, s -> n.contact(k, s) }
        val sim = Sim(f, r, g, n)
        // La carte suit ses passages (comme le fil « câble » de l'app).
        thread(isDaemon = true) { while (sim.running) { n.tick(); Thread.sleep(100) } }
        if (vision) {
            // Une caméra parfaite qui voit le sol : pour chaque direction, la distance jusqu'au premier obstacle.
            fun ray(b: Double): Double {
                val a = Math.toRadians(f.h + b)
                var d = 0.0
                while (d < 3.0) { if (world(f.x + d * cos(a), f.y + d * sin(a))) return d; d += 0.02 }
                return Double.POSITIVE_INFINITY
            }
            var last = listOf<Pair<Double, Double>>()
            r.visionClear = { last.filter { kotlin.math.abs(it.first) <= 10 }.minOfOrNull { it.second } }
            thread(isDaemon = true) {
                while (sim.running) {
                    last = (-30..30 step 10).map { b -> b.toDouble() to ray(b.toDouble()) }
                    n.vision(r.heading, last)
                    Thread.sleep(150)
                }
            }
        }
        return sim
    }

    @Test(timeout = 120_000)
    fun contourneUnCanapeQuIlNeConnaitPas() {
        val sim = start(sofa, vision = false)
        try {
            val res = sim.n.navigateTo(2.3, 0.0, tries = 12, budget = 20.0)
            val d = hypot(sim.f.x - 2.3, sim.f.y)
            assertTrue("$res (à %.2f m du but, %d chocs)".format(d, sim.f.bumps), d < 0.6)
            assertTrue("il a dû toucher le canapé pour le découvrir", sim.f.bumps >= 1)
        } finally { sim.stop() }
    }

    @Test(timeout = 120_000)
    fun avecLaCameraIlLeContourneSansLeToucher() {
        val sim = start(sofa, vision = true)
        try {
            val res = sim.n.navigateTo(2.3, 0.0, tries = 12, budget = 20.0)
            val d = hypot(sim.f.x - 2.3, sim.f.y)
            assertTrue("$res (à %.2f m du but)".format(d), d < 0.6)
            assertTrue("chocs : ${sim.f.bumps}", sim.f.bumps <= 1)
        } finally { sim.stop() }
    }
}
