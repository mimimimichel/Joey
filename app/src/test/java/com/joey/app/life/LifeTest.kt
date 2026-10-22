package com.joey.app.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StartleTest {
    private var t = 0L
    private val s = Startle(jump = 20.0, floor = -30.0) { t }
    private fun feed(db: Double, ms: Long = 100): Boolean { val r = s.feed(db); t += ms; return r }

    @Test fun doorSlamStartles() {
        repeat(30) { feed(-60.0) } // pièce calme
        assertFalse(feed(-10.0)) // BAM
        assertFalse(feed(-25.0))
        assertTrue(feed(-58.0)) // retombé vite : c'était un bruit soudain
    }

    @Test fun speechDoesNot() {
        repeat(30) { feed(-60.0) }
        var fired = false
        repeat(25) { fired = fired || feed(-20.0 + (it % 3) * 2) } // quelqu'un parle 2,5 s
        repeat(10) { fired = fired || feed(-60.0) }
        assertFalse(fired)
    }
}

class BodyMotionTest {
    private var t = 0L
    private val m = BodyMotion { t }
    private fun run(ms: Long, ax: Double = 0.0, ay: Double = 0.0, az: Double = 0.0): BodyMotion.Event? {
        var e: BodyMotion.Event? = null
        val end = t + ms
        while (t < end) { m.feed(ax, ay, az)?.let { e = it }; t += 20 }
        return e
    }

    @Test fun liftedThenPutDown() {
        run(1000)
        assertEquals(BodyMotion.Event.LIFTED, run(300, az = 3.0)) // on le soulève
        run(300, az = -3.0) // freine en haut
        run(1500) // tenu en l'air
        val down = run(300, az = -3.0) ?: run(300, az = 3.0)
        assertEquals(BodyMotion.Event.PUT_DOWN, down)
    }

    @Test fun pushedAcrossTheFloor() {
        run(1000)
        assertEquals(BodyMotion.Event.PUSHED, run(500, ax = 2.5))
    }

    @Test fun sensorNoiseIsNothing() {
        var e: BodyMotion.Event? = null
        repeat(500) { m.feed(0.2 * ((it % 5) - 2), 0.15 * ((it % 3) - 1), 0.2 * ((it % 7) - 3))?.let { e = it }; t += 20 }
        assertNull(e)
    }

    @Test fun ownWheelsAreQuiet() {
        m.quietUntil = 5000
        assertNull(run(600, ax = 3.0))
    }

    @Test fun falling() {
        var e: BodyMotion.Event? = null
        repeat(10) { m.feedRaw(9.8); t += 20 }
        repeat(8) { m.feedRaw(0.8)?.let { e = it }; t += 20 }
        assertEquals(BodyMotion.Event.FALLING, e)
    }
}

class ExpressiveTest {
    private var t = 0L
    private val x = Expressive({ t }, { 0.5 })

    @Test fun breathesWhenIdle() {
        var lo = 0.0; var hi = 0.0
        repeat(100) { val p = x.pose(0f, false, false, false, 0.8); lo = minOf(lo, p.tilt); hi = maxOf(hi, p.tilt); t += 50 }
        assertTrue("respire ($lo..$hi)", hi - lo > 3 && hi - lo < 8)
    }

    @Test fun nodsWhileListening() {
        // Même horloge, l'un écoute, l'autre non : la différence, c'est le hochement.
        val other = Expressive({ t }, { 0.5 })
        var deepest = 0.0
        repeat(200) {
            val d = x.pose(0f, false, true, false, 0.8).tilt - other.pose(0f, false, false, false, 0.8).tilt
            deepest = minOf(deepest, d); t += 50
        }
        assertTrue("hoche la tête ($deepest)", deepest < -6)
    }

    @Test fun tiltsHeadWhenThinking() {
        var p = x.pose(0f, false, false, false, 0.8)
        repeat(20) { t += 50; p = x.pose(0f, false, false, true, 0.8) }
        assertTrue("tête penchée (${p.screenTilt})", p.screenTilt > 12f)
        assertTrue("regarde en l'air (${p.tilt})", p.tilt > 5)
        repeat(20) { t += 50; p = x.pose(0f, false, false, false, 0.8) }
        assertEquals(0f, p.screenTilt)
    }

    @Test fun movesWhenSpeakingButStaysSmall() {
        var maxPan = 0.0; var maxTilt = 0.0
        repeat(200) {
            val v = if ((it / 4) % 2 == 0) 0.25f else 0.02f // syllabes
            val p = x.pose(v, true, false, false, 0.8)
            maxPan = maxOf(maxPan, abs(p.pan)); maxTilt = maxOf(maxTilt, abs(p.tilt)); t += 50
        }
        assertTrue("bouge ($maxPan, $maxTilt)", maxTilt > 3.5)
        assertTrue("reste raisonnable", maxPan < 12 && maxTilt < 14)
    }
}

class MoodPoseTest {
    @Test fun sadHangsHeadAngryShakes() {
        var t = 10_000L
        val x = Expressive({ t }, { 0.5 })
        var p = x.pose(0f, false, false, false, 0.8, com.joey.app.face.Mood.TRISTE, 10_000L)
        repeat(20) { t += 50; p = x.pose(0f, false, false, false, 0.8, com.joey.app.face.Mood.TRISTE, 10_000L) }
        assertTrue("tête basse (${p.tilt})", p.tilt < -10)
        var lo = 0.0; var hi = 0.0
        val since = t
        repeat(16) { t += 50; val q = x.pose(0f, false, false, false, 0.8, com.joey.app.face.Mood.FACHE, since); lo = minOf(lo, q.pan); hi = maxOf(hi, q.pan) }
        assertTrue("secoue la tête (${lo}..${hi})", hi - lo > 8)
    }
}

class TemperamentTest {
    @Test fun joyRisesAndFades() {
        var t = 1_000_000L
        val m = Temperament { t }
        m.feel(0.4)
        assertTrue(m.joy > 0.85)
        t += 60 * 60_000L
        m.feel(0.0)
        assertTrue("revient vers la normale (${m.joy})", m.joy < 0.6)
    }

    @Test fun lonelyAfterHoursAlone() {
        var t = 1_000_000L
        val m = Temperament { t }
        assertTrue(m.loneliness() < 0.1)
        t += 4 * 3600_000L
        assertTrue(m.loneliness() > 0.9)
        m.sawPerson()
        assertTrue(m.loneliness() < 0.1)
    }
}

class SpeechCueTest {
    @Test fun yesNodsNoShakes() {
        var t = 10_000L
        val x = Expressive({ t }, { 0.5 })
        val base = Expressive({ t }, { 0.5 })
        x.cue(Expressive.Cue.YES)
        var deepest = 0.0
        repeat(20) { deepest = minOf(deepest, x.pose(0f, true, false, false, 0.8).tilt - base.pose(0f, true, false, false, 0.8).tilt); t += 50 }
        assertTrue("oui de la tête ($deepest)", deepest < -5)
        x.cue(Expressive.Cue.NO)
        var lo = 0.0; var hi = 0.0
        repeat(20) { val d = x.pose(0f, false, false, false, 0.8).pan - base.pose(0f, false, false, false, 0.8).pan; lo = minOf(lo, d); hi = maxOf(hi, d); t += 50 }
        assertTrue("non de la tête ($lo..$hi)", hi - lo > 8)
    }
}
