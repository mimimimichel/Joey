package com.joey.app.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CableTwistTest {
    @Test fun compteLesToursSansRepli() {
        val c = CableTwist()
        var h = 0.0
        c.update(h, 0.0)
        repeat(20) { h = Head.wrap(h + 15); c.update(h, 0.0) } // 300° vers la droite, par petits pas
        assertEquals(300.0, c.value, 1e-6)
    }

    @Test fun leCorpsQuiTourneCompteAussi() {
        val c = CableTwist()
        c.update(0.0, 0.0)
        // La tête reste sur la même cible pendant que le Roomba tourne de 90° à droite : par rapport au corps, la tête a
        // tourné de 90° à gauche.
        var b = 0.0
        repeat(9) { b += 10; c.update(0.0, b) }
        assertEquals(-90.0, c.value, 1e-6)
    }

    @Test fun repartParLAutreCote() {
        val c = CableTwist()
        c.update(0.0, 0.0)
        var h = 0.0
        repeat(16) { h = Head.wrap(h + 10); c.update(h, 0.0) } // 160° à droite
        // Encore 90° à droite : ça ferait 250°, il va plutôt de 270° à gauche.
        assertEquals(-270.0, c.safe(90.0), 1e-6)
        assertEquals(-30.0, c.safe(-30.0), 1e-6)
        assertTrue(!c.blocks(1.0))
        repeat(3) { h = Head.wrap(h + 10); c.update(h, 0.0) } // 190°
        assertTrue(c.blocks(1.0) && !c.blocks(-1.0))
        assertEquals(-175.0, c.startForFullTurn(1), 1e-6)
    }
}
