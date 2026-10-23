package com.joey.app.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FloorVisionTest {
    private val w = 64
    private val h = 48
    private val floor = 0xFFB08A5C.toInt()   // parquet
    private val wall = 0xFFE8E4DC.toInt()    // mur clair
    private val sofa = 0xFF3A4A6A.toInt()    // canapé bleu

    /** Une scène : sol jusqu'à la ligne [floorTop] (au-dessus : mur), et un objet [obj] dans les colonnes [ox0, ox1) à partir de la ligne [oy]. */
    private fun scene(floorTop: Int, obj: Triple<Int, Int, Int>? = null): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        when {
            obj != null && x in obj.first until obj.second && y <= obj.third -> sofa
            y >= floorTop -> floor + ((x * 7 + y * 13) % 5) // un peu de grain
            else -> wall
        }
    }

    @Test fun distanceDuSolSelonLaLigne() {
        val f = FloorVision(cameraHeight = 0.40)
        // Caméra baissée de 30° : le centre de l'image regarde le sol à 0,40 / tan(30°) ≈ 0,69 m.
        assertEquals(0.69, f.rowToDistance(h / 2, h, -30.0, 50.0), 0.05)
        // Plus haut dans l'image = plus loin ; au-dessus de l'horizon = l'infini.
        assertTrue(f.rowToDistance(10, h, -30.0, 50.0) > 1.0)
        assertTrue(f.rowToDistance(0, h, 0.0, 50.0).isInfinite())
    }

    @Test fun voitLeMurEtLeCanape() {
        val f = FloorVision(cameraHeight = 0.40)
        f.learnFloor(scene(10), w, h, rate = 1.0)
        // Sol dégagé jusqu'au mur (ligne 10) : beaucoup de place devant.
        val open = f.analyse(scene(10), w, h, pitchDeg = -25.0, hfov = 60.0, vfov = 46.0)
        assertTrue(open.floorVisible)
        assertTrue("${open.clearAhead}", open.clearAhead > 1.0)
        // Un canapé droit devant, dont le bas est à la ligne 30 : il est bien plus près.
        val blocked = f.analyse(scene(10, Triple(24, 40, 30)), w, h, pitchDeg = -25.0, hfov = 60.0, vfov = 46.0)
        assertTrue("${blocked.clearAhead}", blocked.clearAhead < open.clearAhead * 0.7)
        val expected = f.rowToDistance(31, h, -25.0, 46.0)
        assertTrue("${blocked.clearAhead} vs $expected", abs(blocked.clearAhead - expected) < 0.15)
        // Sur les côtés, la voie reste libre plus loin.
        assertTrue(blocked.rays.first().distance > blocked.clearAhead)
    }

    @Test fun sansSolAppris() {
        val f = FloorVision()
        val r = f.analyse(scene(10), w, h, -25.0, 60.0, 46.0)
        assertTrue(!r.floorVisible && r.clearAhead.isNaN())
    }

    /** 24/10 : une chaise droit devant, reconnue par le détecteur d'objets, donne sa distance par le bas de sa boîte. */
    @Test fun chaiseVueParLeDetecteur() {
        val f = FloorVision(cameraHeight = 0.40)
        // Tête baissée de 22° : le bas de la boîte au centre de l'image est à 0,40 / tan(22°) ≈ 1 m.
        val devant = f.objectClear(listOf(Box(0.5, 0.25, 0.3, 0.5)), -22.0, 66.0, 51.0)
        assertEquals(0.99, devant, 0.05)
        // Plus bas dans l'image = plus près.
        assertTrue(f.objectClear(listOf(Box(0.5, 0.5, 0.3, 0.6)), -22.0, 66.0, 51.0) < devant)
        // Une chaise bien sur le côté ne bloque pas son couloir.
        assertTrue(f.objectClear(listOf(Box(0.95, 0.4, 0.08, 0.4)), -22.0, 66.0, 51.0).isInfinite())
        // Une boîte au-dessus de l'horizon (tableau au mur) : rien.
        assertTrue(f.objectClear(listOf(Box(0.5, 0.1, 0.2, 0.1)), 0.0, 66.0, 51.0).isInfinite())
    }
}
