package com.joey.app.senses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il regarde les mains quand elles bougent, puis revient au visage. */
class HandAttentionTest {
    private var t = 0L
    private val h = HandAttention { t }

    /** Un corps : nez en (0.5, 0.3), main droite en (hx, hy). [shift] décale tout (la tête de Joey qui tourne). */
    private fun pose(hx: Float, hy: Float, shift: Float = 0f) = List(33) { floatArrayOf(0f, 0f, 0f) }.toMutableList().apply {
        this[0] = floatArrayOf(0.5f + shift, 0.3f, 1f)
        this[16] = floatArrayOf(hx + shift, hy, 1f)
        this[20] = floatArrayOf(hx + shift + 0.02f, hy - 0.02f, 1f)
    }

    private fun step(p: List<FloatArray>) { h.update(p, 0f, -0.4f); t += 300 }

    @Test fun lookAtMovingHandsThenBack() {
        repeat(4) { step(pose(0.7f, 0.85f)) } // mains posées : rien
        assertNull(h.aimPoint(0f, -0.4f))
        step(pose(0.6f, 0.6f)); step(pose(0.75f, 0.5f)); step(pose(0.6f, 0.55f)) // il montre quelque chose
        assertTrue(h.active)
        assertNotNull(h.aimPoint(0f, -0.4f))
        repeat(6) { step(pose(0.6f, 0.55f)) } // la main ne bouge plus : retour au visage après ~1,2 s
        assertFalse(h.active)
        assertNull(h.aimPoint(0f, -0.4f))
    }

    /** Mains hors de l'image (sous le cadre) : on les suit grâce aux bras (épaule -> coude). */
    @Test fun handsBelowTheFrameFollowedByTheArm() {
        fun arm(ex: Float, ey: Float) = List(33) { floatArrayOf(0f, 0f, 0f) }.toMutableList().apply {
            this[0] = floatArrayOf(0.5f, 0.3f, 1f)
            this[12] = floatArrayOf(0.62f, 0.62f, 1f) // épaule droite
            this[14] = floatArrayOf(ex, ey, 0.9f)      // coude, au bord bas de l'image
            this[16] = floatArrayOf(0f, 0f, 0.1f)      // poignet : invisible
        }
        repeat(3) { h.update(arm(0.66f, 0.95f), 0f, -0.4f); t += 300 }
        h.update(arm(0.78f, 0.9f), 0f, -0.4f); t += 300  // il lève et écarte le bras
        h.update(arm(0.66f, 0.95f), 0f, -0.4f); t += 300
        h.update(arm(0.8f, 0.88f), 0f, -0.4f); t += 300
        assertTrue("regarde les mains déduites", h.active)
        val p = h.aimPoint(0f, -0.4f)!!
        assertTrue("plus bas que le visage (${p.second})", p.second > 0.3f)
    }

    @Test fun headTurningIsNotHandsMoving() {
        for (i in 0 until 8) step(pose(0.7f, 0.6f, shift = i * 0.05f)) // tout glisse ensemble
        assertFalse(h.active)
    }

    @Test fun neverStaresAtHandsForever() {
        // Il gesticule sans arrêt pendant 15 s : jamais plus de 5 s d'affilée sur les mains, puis au moins 2,5 s au visage.
        var run = 0; var longest = 0; var faceAfter = 0; var started = false
        for (i in 0 until 50) {
            step(pose(if (i % 2 == 0) 0.6f else 0.8f, 0.55f))
            val onHands = h.aimPoint(0f, -0.4f) != null
            if (onHands) { started = true; run++; longest = maxOf(longest, run) } else { run = 0; if (started) faceAfter++ }
        }
        assertTrue("au plus 5 s sur les mains ($longest pas)", longest * 300 <= 5300)
        assertTrue("revient au visage ($faceAfter pas)", faceAfter * 300 >= 2400)
    }
}
