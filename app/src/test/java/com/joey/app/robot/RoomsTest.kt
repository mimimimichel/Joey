package com.joey.app.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsTest {
    private val cell = 0.05
    private fun free(pred: (Double, Double) -> Boolean, x0: Double, x1: Double, y0: Double, y1: Double): Set<Long> {
        val s = HashSet<Long>()
        var i = Math.floor(x0 / cell).toInt()
        while ((i + 0.5) * cell <= x1) {
            var j = Math.floor(y0 / cell).toInt()
            while ((j + 0.5) * cell <= y1) {
                if (pred((i + 0.5) * cell, (j + 0.5) * cell)) s.add(NavGrid.key(i, j))
                j++
            }
            i++
        }
        return s
    }

    /** Deux pièces de 3 m × 3 m, une cloison avec une porte de 80 cm : deux pièces, une porte. */
    @Test fun deuxPiecesEtUnePorte() {
        val f = free({ x, y -> !(x in 2.95..3.05 && y !in 1.1..1.9) }, 0.0, 6.0, 0.0, 3.0)
        val r = Rooms.segment(f, cell)
        assertEquals("${r.rooms}", 2, r.rooms.size)
        assertNotEquals(r.roomAt(1.5, 1.5), r.roomAt(4.5, 1.5))
        assertEquals(1, r.doors.size)
        val d = r.doors.first()
        assertEquals(3.0, d.x, 0.2); assertEquals(1.5, d.y, 0.3)
        assertTrue("largeur ${d.width}", d.width < 1.2)
        r.rooms.forEach { assertEquals(9.0, it.area, 1.2) }
    }

    /** Une seule pièce avec une table au milieu (elle coupe le centre) : toujours une seule pièce. */
    @Test fun uneTableNeCoupePasLaPiece() {
        val f = free({ x, y -> !(x in 1.6..2.6 && y in 1.2..2.0) }, 0.0, 4.2, 0.0, 3.2)
        val r = Rooms.segment(f, cell)
        assertEquals("${r.rooms}", 1, r.rooms.size)
    }

    /** Trois pièces en enfilade (deux portes) ; un couloir étroit seul : une zone. */
    @Test fun troisPiecesEtUnCouloir() {
        val f = free({ x, y -> !((x in 2.95..3.05 || x in 5.95..6.05) && y !in 1.0..1.8) }, 0.0, 9.0, 0.0, 3.0)
        val r = Rooms.segment(f, cell)
        assertEquals("${r.rooms}", 3, r.rooms.size)
        assertEquals(2, r.doors.size)
        val corridor = Rooms.segment(free({ _, _ -> true }, 0.0, 4.0, 0.0, 0.7), cell)
        assertEquals(1, corridor.rooms.size)
    }
}
