package com.joey.app.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FaceFollowerTest {
    /**
     * Une personne immobile à [personPan]° / [personTilt]° ; la caméra voit ~70° de large.
     * [camSign] : sens dans lequel l'image se décale quand la tête tourne (selon le montage du téléphone).
     */
    private fun run(camSign: Int, startSign: Int, personPan: Double = 25.0, personTilt: Double = 10.0): Pair<FakeGimbal, FaceFollower> {
        val g = FakeGimbal(pan = 0.0, tiltRaw = 180.0)
        val f = FaceFollower(g, now = { g.t }, initialPanSign = startSign)
        repeat(100) { // 10 s à 10 images/s
            val headTilt = Head.wrap(g.tiltRaw - 180.0)
            val x = (camSign * (personPan - g.pan) / 35.0).toFloat()
            val y = (-(personTilt - headTilt) / 27.0).toFloat()
            if (abs(x) <= 1f && abs(y) <= 1f) f.onFace(x, y) else f.onFace(null, null)
            g.advance(100)
        }
        return g to f
    }

    @Test fun centersTheFace() {
        val (g, _) = run(camSign = 1, startSign = 1)
        assertTrue("pan ${g.pan}", abs(g.pan - 25.0) < 4)
        assertTrue("tilt ${Head.wrap(g.tiltRaw - 180)}", abs(Head.wrap(g.tiltRaw - 180) - 10.0) < 3)
    }

    @Test fun fixesAWrongDirectionByItself() {
        val (g, f) = run(camSign = -1, startSign = 1)
        assertEquals(-1, f.panSign)
        assertTrue("pan ${g.pan}", abs(g.pan - 25.0) < 4)
    }

    @Test fun pausedMeansHandsOff() {
        val g = FakeGimbal()
        val f = FaceFollower(g, now = { g.t })
        f.pause(5000)
        f.onFace(0.8f, 0f)
        assertEquals(0, g.p)
        g.advance(6000)
        f.onFace(0.8f, 0f)
        assertTrue(g.p != 0)
    }

    @Test fun stopsWhenFaceLost() {
        val g = FakeGimbal()
        val f = FaceFollower(g, now = { g.t })
        f.onFace(0.8f, 0f)
        assertTrue(g.p != 0)
        g.advance(1000)
        f.onFace(null, null)
        assertEquals(0, g.p)
    }
}
