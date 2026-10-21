package com.joey.app.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sign

/** Fausse nacelle : vitesse proportionnelle au joystick au-delà d'une zone morte (comme la vraie, en gros). */
class FakeGimbal(var pan: Double = -131.0, var tiltRaw: Double = -179.9) : GimbalLink {
    var t = 0L
    var p = 0
    var tl = 0
    var recentered = 0
    override val ready = true
    override val angles get() = SpgProtocol.Angles(tiltRaw, 0.0, pan)
    override val anglesAt get() = t
    override fun stick(pan: Int, tilt: Int) { p = pan; tl = tilt }
    override fun recenter() { recentered++; tiltRaw = 180.0 }
    private fun rate(cmd: Int, max: Double) = if (abs(cmd) < 100) 0.0 else sign(cmd.toDouble()) * (abs(cmd) - 100) / 220.0 * max
    fun advance(ms: Long) {
        val dt = ms / 1000.0
        pan = Head.wrap(pan + rate(p, 40.0) * dt)
        tiltRaw = Head.wrap(tiltRaw + rate(tl, 22.0) * dt)
        t += ms
    }
}

class HeadTest {
    private fun setup(): Pair<FakeGimbal, Head> {
        val g = FakeGimbal()
        val h = Head(g, sleep = { g.advance(it) }, now = { g.t })
        return g to h
    }

    @Test fun wrap() {
        assertEquals(-170.0, Head.wrap(190.0), 1e-9)
        assertEquals(170.0, Head.wrap(-190.0), 1e-9)
    }

    @Test fun tiltZeroWhenLevel() {
        val (g, h) = setup()
        assertEquals(0.1, h.tiltOf(g.angles), 1e-6)
        g.tiltRaw = -120.0
        assertEquals(60.0, h.tiltOf(g.angles), 1e-6) // joystick + = vers le haut (vu dans la capture)
        g.tiltRaw = 151.93
        assertEquals(-28.07, h.tiltOf(g.angles), 1e-6)
    }

    @Test fun lookRightThenFront() {
        val (g, h) = setup()
        h.lookDirection("droite", 45.0)
        val p = h.pose()!!
        assertTrue("pan ${p.first}", abs(p.first - 45) < 4)
        assertEquals(0, g.p)
        h.lookDirection("devant", null)
        assertTrue("pan ${h.pose()!!.first}", abs(h.pose()!!.first) < 4)
    }

    @Test fun lookAcrossTheWrap() {
        val (g, h) = setup()
        g.pan = 170.0
        h.look(30.0, null)
        assertTrue("pan ${g.pan}", abs(Head.wrap(g.pan - (-160.0))) < 4)
    }

    @Test fun tiltUpIsClamped() {
        val (_, h) = setup()
        h.look(null, 90.0, timeoutMs = 10_000)
        assertTrue(abs(h.pose()!!.second - Head.TILT_MAX) < 4)
    }

    @Test fun invertedPan() {
        val g = FakeGimbal()
        val h = Head(g, rightSign = { -1 }, sleep = { g.advance(it) }, now = { g.t })
        val start = g.pan
        h.lookDirection("droite", 30.0)
        assertTrue("pan ${g.pan}", g.pan < start - 25) // droite = joystick négatif
        assertTrue(abs(h.pose()!!.first - 30) < 4)
    }

    @Test fun gesturesEndStopped() {
        val (g, h) = setup()
        h.nod(); assertEquals(0, g.p); assertEquals(0, g.tl)
        h.shake(); assertEquals(0, g.p)
        val stops = mutableListOf<String>()
        h.sweep { stops += it }
        assertEquals(listOf("à gauche", "devant", "à droite"), stops)
        assertTrue(abs(h.pose()!!.first) < 5)
    }

    @Test fun blindWhenNoAngles() {
        val g = object : GimbalLink {
            val sent = mutableListOf<Pair<Int, Int>>()
            override val ready = true
            override val angles: SpgProtocol.Angles? = null
            override val anglesAt = 0L
            override fun stick(pan: Int, tilt: Int) { sent += pan to tilt }
            override fun recenter() {}
        }
        val h = Head(g, sleep = {})
        h.look(-30.0, null)
        assertTrue(g.sent.first().first < 0)
        assertEquals(0 to 0, g.sent.last())
    }
}
