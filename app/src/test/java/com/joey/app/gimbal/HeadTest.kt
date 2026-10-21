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
        val h = Head(g, maxStick = { 700 }, sleep = { g.advance(it) }, now = { g.t })
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
        h.look(null, 130.0, timeoutMs = 10_000)
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

    @Test fun frontCameraUpIsGimbalDown() {
        val g = FakeGimbal()
        val h = Head(g, tiltSign = { -1 }, maxStick = { 700 }, sleep = { g.advance(it) }, now = { g.t })
        h.lookDirection("haut", 30.0)
        // Pour la nacelle (caméra arrière), c'est vers le bas ; pour Joey (caméra avant), vers le haut.
        assertTrue("nacelle ${h.tiltOf(g.angles)}", abs(h.tiltOf(g.angles) + 30) < 4)
        assertTrue("joey ${h.pose()!!.second}", abs(h.pose()!!.second - 30) < 4)
        h.nod()
        assertTrue(abs(h.pose()!!.second - 30) < 4)
    }

    @Test fun searchFindsAPersonBehind() {
        val (g, h) = setup()
        val personPan = Head.wrap(g.pan + 140.0) // derrière, un peu à droite
        var seenAt = -1L
        val seen = { t: Long ->
            // Le visage est vu si la tête pointe à moins de 30° de la personne et qu'elle n'est pas trop basse.
            if (abs(Head.wrap(g.pan - personPan)) < 30 && h.pose()!!.second > -10) seenAt = g.t
            seenAt > t
        }
        assertTrue(h.searchTarget(seen))
        assertTrue("pan ${g.pan}", abs(Head.wrap(g.pan - personPan)) < 30)
    }

    @Test fun searchGivesUp() {
        val (_, h) = setup()
        assertEquals(false, h.searchTarget { false })
    }

    @Test fun lookBehind() {
        val (g, h) = setup()
        val start = g.pan
        h.lookDirection("derriere", null)
        assertTrue("pan ${g.pan}", abs(abs(Head.wrap(g.pan - start)) - 179) < 5)
    }

    @Test fun fallsBackWhenGimbalIgnoresBigValues() {
        // Nacelle qui ne bouge pas au-delà de 314
        val f = FakeGimbal()
        val picky = object : GimbalLink {
            override val ready = true
            override val angles get() = f.angles
            override val anglesAt get() = f.anglesAt
            override fun stick(pan: Int, tilt: Int) = f.stick(if (abs(pan) > 314) 0 else pan, if (abs(tilt) > 314) 0 else tilt)
            override fun recenter() {}
        }
        val logs = mutableListOf<String>()
        val h = Head(picky, maxStick = { 1000 }, onLog = { logs += it }, sleep = { f.advance(it) }, now = { f.t })
        val start = f.pan
        h.look(60.0, null)
        assertEquals(314, h.stickCap)
        assertTrue("pan ${f.pan}", abs(Head.wrap(f.pan - start) - 60) < 4)
        assertTrue(logs.any { it.contains("314") })
    }

    @Test fun fastWithHighStick() {
        val (g, h) = setup()
        val t0 = g.t
        h.look(90.0, null)
        assertTrue("durée ${g.t - t0} ms", g.t - t0 < 2500) // ~110°/s dans la fausse nacelle à 700
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
