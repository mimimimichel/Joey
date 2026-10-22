package com.joey.app.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyLanguageTest {
    /** Une personne de face : épaules en (0,4 ; 0,4) et (0,6 ; 0,4), nez en (0,5 ; 0,25). */
    private fun pose(lElbow: Pair<Float, Float> = 0.38f to 0.55f, lWrist: Pair<Float, Float> = 0.37f to 0.7f,
                     rElbow: Pair<Float, Float> = 0.62f to 0.55f, rWrist: Pair<Float, Float> = 0.63f to 0.7f): List<FloatArray> {
        val p = MutableList(33) { floatArrayOf(0.5f, 0.5f, 0f) }
        p[0] = floatArrayOf(0.5f, 0.25f, 1f)
        p[11] = floatArrayOf(0.4f, 0.4f, 1f); p[12] = floatArrayOf(0.6f, 0.4f, 1f)
        p[13] = floatArrayOf(lElbow.first, lElbow.second, 1f); p[15] = floatArrayOf(lWrist.first, lWrist.second, 1f)
        p[14] = floatArrayOf(rElbow.first, rElbow.second, 1f); p[16] = floatArrayOf(rWrist.first, rWrist.second, 1f)
        return p
    }

    @Test fun armsDownIsNothing() {
        val b = BodyLanguage()
        repeat(20) { assertNull(b.update(it * 100L, null, pose())) }
    }

    @Test fun pointingToTheSide() {
        val b = BodyLanguage()
        // Bras droit (côté droit de l'image) tendu vers la droite, un peu vers le bas.
        val p = pose(rElbow = 0.72f to 0.43f, rWrist = 0.84f to 0.46f)
        var got: Body? = null
        for (t in 0..10) got = got ?: b.update(t * 100L, null, p)
        assertTrue(got is Body.Point)
        val pt = got as Body.Point
        assertTrue("dx ${pt.dx}", pt.dx > 0.9f)
    }

    @Test fun thumbUpMustBeHeld() {
        val b = BodyLanguage()
        assertNull(b.update(0, "Thumb_Up", pose()))
        assertNull(b.update(300, "Thumb_Up", pose()))
        assertEquals(Body.ThumbUp, b.update(700, "Thumb_Up", pose()))
        assertNull(b.update(800, "Thumb_Up", pose())) // pause avant le suivant
    }

    @Test fun waving() {
        val b = BodyLanguage()
        var got: Body? = null
        for (i in 0 until 16) {
            val x = if (i % 2 == 0) 0.70f else 0.78f
            got = got ?: b.update(i * 120L, null, pose(rElbow = 0.66f to 0.33f, rWrist = x to 0.2f))
        }
        assertEquals(Body.Wave, got)
    }

    @Test fun rockPaperScissors() {
        assertEquals("pierre", BodyLanguage.rps("Closed_Fist"))
        assertEquals("ciseaux", BodyLanguage.rps("Victory"))
        assertNull(BodyLanguage.rps("Thumb_Up"))
    }
}
