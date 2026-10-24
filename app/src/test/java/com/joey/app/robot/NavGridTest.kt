package com.joey.app.robot

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class NavGridTest {
    /** Une pièce de 4 m × 2 m parcourue en zigzag (tout libre). */
    private fun room(): NavGrid {
        val g = NavGrid()
        var x = 0.0
        while (x <= 4.0) { var y = -1.0; while (y <= 1.0) { g.markRobot(x, y); y += 0.1 }; x += 0.1 }
        return g
    }

    private fun wall(g: NavGrid, x: Double, y0: Double, y1: Double) {
        var y = y0
        while (y <= y1) { repeat(3) { g.markObstacle(x - 0.23, y, 0.0) }; y += 0.05 }
    }

    @Test fun contourneUnMur() {
        val g = room()
        wall(g, 2.0, -1.2, 0.4) // un mur avec un passage en haut (y > 0,4)
        val p = g.plan(0.5, 0.0, 3.5, 0.0)
        assertNotNull(p)
        p!!
        assertTrue("$p", hypot(p.last().first - 3.5, p.last().second) < 0.25)
        // Il passe par le trou : à x = 2 m, il est bien au-dessus du mur.
        val nearWall = p.zipWithNext().any { (a, b) -> (a.first - 2.0) * (b.first - 2.0) <= 0 && maxOf(a.second, b.second) > 0.45 }
        assertTrue("$p", nearWall)
        for ((a, b) in p.zipWithNext()) assertTrue("$a -> $b", g.lineClear(a.first, a.second, b.first, b.second))
    }

    @Test fun pasDeCheminSiToutEstBouche() {
        val g = room()
        wall(g, 2.0, -3.0, 3.0)
        // Il pourrait passer par l'inconnu autour de la pièce : on bouche tout l'inconnu en le marquant occupé.
        for (y in listOf(-1.3, 1.3)) { var x = -0.5; while (x <= 4.5) { repeat(3) { g.markObstacle(x, y - 0.23, 90.0) }; x += 0.05 } }
        for (x in listOf(-0.4, 4.4)) { var y = -1.4; while (y <= 1.4) { repeat(3) { g.markObstacle(x - 0.23, y, 0.0) }; y += 0.05 } }
        assertNull(g.plan(0.5, 0.0, 3.5, 0.0))
    }

    @Test fun neTraverseJamaisUnVide() {
        val g = room()
        var y = -1.5
        while (y <= 1.5) { g.markCliff(1.82, y, 0.0); y += 0.05 }
        val p = g.plan(0.5, 0.0, 3.5, 0.0)
        // Il peut contourner le bout de la marche (dans l'inconnu, au-delà de 1,5 m), jamais passer dessus.
        assertTrue("$p", p == null || p.none { it.first in 1.95..2.2 && kotlin.math.abs(it.second) <= 1.5 })
        if (p != null) for ((a, b) in p.zipWithNext()) assertTrue("$a -> $b", g.lineClear(a.first, a.second, b.first, b.second))
    }

    @Test fun frontieresAuxBordsDuConnu() {
        val g = NavGrid()
        var x = 0.0
        while (x <= 2.0) { g.markRobot(x, 0.0); x += 0.05 } // un couloir parcouru
        val f = g.frontiers(0.0, 0.0)
        assertTrue("$f", f.isNotEmpty())
        // La plus proche est près du départ.
        assertTrue("$f", hypot(f.first().first, f.first().second) < 1.2)
        assertTrue(g.knownArea() > 0.5)
    }

    @Test fun laVisionOuvreDuSolLibre() {
        val g = NavGrid()
        g.markRobot(0.0, 0.0)
        g.markFreeRay(0.0, 0.0, 0.0, 2.0)
        assertTrue(g.free(g.idx(1.5), g.idx(0.0)))
        // Un contact contredit la vision.
        g.markObstacle(1.0 - 0.23, 0.0, 0.0)
        assertTrue(g.occupied(g.idx(1.0), g.idx(0.0)))
    }

    @Test fun sauveEtRecharge() {
        val g = room()
        wall(g, 2.0, -1.0, 0.0)
        val f = java.io.File.createTempFile("grille", ".json")
        g.save(f)
        val h = NavGrid()
        assertTrue(h.load(f))
        assertTrue(h.occupied(h.idx(2.0), h.idx(-0.5)))
        assertTrue(h.free(h.idx(0.5), h.idx(0.0)))
    }

    /** 24/10 : glissé entre deux chaises (passage de 45 cm, plus étroit que sa marge), il ne trouvait plus le retour. */
    @Test fun revientParOuIlEstPasse() {
        val g = NavGrid()
        // Des pieds de chaise de part et d'autre d'un passage de 45 cm, de x = 1 à 2 m.
        var x = 1.0
        while (x <= 2.0) { repeat(3) { g.markObstacle(x, 0.0, 90.0, 0.225) ; g.markObstacle(x, 0.0, -90.0, 0.225) }; x += 0.05 }
        // Il y est passé, en ligne droite, de 0 à 2,5 m.
        x = 0.0
        while (x <= 2.5) { g.markRobot(x, 0.0); x += 0.05 }
        // Tout autour : bouché (on ne veut pas qu'il fasse le tour par l'inconnu).
        for (y in listOf(-0.6, 0.6)) { var xx = -0.5; while (xx <= 3.0) { repeat(3) { g.markObstacle(xx, y, if (y > 0) 90.0 else -90.0, 0.0) }; xx += 0.05 } }
        for (xx in listOf(-0.5, 3.0)) { var y = -0.6; while (y <= 0.6) { repeat(3) { g.markObstacle(xx, y, 0.0, 0.0) }; y += 0.05 } }
        val back = g.plan(2.5, 0.0, 0.0, 0.0)
        assertNotNull("pas de retour par son propre chemin", back)
        back!!
        assertTrue("$back", hypot(back.last().first, back.last().second) < 0.25)
        // Le retour reste dans le passage (pas à travers les pieds).
        assertTrue("$back", back.all { kotlin.math.abs(it.second) < 0.2 })
    }

    /** La carte de la maison gardée avant la 0.67 (carreaux de 10 cm) se recharge en carreaux de 5 cm, sans rien perdre. */
    @Test fun rechargeUneCarteDe10cm() {
        val old = NavGrid(0.1)
        var x = 0.0
        while (x <= 2.0) { old.markRobot(x, 0.0); x += 0.1 }
        repeat(3) { old.markObstacle(2.0, 0.0, 0.0) }
        old.markCliff(0.0, 0.0, 180.0)
        val f = java.io.File.createTempFile("carte", ".json")
        old.save(f)
        val g = NavGrid()
        assertTrue(g.load(f))
        assertTrue("libre", g.free(g.idx(1.0), g.idx(0.0)))
        assertTrue("obstacle", g.occupied(g.idx(2.23), g.idx(0.0)))
        assertTrue("surface", kotlin.math.abs(g.knownArea() - old.knownArea()) < 0.05)
        assertNotNull(g.plan(0.2, 0.0, 1.8, 0.0))
        f.delete()
    }
}
