package com.joey.app.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * Un escalier (un trou de 80 cm × 80 cm) entre Joey et son but. Ses capteurs de vide le sentent au bord : il doit
 * reculer, noter le vide sur sa carte, et le contourner, sans jamais avoir le centre au-dessus du vide. Puis revenir.
 */
class NavCliffSimTest {
    @Test(timeout = 180_000)
    fun contourneLEscalierSansTomber() {
        val f = FakeRoomba()
        f.hole = { x, y -> x in 1.2..2.0 && y in -0.4..0.4 }
        val r = RoombaRobot(f)
        r.connect()
        Thread.sleep(200)
        val g = NavGrid()
        val n = Navigator(r, g) { println("[nav] %.2f,%.2f : %s".format(f.x, f.y, it)) }
        r.onContact = { k, s -> n.contact(k, s) }
        val running = AtomicBoolean(true)
        thread(isDaemon = true) { while (running.get()) { n.tick(); Thread.sleep(100) } }
        try {
            val res = n.navigateTo(2.7, 0.0, tries = 12, budget = 20.0)
            println("aller : $res ; %.2f,%.2f".format(f.x, f.y))
            assertEquals("il est passé au-dessus du vide", 0.0, f.maxInHole, 0.0)
            assertTrue("$res (à %.2f m du but)".format(hypot(f.x - 2.7, f.y)), hypot(f.x - 2.7, f.y) < 0.6)
            // Retour au départ par sa carte : il connaît le vide, il ne doit même plus le sentir.
            val back = n.navigateTo(0.0, 0.0, tries = 8, budget = 15.0)
            println("retour : $back ; %.2f,%.2f".format(f.x, f.y))
            assertEquals(0.0, f.maxInHole, 0.0)
            assertTrue("$back", hypot(f.x, f.y) < 0.6)
        } finally {
            running.set(false)
            r.disconnect()
        }
    }
}
